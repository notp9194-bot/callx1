package com.callx.app.db.paging;

import androidx.annotation.NonNull;
import androidx.paging.LoadType;
import androidx.paging.PagingState;
import androidx.paging.rxjava3.RxRemoteMediator;

import com.callx.app.db.entity.MessageEntity;
import com.callx.app.repository.ChatRepository;

import io.reactivex.rxjava3.core.Single;

/**
 * RemoteMediator — bridges Paging3 to Firebase for OLDER chat history.
 *
 * Previously this app only ever pulled the most recent PAGE_SIZE messages
 * into Room (see ChatRepository#syncMessagesDelta, a one-shot "delta sync").
 * Scrolling up past whatever happened to already be in Room simply hit the
 * end of MessageKeysetPagingSource with nothing more to show — there was no
 * mechanism to reach further back into a chat's full history from Firebase.
 * That's "manual pagination": it worked for the common case (recently active
 * chats) but silently stopped for long-scrollback chats.
 *
 * This RemoteMediator plugs into the existing keyset Pager (see
 * ChatActivity#attachPagerWithKey) and is invoked automatically by Paging3
 * whenever MessageKeysetPagingSource's PREPEND runs dry locally:
 *   1. Paging3 asks this mediator to load(PREPEND, state)
 *   2. We read the oldest timestamp currently loaded (state's first item)
 *   3. We ask ChatRepository to pull one more page OLDER than that timestamp
 *      straight from Firebase and insert it into Room
 *   4. Room's invalidation tracker fires → MessageKeysetPagingSource
 *      automatically re-queries → Paging3 delivers the new page to the UI
 *
 * REFRESH and APPEND are no-ops here on purpose:
 *   - REFRESH: syncMessagesDelta() already keeps the newest page fresh via
 *     its own realtime-ish delta sync, triggered from getMessages(); no
 *     extra network round-trip needed just to satisfy the mediator contract.
 *   - APPEND: new incoming messages arrive through the existing realtime
 *     listener path (not through paging upward), so there's never "newer"
 *     history to fetch from the network on APPEND for this chat screen.
 */
public class MessageRemoteMediator extends RxRemoteMediator<Long, MessageEntity> {

    private final ChatRepository repository;
    private final String chatId;
    private final int pageSize;

    public MessageRemoteMediator(ChatRepository repository, String chatId, int pageSize) {
        this.repository = repository;
        this.chatId = chatId;
        this.pageSize = pageSize;
    }

    @NonNull
    @Override
    public Single<MediatorResult> loadSingle(@NonNull LoadType loadType,
                                              @NonNull PagingState<Long, MessageEntity> state) {
        if (loadType == LoadType.REFRESH) {
            // Freshness for the newest page is already handled by the
            // existing delta-sync path (getMessages() → syncMessagesDelta()).
            return Single.just(new MediatorResult.Success(false));
        }

        if (loadType == LoadType.APPEND) {
            // No forward network history for this screen — see class doc.
            return Single.just(new MediatorResult.Success(true));
        }

        // PREPEND — find the oldest message currently loaded in Room/Paging
        // so we know where to ask Firebase to continue from.
        MessageEntity oldestLoaded = state.firstItemOrNull();
        if (oldestLoaded == null || oldestLoaded.timestamp == null) {
            // Nothing loaded yet to anchor from — let the local PagingSource
            // handle the initial page; nothing for the network to do yet.
            return Single.just(new MediatorResult.Success(true));
        }

        return repository.fetchOlderMessagesFromFirebase(chatId, oldestLoaded.timestamp, pageSize)
                .map(insertedCount -> {
                    boolean endReached = insertedCount < pageSize;
                    return (MediatorResult) new MediatorResult.Success(endReached);
                })
                .onErrorReturn(MediatorResult.Error::new);
    }
}
