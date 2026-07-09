package com.callx.app.db.paging;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.paging.PagingState;
import androidx.paging.rxjava3.RxPagingSource;

import com.callx.app.db.dao.MessageDao;
import com.callx.app.db.entity.MessageEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * PERF FIX — keyset (a.k.a. cursor) pagination for the chat screen.
 *
 * Room's auto-generated {@code PagingSource<Integer, MessageEntity>} from a
 * plain {@code ORDER BY timestamp ASC} query is OFFSET-based under the hood:
 * to show a page anchored near the END of the table (which is exactly what
 * opening a chat needs — land on the newest messages), SQLite has to walk
 * past `OFFSET` rows before it can return `LIMIT` rows. That walk costs is
 * proportional to how many messages that chat has — a 20-message chat opens
 * instantly, a 5,000-message chat visibly stalls, even with the right index,
 * purely from the OFFSET skip. This is why the earlier fixes (warm caching,
 * removing thread-hops, postponing the enter transition) all helped small
 * chats but the delay came right back on chats with a lot of history.
 *
 * This class keys pages by `timestamp` instead of row position. Every page
 * is fetched with `WHERE chatId=? AND timestamp < / > :key`, which the
 * existing (chatId, timestamp) index answers directly — no OFFSET, so cost
 * no longer grows with total chat history. Same technique WhatsApp/Telegram/
 * Signal-style chat apps use for this exact reason.
 *
 * Key = timestamp of the item just past whichever end of the loaded window
 * that page's request extends from. REFRESH normally ignores the key and
 * loads the most recent PAGE_SIZE messages — the default "open chat" case,
 * always bottom-anchored, same as WhatsApp/Telegram/Signal. The one
 * exception: when the caller supplies an explicit REFRESH key (see
 * {@code anchorKey} below), it's treated as an ANCHORED open — used by
 * ChatActivity's WhatsApp-style scroll restore to reopen a chat centered on
 * the exact message the user was reading when they left, instead of always
 * snapping back to the bottom.
 */
public class MessageKeysetPagingSource extends RxPagingSource<Long, MessageEntity> {

    private final MessageDao dao;
    private final String chatId;
    private final int pageSize;

    public MessageKeysetPagingSource(MessageDao dao, String chatId, int pageSize) {
        this.dao = dao;
        this.chatId = chatId;
        this.pageSize = pageSize;
    }

    @NonNull
    @Override
    public Single<LoadResult<Long, MessageEntity>> loadSingle(@NonNull LoadParams<Long> params) {
        return Single.fromCallable(() -> {
            List<MessageEntity> page;
            Long prevKey; // PREPEND key — load OLDER than this page
            Long nextKey; // APPEND key  — load NEWER than this page

            if (params instanceof LoadParams.Prepend) {
                // Loading older messages, above what's currently shown.
                long before = params.getKey();
                List<MessageEntity> desc = dao.getMessagesBeforeDesc(chatId, before, pageSize);
                page = new ArrayList<>(desc);
                Collections.reverse(page); // DESC → ASC for display order
                prevKey = page.isEmpty() ? null : page.get(0).timestamp;
                if (desc.size() < pageSize) prevKey = null; // reached true start of history
                nextKey = before; // resume forward pagination right where this page ends

            } else if (params instanceof LoadParams.Append) {
                // Loading newer messages, below what's currently shown.
                long after = params.getKey();
                List<MessageEntity> asc = dao.getMessagesAfterAsc(chatId, after, pageSize);
                page = asc;
                nextKey = page.isEmpty() ? null : page.get(page.size() - 1).timestamp;
                if (asc.size() < pageSize) nextKey = null;
                prevKey = after;

            } else if (params.getKey() != null) {
                // ANCHORED REFRESH — scroll-restore reopening this chat
                // centered on a specific saved message timestamp. Split the
                // window roughly in half: older messages strictly before the
                // anchor, plus the anchor itself and newer messages after it,
                // then stitch them into one ASC page — same shape as a normal
                // page, so the rest of the Paging3 pipeline doesn't need to
                // know this REFRESH was special.
                long anchor = params.getKey();
                int half = Math.max(1, pageSize / 2);
                List<MessageEntity> olderDesc = dao.getMessagesBeforeDesc(chatId, anchor, half);
                List<MessageEntity> older = new ArrayList<>(olderDesc);
                Collections.reverse(older); // DESC → ASC

                // Remaining budget goes to the anchor-forward half so a short
                // "older" side (anchor near the very start of history) still
                // fills out the page instead of returning a half-empty one.
                int forwardLimit = pageSize - older.size();
                List<MessageEntity> atOrAfter =
                        dao.getMessagesAtOrAfterAsc(chatId, anchor, forwardLimit);

                page = new ArrayList<>(older.size() + atOrAfter.size());
                page.addAll(older);
                page.addAll(atOrAfter);

                prevKey = older.isEmpty() ? null : older.get(0).timestamp;
                if (olderDesc.size() < half) prevKey = null; // reached true start of history
                nextKey = atOrAfter.isEmpty() ? null : atOrAfter.get(atOrAfter.size() - 1).timestamp;
                if (atOrAfter.size() < forwardLimit) nextKey = null; // reached the tail

            } else {
                // Plain REFRESH — always the most recent page; this is the
                // default "open chat" case (first-ever open, or scroll-
                // restore found nothing saved for this chat).
                List<MessageEntity> desc = dao.getMessagesLatestDesc(chatId, pageSize);
                page = new ArrayList<>(desc);
                Collections.reverse(page); // DESC → ASC for display order
                prevKey = page.isEmpty() ? null : page.get(0).timestamp;
                if (desc.size() < pageSize) prevKey = null;
                nextKey = null; // already at the newest message that existed at load time
            }

            return (LoadResult<Long, MessageEntity>) new LoadResult.Page<>(
                    page, prevKey, nextKey,
                    LoadResult.Page.COUNT_UNDEFINED, LoadResult.Page.COUNT_UNDEFINED);
        }).subscribeOn(Schedulers.io());
    }

    @Nullable
    @Override
    public Long getRefreshKey(@NonNull PagingState<Long, MessageEntity> state) {
        // We always want REFRESH to mean "most recent page" (see loadSingle
        // above, which ignores the key on REFRESH), so there's no anchor-
        // preserving refresh key to compute here — every refresh of this
        // Pager happens because we explicitly built a brand-new one
        // (attachPagerWithKey), never because Room auto-invalidated this
        // exact PagingSource instance mid-scroll.
        return null;
    }
}
