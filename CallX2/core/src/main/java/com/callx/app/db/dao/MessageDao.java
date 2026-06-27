package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
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

    /**
     * PERF: used by LastMessagesCache priming — fetches just the most recent
     * `limit` rows but returns them ASC (oldest→newest), matching the order
     * Room/Paging/RecyclerView already use. Inner query does DESC+LIMIT
     * (cheap, uses the chatId+timestamp index), outer query just re-sorts
     * those few rows ASC — no full-table scan, no large offset math.
     */
    @WorkerThread
    @Query("SELECT * FROM (SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    List<MessageEntity> getLastMessagesAsc(String chatId, int limit);

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

    // ── Feature 13: View Once ─────────────────────────────────────────────

    /** Update view-once state for a single message (e.g., "opened", "deleted"). */
    @Query("UPDATE messages SET viewOnceState = :state WHERE id = :messageId")
    void updateViewOnceState(String messageId, String state);

    /** Update openedAt timestamp (called after receiver opens). */
    @Query("UPDATE messages SET openedAt = :openedAt WHERE id = :messageId")
    void updateOpenedAt(String messageId, long openedAt);

    /** Fetch all view-once messages in OPENED state that haven't been hard-deleted
     *  locally yet — used by SyncWorker offline flush. */
    @Query("SELECT * FROM messages WHERE viewOnce = 1 AND viewOnceState = 'opened' AND (deleted IS NULL OR deleted = 0)")
    List<MessageEntity> getPendingViewOnceDeletes();

    /**
     * Fetch view-once messages in STATE_SENT whose sender-set expiry time has elapsed.
     * Used by scheduleExpiryCleanup() every 30s to fire expireViewOnce() on elapsed messages.
     * Only "sent" state — we skip already opened/deleted/revoked/expired ones.
     */
    @Query("SELECT * FROM messages WHERE viewOnce = 1 AND viewOnceState = 'sent' " +
           "AND viewOnceExpiresAt IS NOT NULL AND viewOnceExpiresAt > 0 " +
           "AND viewOnceExpiresAt <= :nowMs AND (deleted IS NULL OR deleted = 0)")
    List<MessageEntity> getElapsedViewOnceMessages(long nowMs);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMessages(List<MessageEntity> messages);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMessage(MessageEntity message);

    @WorkerThread
    @Update
    void updateMessage(MessageEntity message);

    @WorkerThread
    @Query("UPDATE messages SET deleted = 1, text = '', mediaUrl = NULL, thumbnailUrl = NULL, fileName = NULL, viewOnceState = 'deleted' WHERE id = :messageId")
    void softDelete(String messageId);

    /**
     * PERF FIX (chat-open jump/flicker): bulk soft-delete for messages
     * removed from Firebase while they were buffered in a write-coalescing
     * window — see ChatActivity/GroupChatActivity#flushPendingRoomWrites().
     * One UPDATE for N ids = one PagingSource invalidation, not N.
     */
    @WorkerThread
    @Query("UPDATE messages SET deleted = 1, text = '', mediaUrl = NULL, thumbnailUrl = NULL, fileName = NULL, viewOnceState = 'deleted' WHERE id IN (:ids)")
    void softDeleteAll(List<String> ids);

    @WorkerThread
    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    void updateStatus(String messageId, String status);

    /**
     * PERF FIX (chat-open jump/flicker): bulk "mark read" for messages
     * buffered during the write-coalescing window, instead of one
     * UPDATE per message (which was triggering one PagingSource
     * invalidation — and therefore one extra RecyclerView re-render —
     * per historical message on every chat open).
     */
    @WorkerThread
    @Query("UPDATE messages SET status = 'read' WHERE id IN (:ids) AND (status IS NULL OR status != 'read')")
    void markReadBulk(List<String> ids);

    /**
     * PERF FIX (chat-open jump/flicker): applies a whole burst of buffered
     * Firebase events — new/changed messages, removed messages, and
     * read-receipt updates — inside a SINGLE Room transaction.
     *
     * Why this matters: previously every individual Firebase onChildAdded()
     * fired its own insertMessage() + updateStatus() call. When a chat was
     * opened, Firebase replays the last N messages as N separate
     * onChildAdded() events in a tight burst; each one was its own Room
     * write, each write fired its own PagingSource invalidation, and each
     * invalidation drove its own submitData() → diff → layout pass. The
     * user saw this as the chat screen "jumping" up/down repeatedly while
     * messages popped in one at a time, plus a multi-second delay before
     * the list settled.
     *
     * Fix: ChatActivity/GroupChatActivity now buffer incoming Firebase
     * events for a short debounce window and call this method ONCE with
     * everything that arrived in that window. One transaction → Room's
     * InvalidationTracker fires once → Paging3 re-queries once →
     * PagingDataAdapter does exactly one diff + one single-pass render.
     */
    @WorkerThread
    @Transaction
    default void applyBufferedChanges(List<MessageEntity> upserts, List<String> removedIds, List<String> readIds) {
        if (upserts != null && !upserts.isEmpty()) insertMessages(upserts);
        if (removedIds != null && !removedIds.isEmpty()) softDeleteAll(removedIds);
        if (readIds != null && !readIds.isEmpty()) markReadBulk(readIds);
    }

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

    /** Reactions: overwrite the reactions JSON blob for a message — see
     *  ReactionJsonUtil / ChatReactionController. */
    @WorkerThread
    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE id = :messageId")
    void updateReactions(String messageId, String reactionsJson);

    /** Fetch just the reactions JSON blob — used to read-modify-write a
     *  single uid's reaction without needing the whole MessageEntity. */
    @WorkerThread
    @Query("SELECT reactionsJson FROM messages WHERE id = :messageId")
    String getReactionsJson(String messageId);

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
