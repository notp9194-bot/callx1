package com.callx.app.chatlist;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.repository.MessageRepository;
import com.callx.app.utils.FirebaseUtils;
import java.util.List;

/**
 * ChatListViewModel — bridges MessageRepository and ChatsFragment.
 *
 * WhatsApp-level pattern:
 *   ChatsFragment
 *     → observes LiveData<List<ChatEntity>> from this ViewModel
 *     → never touches Firebase or Room directly for list data
 *
 * Room is the source of truth for the chat list.
 * Firebase delta-syncs run in MessageRepository on demand.
 *
 * Shared between ChatsFragment + GroupsFragment via activity scope
 * (both are in the same ViewPager tab set).
 */
public class ChatListViewModel extends AndroidViewModel {

    private final MessageRepository repo;
    private final String myUid;

    // ── Exposed LiveData ──────────────────────────────────────────────────

    /** Active (non-archived) chats, ordered by last message time. */
    public final LiveData<List<ChatEntity>> chats;

    /** Archived chats. */
    public final LiveData<List<ChatEntity>> archivedChats;

    /** Starred messages — for the "Starred Messages" screen. */
    public final LiveData<List<MessageEntity>> starredMessages;

    private final MutableLiveData<String> _searchQuery = new MutableLiveData<>("");
    public final LiveData<String> searchQuery = _searchQuery;

    private final MutableLiveData<Boolean> _loading = new MutableLiveData<>(false);
    public final LiveData<Boolean> loading = _loading;

    public ChatListViewModel(@NonNull Application app) {
        super(app);
        repo  = MessageRepository.getInstance(app);
        myUid = FirebaseUtils.getCurrentUid();

        chats          = repo.getChats();
        archivedChats  = repo.getArchivedChats();
        starredMessages= repo.getStarredMessages();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Call from ChatsFragment.onStart().
     * Triggers delta-sync for top N chats to keep the list fresh.
     */
    public void refreshTopChats(List<String> topChatIds) {
        if (topChatIds == null) return;
        for (String chatId : topChatIds) {
            repo.deltaSync(chatId);
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    public void setSearchQuery(String query) {
        _searchQuery.setValue(query != null ? query.trim().toLowerCase() : "");
    }

    // ── Unread count ─────────────────────────────────────────────────────

    /**
     * Total unread message count across all chats.
     * Drives the unread badge on the Chats tab.
     * Computed from Room so it's always consistent with local DB state.
     */
    public LiveData<Integer> getTotalUnreadCount() {
        // Simple implementation: sum unreadCount from all ChatEntities
        // A MediatorLiveData approach keeps this reactive
        MutableLiveData<Integer> result = new MutableLiveData<>(0);
        chats.observeForever(list -> {
            if (list == null) { result.setValue(0); return; }
            int total = 0;
            for (ChatEntity c : list) total += (c.unread != null && c.unread > 0 ? c.unread.intValue() : 0);
            result.setValue(total);
        });
        return result;
    }
}
