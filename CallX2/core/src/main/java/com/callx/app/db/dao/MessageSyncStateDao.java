package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.callx.app.db.entity.MessageSyncStateEntity;

@Dao
public interface MessageSyncStateDao {

    @WorkerThread
    @Query("SELECT * FROM message_sync_state WHERE chatId = :chatId LIMIT 1")
    MessageSyncStateEntity get(String chatId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(MessageSyncStateEntity state);

    /**
     * Advance monotonically. This protects the cursor if a listener callback
     * and a background sync complete close together.
     *
     * GAP FIX (#1): once a chat has a seq-bearing cursor (cursorSeq != null),
     * the comparison switches to pure seq order — a real server-issued total
     * order, so no tiebreak needed. A chat that hasn't seen a seq-bearing
     * message yet keeps advancing on (timestamp, id) as before; the moment
     * ANY message with a seq is synced, this chat's cursor picks up cursorSeq
     * and every advance call after that compares by seq instead. Two calls
     * racing where one has a seq and one doesn't: the seq call always wins
     * regardless of arrival order, since a real server seq is strictly more
     * trustworthy than a client timestamp.
     */
    @WorkerThread
    @Transaction
    default void advance(String chatId, long timestamp, String messageId, Long seq) {
        if (chatId == null || chatId.isEmpty() || messageId == null) return;

        MessageSyncStateEntity current = get(chatId);
        if (current != null) {
            if (current.cursorSeq != null) {
                // Cursor is already seq-anchored — only another (larger or
                // equal, to allow re-advancing to the same seq idempotently)
                // seq can move it forward. A seq-less call here is from a
                // message that hasn't been assigned one yet (or predates the
                // migration) and must never regress an already-authoritative
                // cursor back to timestamp comparison.
                if (seq == null || seq <= current.cursorSeq) return;
            } else if (seq == null) {
                // Neither side has a seq yet — original (timestamp, id) keyset compare.
                String currentId = current.cursorMessageId != null
                        ? current.cursorMessageId : "";
                if (timestamp < current.cursorTimestamp
                        || (timestamp == current.cursorTimestamp
                        && messageId.compareTo(currentId) <= 0)) {
                    return;
                }
            }
            // else: current has no seq yet but this call does — always
            // accept, since gaining a real server cursor is strictly an
            // upgrade over a timestamp-only one regardless of timestamp order.
        }
        upsert(new MessageSyncStateEntity(chatId, timestamp, messageId, seq));
    }

    /** Back-compat overload for callers that don't have a seq yet. */
    @WorkerThread
    @Transaction
    default void advance(String chatId, long timestamp, String messageId) {
        advance(chatId, timestamp, messageId, null);
    }
}