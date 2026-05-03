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
 * FIX #3 (MEDIUM): Ordering inconsistency fixed.
 *
 *   Old:
 *     getMessagesPagingSource() → ORDER BY timestamp ASC  (oldest → newest)
 *     getMessagesPaged()        → ORDER BY timestamp DESC (newest → oldest)
 *
 *   → CacheManager.preloadTopChats() filled MemoryCache with DESC-ordered lists.
 *     Any code path that consumed this cache and displayed it directly showed
 *     messages in REVERSE order (newest at top, oldest at bottom).
 *
 *   Fix: ALL queries that return messages for display use ASC order.
 *     getMessagesPaged() is now ASC to match getMessagesPagingSource().
 *     getLastMessage() still uses DESC LIMIT 1 — correct (it fetches one row).
 *     getStarredMessages() remains DESC — correct (newest starred first in list).
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
     * PagingSource auto-invalidates when Room detects DB changes.
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    PagingSource<Integer, MessageEntity> getMessagesPagingSource(String chatId);

    /**
     * Manual offset-based page — used by CacheManager.preloadTopChats().
     *
     * FIX #3: Changed from DESC to ASC.
     * Old: ORDER BY timestamp DESC → MemoryCache had reversed-order list.
     * New: ORDER BY timestamp ASC  → consistent with PagingSource order.
     * Result: preloaded cache matches what the pager shows → no reorder surprise.
     */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    List<MessageEntity> getMessagesPaged(String chatId, int limit, int offset);

    // ─────────────────────────────────────────────────────────────
    // DELTA SYNC
    // ─────────────────────────────────────────────────────────────

    /** Delta sync: only messages newer than lastTimestamp. */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND timestamp > :lastTimestamp ORDER BY timestamp ASC")
    List<MessageEntity> getMessagesSince(String chatId, long lastTimestamp);

    /** Most recent message timestamp per chat — used for delta sync range. */
    @WorkerThread
    @Query("SELECT MAX(timestamp) FROM messages WHERE chatId = :chatId")
    Long getLastTimestamp(String chatId);

    /** Last message row — used for chat list preview. */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    MessageEntity getLastMessage(String chatId);

    // ─────────────────────────────────────────────────────────────
    // STARRED MESSAGES
    // ─────────────────────────────────────────────────────────────

    /** Starred messages — newest first (correct for starred list screen). */
    @Query("SELECT * FROM messages WHERE starred = 1 ORDER BY timestamp DESC")
    LiveData<List<MessageEntity>> getStarredMessages();

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

    // ─────────────────────────────────────────────────────────────
    // PRUNING / CLEANUP
    // ─────────────────────────────────────────────────────────────

    /** Prune old messages — keep last N per chat. Storage management. */
    @WorkerThread
    @Query("DELETE FROM messages WHERE chatId = :chatId AND timestamp < " +
           "(SELECT timestamp FROM messages WHERE chatId = :chatId " +
           "ORDER BY timestamp DESC LIMIT 1 OFFSET :keepCount)")
    void pruneOldMessages(String chatId, int keepCount);

    @WorkerThread
    @Query("DELETE FROM messages WHERE chatId = :chatId")
    void deleteAllForChat(String chatId);

    // ─────────────────────────────────────────────────────────────
    // STATS (used by CacheStatsActivity)
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
