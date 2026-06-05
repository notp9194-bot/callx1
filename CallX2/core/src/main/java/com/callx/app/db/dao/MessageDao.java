package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.callx.app.db.entity.MessageEntity;

import java.util.List;

/**
 * Message DAO — all queries for the messages table.
 *
 * v20 additions:
 *   • searchInChat()       — in-chat full-text search
 *   • searchGlobal()       — global cross-chat search
 *   • getExpiredMessages() — disappearing messages auto-delete
 *   • markDeleted()        — soft-delete by id (used by DisappearingMessageWorker)
 *   • getGroupUnread()     — group read receipt tracking
 */
@Dao
public interface MessageDao {

    // ─────────────────────────────────────────────────────────────
    // LIVE / REACTIVE QUERIES
    // ─────────────────────────────────────────────────────────────

    /** LiveData stream — UI auto-updates when DB changes. ASC = oldest → newest. */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    LiveData<List<MessageEntity>> getMessages(String chatId);

    /**
     * Paging 3 — Room native PagingSource for smooth infinite scroll.
     * ASC order: messages load oldest → newest (standard chat layout).
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    PagingSource<Integer, MessageEntity> getMessagesPagingSource(String chatId);

    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    List<MessageEntity> getMessagesPaged(String chatId, int limit, int offset);

    // ─────────────────────────────────────────────────────────────
    // SEARCH — v20 NEW
    // ─────────────────────────────────────────────────────────────

    /**
     * In-chat search: find messages matching query within a specific chat.
     * Uses LIKE for broad match. Returns newest first for relevance.
     * Caller wraps query as "%" + userInput + "%".
     */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND text LIKE :query AND (deleted IS NULL OR deleted = 0) ORDER BY timestamp DESC LIMIT 200")
    List<MessageEntity> searchInChat(String chatId, String query);

    /**
     * Global search: find messages matching query across ALL chats.
     * Returns newest first. Limit 500 to avoid OOM.
     */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE text LIKE :query AND (deleted IS NULL OR deleted = 0) ORDER BY timestamp DESC LIMIT 500")
    List<MessageEntity> searchGlobal(String query);

    // ─────────────────────────────────────────────────────────────
    // DISAPPEARING MESSAGES — v20 NEW
    // ─────────────────────────────────────────────────────────────

    /**
     * Fetch messages whose disappearAt time has passed and are not already deleted.
     * DisappearingMessageWorker calls this every 15 minutes.
     */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE disappearAt IS NOT NULL AND disappearAt > 0 AND disappearAt <= :now AND (deleted IS NULL OR deleted = 0)")
    List<MessageEntity> getExpiredMessages(long now);

    /** Mark a message as deleted (soft-delete) — used by DisappearingMessageWorker. */
    @WorkerThread
    @Query("UPDATE messages SET deleted = 1, text = '[Message deleted]' WHERE id = :messageId")
    void markDeleted(String messageId);

    // ─────────────────────────────────────────────────────────────
    // DELTA SYNC
    // ─────────────────────────────────────────────────────────────

    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND timestamp > :lastTimestamp ORDER BY timestamp ASC")
    List<MessageEntity> getMessagesSince(String chatId, long lastTimestamp);

    @WorkerThread
    @Query("SELECT MAX(timestamp) FROM messages WHERE chatId = :chatId")
    Long getLastTimestamp(String chatId);

    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    MessageEntity getLastMessage(String chatId);

    // ─────────────────────────────────────────────────────────────
    // STARRED MESSAGES
    // ─────────────────────────────────────────────────────────────

    @Query("SELECT * FROM messages WHERE starred = 1 ORDER BY timestamp DESC")
    LiveData<List<MessageEntity>> getStarredMessages();

    @WorkerThread
    @Query("SELECT * FROM messages WHERE starred = 1 ORDER BY timestamp ASC")
    List<MessageEntity> getStarredMessagesSync();

    // ─────────────────────────────────────────────────────────────
    // PENDING / OFFLINE QUEUE
    // ─────────────────────────────────────────────────────────────

    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND status = 'pending' ORDER BY timestamp ASC")
    List<MessageEntity> getPendingMessages(String chatId);

    @WorkerThread
    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    MessageEntity getMessageById(String messageId);

    @WorkerThread
    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND timestamp > :timestamp")
    int countMessagesAfterTimestamp(String chatId, long timestamp);

    @WorkerThread
    @Query("SELECT * FROM messages WHERE mediaLocalPath IS NOT NULL AND (mediaUrl IS NULL OR mediaUrl = '') AND status = 'pending' ORDER BY timestamp ASC")
    List<MessageEntity> getFailedMediaUploads();

    @WorkerThread
    @Query("SELECT * FROM messages WHERE status = 'pending' ORDER BY timestamp ASC")
    List<MessageEntity> getAllPendingMessages();

    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND senderId = :partnerUid AND (status IS NULL OR status != 'read') ORDER BY timestamp ASC")
    List<MessageEntity> getUnreadMessages(String chatId, String partnerUid);

    // ─────────────────────────────────────────────────────────────
    // WRITE OPERATIONS
    // ─────────────────────────────────────────────────────────────

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMessages(List<MessageEntity> messages);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMessage(MessageEntity message);

    @WorkerThread
    @Update
    void updateMessage(MessageEntity message);

    @WorkerThread
    @Query("UPDATE messages SET deleted = 1, text = '' WHERE id = :messageId")
    void softDelete(String messageId);

    @WorkerThread
    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    void updateStatus(String messageId, String status);

    @WorkerThread
    @Query("UPDATE messages SET starred = :starred WHERE id = :messageId")
    void updateStarred(String messageId, boolean starred);

    @WorkerThread
    @Query("UPDATE messages SET text = :newText, edited = 1, editedAt = :editedAt WHERE id = :messageId")
    void updateText(String messageId, String newText, long editedAt);

    // ─────────────────────────────────────────────────────────────
    // PRUNING / CLEANUP
    // ─────────────────────────────────────────────────────────────

    @WorkerThread
    @Query("DELETE FROM messages WHERE chatId = :chatId AND timestamp < " +
           "(SELECT timestamp FROM messages WHERE chatId = :chatId " +
           "ORDER BY timestamp DESC LIMIT 1 OFFSET :keepCount)")
    void pruneOldMessages(String chatId, int keepCount);

    @WorkerThread
    @Query("DELETE FROM messages WHERE timestamp < :cutoffTimestamp AND deleted != 1")
    int deleteMessagesOlderThan(long cutoffTimestamp);

    @WorkerThread
    @Query("DELETE FROM messages WHERE chatId = :chatId")
    void deleteAllForChat(String chatId);

    // ─────────────────────────────────────────────────────────────
    // STATS
    // ─────────────────────────────────────────────────────────────

    @WorkerThread
    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    int getMessageCount(String chatId);

    @WorkerThread
    @Query("SELECT COUNT(*) FROM messages")
    long getTotalMessageCount();

    @WorkerThread
    @Query("DELETE FROM messages")
    void deleteAll();
}
