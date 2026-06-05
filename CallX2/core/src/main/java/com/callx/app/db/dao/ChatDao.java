package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.ChatEntity;

import java.util.List;

/**
 * ChatDao — all queries for the chats table.
 *
 * v20 additions:
 *   • getActiveChatsSorted()  — excludes archived chats (main list)
 *   • getArchivedChats()      — archived chats screen
 *   • updateArchived()        — archive/unarchive
 *   • getArchivedCount()      — badge count on archive entry
 *   • updateDisappearTimer()  — set disappearing messages for a chat
 */
@Dao
public interface ChatDao {

    // ─────────────────────────────────────────────────────────────
    // MAIN CHAT LIST — v20: excludes archived by default
    // ─────────────────────────────────────────────────────────────

    /**
     * Active (non-archived) chats — shown in main chat list.
     * Pinned chats always appear first, then sorted by lastMessageAt.
     */
    @Query("SELECT * FROM chats WHERE (archived IS NULL OR archived = 0) ORDER BY CASE WHEN pinned = 1 THEN 0 ELSE 1 END, lastMessageAt DESC")
    LiveData<List<ChatEntity>> getActiveChatsSorted();

    /** Sync version of active chats — for background threads. */
    @Query("SELECT * FROM chats WHERE (archived IS NULL OR archived = 0) ORDER BY CASE WHEN pinned = 1 THEN 0 ELSE 1 END, lastMessageAt DESC")
    List<ChatEntity> getActiveChatsSync();

    /** Legacy — returns ALL chats including archived (kept for backward compat). */
    @Query("SELECT * FROM chats ORDER BY lastMessageAt DESC")
    LiveData<List<ChatEntity>> getAllChats();

    @Query("SELECT * FROM chats ORDER BY lastMessageAt DESC")
    List<ChatEntity> getAllChatsSync();

    // ─────────────────────────────────────────────────────────────
    // ARCHIVE — v20 NEW
    // ─────────────────────────────────────────────────────────────

    /** Archived chats screen — sorted by lastMessageAt. */
    @Query("SELECT * FROM chats WHERE archived = 1 ORDER BY lastMessageAt DESC")
    LiveData<List<ChatEntity>> getArchivedChats();

    /** Count of archived chats — for badge on archive entry in chat list. */
    @Query("SELECT COUNT(*) FROM chats WHERE archived = 1")
    int getArchivedCount();

    /** Archive or unarchive a chat. */
    @Query("UPDATE chats SET archived = :archived WHERE chatId = :chatId")
    void updateArchived(String chatId, boolean archived);

    // ─────────────────────────────────────────────────────────────
    // DISAPPEARING MESSAGES — v20 NEW
    // ─────────────────────────────────────────────────────────────

    /** Set disappearing messages timer for a chat (ms). 0 = off. */
    @Query("UPDATE chats SET disappearTimer = :timerMs WHERE chatId = :chatId")
    void updateDisappearTimer(String chatId, long timerMs);

    /** Get current disappear timer for a chat. */
    @Query("SELECT disappearTimer FROM chats WHERE chatId = :chatId LIMIT 1")
    Long getDisappearTimer(String chatId);

    // ─────────────────────────────────────────────────────────────
    // SINGLE CHAT
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    // DRAFT — v18
    // ─────────────────────────────────────────────────────────────

    @Query("UPDATE chats SET draft = :draft WHERE chatId = :chatId")
    void saveDraft(String chatId, String draft);

    @Query("SELECT draft FROM chats WHERE chatId = :chatId LIMIT 1")
    String getDraft(String chatId);

    @Query("SELECT draft FROM chats WHERE chatId = :chatId LIMIT 1")
    LiveData<String> getDraftLive(String chatId);

    // ─────────────────────────────────────────────────────────────
    // READ RECEIPT OFFLINE QUEUE — v18
    // ─────────────────────────────────────────────────────────────

    @Query("UPDATE chats SET pendingMarkRead = 1, unread = 0 WHERE chatId = :chatId")
    void queueMarkRead(String chatId);

    @Query("SELECT * FROM chats WHERE pendingMarkRead = 1")
    List<ChatEntity> getPendingMarkReadChats();

    @Query("UPDATE chats SET pendingMarkRead = 0 WHERE chatId = :chatId")
    void clearPendingMarkRead(String chatId);
}
