package com.callx.app.chatlist;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.db.entity.ChatFolderEntity;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.repository.MessageRepository;
import com.callx.app.utils.FirebaseUtils;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * ChatListViewModel v2 — Chat Folders / Labels support added.
 *
 * New LiveData:
 *   folders          — all ChatFolderEntities, ordered by sortOrder
 *   selectedFolderId — currently active folder filter (-1 = All Chats)
 *   chatsForFolder() — filtered chats by folder rules
 *
 * The existing chats / archivedChats / starredMessages LiveData are unchanged.
 */
public class ChatListViewModel extends AndroidViewModel {

    private final MessageRepository repo;
    private final AppDatabase       db;
    private final String myUid;

    // ── Core LiveData ─────────────────────────────────────────────────────────

    /** Active (non-archived) chats, ordered by last message time. */
    public final LiveData<List<ChatEntity>> chats;

    /** Archived chats. */
    public final LiveData<List<ChatEntity>> archivedChats;

    /** Starred messages — for the per-chat "Starred Messages" screen. */
    public final LiveData<List<MessageEntity>> starredMessages;

    // ── Folder LiveData ───────────────────────────────────────────────────────

    /** All Chat Folders, ordered by sortOrder. */
    public final LiveData<List<ChatFolderEntity>> folders;

    /**
     * Currently selected folder id.
     * -1 = "All Chats" (no folder filter).
     * -2 = "Unread" quick-filter.
     */
    private final MutableLiveData<Integer> _selectedFolderId = new MutableLiveData<>(-1);
    public final LiveData<Integer> selectedFolderId = _selectedFolderId;

    // ── Search ────────────────────────────────────────────────────────────────

    private final MutableLiveData<String> _searchQuery = new MutableLiveData<>("");
    public final LiveData<String> searchQuery = _searchQuery;

    private final MutableLiveData<Boolean> _loading = new MutableLiveData<>(false);
    public final LiveData<Boolean> loading = _loading;

    public ChatListViewModel(@NonNull Application app) {
        super(app);
        repo = MessageRepository.getInstance(app);
        db   = AppDatabase.getInstance(app);
        myUid = FirebaseUtils.getCurrentUid();

        chats           = repo.getChats();
        archivedChats   = repo.getArchivedChats();
        starredMessages = repo.getStarredMessages();
        folders         = db.chatFolderDao().getAllFolders();
    }

    // ── Folder filter ─────────────────────────────────────────────────────────

    public void selectFolder(int folderId) {
        _selectedFolderId.setValue(folderId);
    }

    /**
     * Returns a LiveData of chats filtered by the given folder rules.
     * folderId = -1 → all chats (no filter, returns chats LiveData).
     * folderId = -2 → unread chats only.
     * folderId >= 0 → chats assigned to that folder.
     */
    public LiveData<List<ChatEntity>> getChatsForFolder(int folderId) {
        if (folderId == -2) return db.chatDao().getUnreadChats();
        if (folderId >= 0)  return db.chatDao().getChatsForFolder(folderId);
        return chats; // -1 = All
    }

    /** Assign a chat to a folder (background thread). */
    public void assignChatToFolder(String chatId, int folderId) {
        Executors.newSingleThreadExecutor().execute(() ->
            db.chatDao().setChatFolder(chatId, folderId));
    }

    /** Remove a chat from its folder. */
    public void removeChatFromFolder(String chatId) {
        Executors.newSingleThreadExecutor().execute(() ->
            db.chatDao().removeChatFromFolder(chatId));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Call from ChatsFragment.onStart(). */
    public void refreshTopChats(List<String> topChatIds) {
        if (topChatIds == null) return;
        for (String chatId : topChatIds) repo.deltaSync(chatId);
    }

    // ── Search ────────────────────────────────────────────────────────────────

    public void setSearchQuery(String query) {
        _searchQuery.setValue(query != null ? query.trim().toLowerCase() : "");
    }

    // ── Unread count ─────────────────────────────────────────────────────────

    public LiveData<Integer> getTotalUnreadCount() {
        MutableLiveData<Integer> result = new MutableLiveData<>(0);
        chats.observeForever(list -> {
            if (list == null) { result.setValue(0); return; }
            int total = 0;
            for (ChatEntity c : list)
                total += (c.unread != null && c.unread > 0 ? c.unread.intValue() : 0);
            result.setValue(total);
        });
        return result;
    }
}
