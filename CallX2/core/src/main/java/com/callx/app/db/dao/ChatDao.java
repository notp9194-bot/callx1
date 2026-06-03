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

    @Query("SELECT * FROM chats ORDER BY lastMessageAt DESC")
    LiveData<List<ChatEntity>> getAllChats();

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

    @Query("SELECT COUNT(*) FROM chats")
    int getChatCount();

    // ── v18 IMPROVEMENT 2: Draft persist ──────────────────────────────────

    /** Draft save karo — user navigate kare tab call karo. */
    @Query("UPDATE chats SET draft = :draft WHERE chatId = :chatId")
    void saveDraft(String chatId, String draft);

    /** Draft load karo — chat open hone par. */
    @Query("SELECT draft FROM chats WHERE chatId = :chatId LIMIT 1")
    String getDraft(String chatId);

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
}
