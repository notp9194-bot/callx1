package com.callx.app.db.dao;

import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.annotation.WorkerThread;
import com.callx.app.db.entity.MessageEntity;
import java.util.List;

/**
 * MessageDao — WhatsApp-level bulk operations
 *
 * KEY UPGRADE: applyBufferedChanges() — single @Transaction for all buffered
 * Firebase events. 30 onChildAdded() callbacks → 1 Room write → 1 PagingSource
 * invalidation → 1 DiffUtil pass → 0 visible jump/flicker.
 */
@Dao
public abstract class MessageDao {

    // ── Insert / upsert ──────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @WorkerThread
    public abstract void insertMessage(MessageEntity message);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @WorkerThread
    public abstract void insertMessages(List<MessageEntity> messages);

    // ── Paging ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND deleted = 0 ORDER BY timestamp ASC")
    public abstract PagingSource<Integer, MessageEntity> getPagedMessages(String chatId);

    @Query("SELECT * FROM messages WHERE groupId = :groupId AND deleted = 0 ORDER BY timestamp ASC")
    public abstract PagingSource<Integer, MessageEntity> getPagedGroupMessages(String groupId);

    // ── Bulk status / soft-delete ─────────────────────────────────────────────

    /** Bulk soft-delete: single UPDATE for N message IDs. */
    @Query("UPDATE messages SET deleted = 1 WHERE id IN (:ids)")
    @WorkerThread
    public abstract void softDeleteAll(List<String> ids);

    /** Bulk read-receipt: single UPDATE for N message IDs (status column only). */
    @Query("UPDATE messages SET status = 'read' WHERE id IN (:ids)")
    @WorkerThread
    public abstract void markReadBulk(List<String> ids);

    @Query("UPDATE messages SET deleted = 1 WHERE id = :msgId")
    @WorkerThread
    public abstract void markDeleted(String msgId);

    @Query("UPDATE messages SET status = :status WHERE id = :msgId")
    @WorkerThread
    public abstract void updateStatus(String msgId, String status);

    // ── THE KEY METHOD — one @Transaction for all buffered Firebase events ────

    /**
     * Atomically applies all buffered Firebase changes in ONE Room transaction.
     *
     * OLD: 30 onChildAdded → 30 insertMessage() → 30 PagingSource invalidations
     *      → 30 DiffUtil passes → visible jump + 3-4s settling delay.
     *
     * NEW: 30 onChildAdded → buffer 80ms → 1 call here → 1 transaction
     *      → 1 PagingSource invalidation → 1 DiffUtil → zero jump.
     */
    @Transaction
    @WorkerThread
    public void applyBufferedChanges(
            List<MessageEntity> upserts,
            List<String> removedIds,
            List<String> readIds) {
        if (upserts   != null && !upserts.isEmpty())   insertMessages(upserts);
        if (removedIds != null && !removedIds.isEmpty()) softDeleteAll(removedIds);
        if (readIds   != null && !readIds.isEmpty())   markReadBulk(readIds);
    }

    // ── Offline / SyncWorker ─────────────────────────────────────────────────

    @Query("SELECT * FROM messages WHERE mediaLocalPath IS NOT NULL AND (mediaUrl IS NULL OR mediaUrl = '')")
    @WorkerThread
    public abstract List<MessageEntity> getPendingMediaUploads();

    @Query("SELECT * FROM messages WHERE status = 'pending' ORDER BY timestamp ASC")
    @WorkerThread
    public abstract List<MessageEntity> getAllPendingMessages();

    @Query("SELECT * FROM messages WHERE id = :msgId LIMIT 1")
    @WorkerThread
    public abstract MessageEntity getMessageById(String msgId);

    @Query("SELECT id FROM messages WHERE chatId = :chatId AND senderId = :senderUid AND (status IS NULL OR status != 'read') AND deleted = 0")
    @WorkerThread
    public abstract List<String> getUnreadMessageIds(String chatId, String senderUid);

    @Query("SELECT id FROM messages WHERE groupId = :groupId AND senderId != :myUid AND (status IS NULL OR status != 'read') AND deleted = 0")
    @WorkerThread
    public abstract List<String> getUnreadGroupMessageIds(String groupId, String myUid);

    // ── Counts ───────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND deleted = 0")
    @WorkerThread
    public abstract int getMessageCount(String chatId);

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId AND senderId = :senderUid AND (status IS NULL OR status != 'read') AND deleted = 0")
    @WorkerThread
    public abstract int getUnreadCount(String chatId, String senderUid);

    // ── Cleanup ──────────────────────────────────────────────────────────────

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    @WorkerThread
    public abstract void deleteAllForChat(String chatId);

    @Query("DELETE FROM messages WHERE groupId = :groupId")
    @WorkerThread
    public abstract void deleteAllForGroup(String groupId);

    @Query("DELETE FROM messages WHERE deleted = 1 AND timestamp < :olderThanMs")
    @WorkerThread
    public abstract void pruneDeletedOlderThan(long olderThanMs);
}
