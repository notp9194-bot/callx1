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

    /** Starred messages sync version — for offline background thread use. */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE starred = 1 ORDER BY timestamp ASC")
    List<MessageEntity> getStarredMessagesSync();

    // ─────────────────────────────────────────────────────────────
    // WRITE OPERATIONS
    // ─────────────────────────────────────────────────────────────

    /** v15: Pending messages jo offline the — retry ke liye (specific chat). */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND status = 'pending' ORDER BY timestamp ASC")
    List<MessageEntity> getPendingMessages(String chatId);

    /** v18 IMPROVEMENT 5: Single message by id — media upload retry ke liye. */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    MessageEntity getMessageById(String messageId);

    /**
     * POLISH: Reply scroll — count messages newer than a given timestamp in this chat.
     * Used by navigateToOriginal() to calculate approximate adapter position.
     */
    @WorkerThread
    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND timestamp > :timestamp")
    int countMessagesAfterTimestamp(String chatId, long timestamp);

    /** v18 IMPROVEMENT 5: Failed media uploads — SyncWorker retry karega. */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE mediaLocalPath IS NOT NULL AND (mediaUrl IS NULL OR mediaUrl = '') AND status = 'pending' ORDER BY timestamp ASC")
    List<MessageEntity> getFailedMediaUploads();

    /** v18: Saare pending messages across all chats — SyncWorker + NotificationActionReceiver retry ke liye. */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE status = 'pending' ORDER BY timestamp ASC")
    List<MessageEntity> getAllPendingMessages();

    /** Read receipts: messages from partnerUid in chatId that are not yet 'read'. */
    @WorkerThread
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND senderId = :partnerUid AND (status IS NULL OR status != 'read') ORDER BY timestamp ASC")
    List<MessageEntity> getUnreadMessages(String chatId, String partnerUid);

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

    /** Edit message text — marks as edited with a timestamp. */
    @WorkerThread
    @Query("UPDATE messages SET text = :newText, edited = 1, editedAt = :editedAt WHERE id = :messageId")
    void updateText(String messageId, String newText, long editedAt);

    /** Edit message text AND append the pre-edit version to the history
     *  JSON blob in one write — used by MessageEditHistoryController so a
     *  message edited more than once keeps every prior version. */
    @WorkerThread
    @Query("UPDATE messages SET text = :newText, edited = 1, editedAt = :editedAt, editHistoryJson = :editHistoryJson WHERE id = :messageId")
    void updateTextWithHistory(String messageId, String newText, long editedAt, String editHistoryJson);

    /** Fetch just the history JSON blob — used to append the next version
     *  without needing the whole MessageEntity. */
    @WorkerThread
    @Query("SELECT editHistoryJson FROM messages WHERE id = :messageId")
    String getEditHistoryJson(String messageId);

    /** Poll: update the votes JSON blob for a poll message. */
    @WorkerThread
    @Query("UPDATE messages SET pollVotesJson = :votesJson WHERE id = :messageId")
    void updatePollVotes(String messageId, String votesJson);

    /** Poll: mark a poll as closed (no further votes accepted). */
    @WorkerThread
    @Query("UPDATE messages SET pollClosed = :closed WHERE id = :messageId")
    void updatePollClosed(String messageId, boolean closed);

    // ─────────────────────────────────────────────────────────────
    // PRUNING / CLEANUP
    // ─────────────────────────────────────────────────────────────

    /** Disappearing messages — delete all messages whose expiresAt has passed. */
    @WorkerThread
    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt > 0 AND expiresAt <= :nowMs")
    int deleteExpiredMessages(long nowMs);

    /** Prune old messages — keep last N per chat. Storage management. */
    @WorkerThread
    @Query("DELETE FROM messages WHERE chatId = :chatId AND timestamp < " +
           "(SELECT timestamp FROM messages WHERE chatId = :chatId " +
           "ORDER BY timestamp DESC LIMIT 1 OFFSET :keepCount)")
    void pruneOldMessages(String chatId, int keepCount);

    /** Auto-delete: delete all messages older than cutoffTimestamp (across all chats). */
    @WorkerThread
    @Query("DELETE FROM messages WHERE timestamp < :cutoffTimestamp AND deleted != 1")
    int deleteMessagesOlderThan(long cutoffTimestamp);

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
