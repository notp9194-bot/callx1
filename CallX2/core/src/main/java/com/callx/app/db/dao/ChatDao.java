package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.ChatEntity;

import java.util.List;

@Dao
public interface ChatDao {

    /** Active (non-archived) chats — chat list screen. */
    @Query("SELECT * FROM chats WHERE archived = 0 OR archived IS NULL ORDER BY lastMessageAt DESC")
    LiveData<List<ChatEntity>> getAllChats();

    /** v35: Archived chats — "Archived Chats" screen. */
    @Query("SELECT * FROM chats WHERE archived = 1 ORDER BY lastMessageAt DESC")
    LiveData<List<ChatEntity>> getArchivedChats();

    /** v35: Chat ko archive/unarchive karo. */
    @Query("UPDATE chats SET archived = :archived WHERE chatId = :chatId")
    void setArchived(String chatId, boolean archived);

    /**
     * FIX v8: Blocking synchronous version of getAllChats().
     *
     * Use this on background threads (e.g., in SyncWorker.doWork()).
     * LiveData.getValue() returns null on background threads because no
     * lifecycle owner is observing — this @Query is safe everywhere.
     */
    @Query("SELECT * FROM chats ORDER BY lastMessageAt DESC")
    List<ChatEntity> getAllChatsSync();

    @Query("SELECT * FROM chats WHERE chatId = :chatId LIMIT 1")
    ChatEntity getChat(String chatId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChat(ChatEntity chat);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChats(List<ChatEntity> chats);

    @Query("UPDATE chats SET lastMessage = :msg, lastMessageAt = :ts WHERE chatId = :chatId")
    void updateLastMessage(String chatId, String msg, long ts);

    @Query("UPDATE chats SET unread = :count WHERE chatId = :chatId")
    void updateUnread(String chatId, long count);

    @Query("UPDATE chats SET muted = :muted WHERE chatId = :chatId")
    void updateMuted(String chatId, boolean muted);

    @Query("UPDATE chats SET pinned = :pinned WHERE chatId = :chatId")
    void updatePinned(String chatId, boolean pinned);

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    void deleteChat(String chatId);

    // ── v21 DELETE SYSTEM ──────────────────────────────────────────────────

    /** Partner UID se chat delete karo (1-on-1 chat list cleanup). */
    @Query("DELETE FROM chats WHERE partnerUid = :partnerUid")
    void deleteByPartnerUid(String partnerUid);

    /** Saare chats delete karo (Delete All). */
    @Query("DELETE FROM chats")
    void deleteAllChats();

    @Query("SELECT COUNT(*) FROM chats")
    int getChatCount();

    // ── v18 IMPROVEMENT 2: Draft persist ──────────────────────────────────

    /** Draft save karo — user navigate kare tab call karo. */
    @Query("UPDATE chats SET draft = :draft WHERE chatId = :chatId")
    void saveDraft(String chatId, String draft);

    /** Draft load karo — chat open hone par. */
    @Query("SELECT draft FROM chats WHERE chatId = :chatId LIMIT 1")
    String getDraft(String chatId);

    /** Draft LiveData — observe karo for reactive UI updates. */
    @Query("SELECT draft FROM chats WHERE chatId = :chatId LIMIT 1")
    LiveData<String> getDraftLive(String chatId);

    // ── v18 IMPROVEMENT 4: Read receipt offline queue ─────────────────────

    /** Offline mein markRead fail hua — queue karo. */
    @Query("UPDATE chats SET pendingMarkRead = 1, unread = 0 WHERE chatId = :chatId")
    void queueMarkRead(String chatId);

    /** Online aane par pending read receipts push karne ke liye. */
    @Query("SELECT * FROM chats WHERE pendingMarkRead = 1")
    List<ChatEntity> getPendingMarkReadChats();

    /** Read receipt successfully pushed — clear karo. */
    @Query("UPDATE chats SET pendingMarkRead = 0 WHERE chatId = :chatId")
    void clearPendingMarkRead(String chatId);

    // ── v38: Chat Folders ─────────────────────────────────────────────────────

    /** Chats belonging to a specific folder (non-archived). */
    @Query("SELECT * FROM chats WHERE folderId = :folderId AND (archived = 0 OR archived IS NULL) ORDER BY lastMessageAt DESC")
    LiveData<List<ChatEntity>> getChatsForFolder(int folderId);

    /** Contacts (type='private') only — for "Contacts" folder type. */
    @Query("SELECT * FROM chats WHERE type = 'private' AND (archived = 0 OR archived IS NULL) ORDER BY lastMessageAt DESC")
    LiveData<List<ChatEntity>> getPrivateChats();

    /** Groups (type='group') only — for "Groups" folder type. */
    @Query("SELECT * FROM chats WHERE type = 'group' AND (archived = 0 OR archived IS NULL) ORDER BY lastMessageAt DESC")
    LiveData<List<ChatEntity>> getGroupChats();

    /** Unread chats only — for "Unread" folder type. */
    @Query("SELECT * FROM chats WHERE (unread IS NOT NULL AND unread > 0) AND (archived = 0 OR archived IS NULL) ORDER BY lastMessageAt DESC")
    LiveData<List<ChatEntity>> getUnreadChats();

    /** Assign a chat to a folder. */
    @Query("UPDATE chats SET folderId = :folderId WHERE chatId = :chatId")
    void setChatFolder(String chatId, int folderId);

    /** Remove a chat from its folder (put back in All Chats). */
    @Query("UPDATE chats SET folderId = NULL WHERE chatId = :chatId")
    void removeChatFromFolder(String chatId);

    /** When a folder is deleted, remove all chats from that folder. */
    @Query("UPDATE chats SET folderId = NULL WHERE folderId = :folderId")
    void clearFolder(int folderId);

    /** Set labels on a chat (comma-separated string). */
    @Query("UPDATE chats SET labels = :labels WHERE chatId = :chatId")
    void setLabels(String chatId, String labels);
}
