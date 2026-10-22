package com.callx.app.db;

import androidx.annotation.Nullable;

import com.callx.app.db.entity.MessageEntity;

/**
 * Pure merge rules for ONE incoming (server / cache) message row against the row already in Room.
 * Extracted from MessageDao.mergeIncomingMessages so the rules can be unit-tested on the JVM.
 */
public final class MessageMergePolicy {
    private MessageMergePolicy() {}

    public static int statusRank(@Nullable String status) {
        if ("read".equals(status)) return 4;
        if ("delivered".equals(status)) return 3;
        if ("sent".equals(status)) return 2;
        if ("failed".equals(status)) return 1;
        return 0;
    }

    /**
     * @param local    the row currently in Room, or null if the message is new
     * @param incoming the server/cache copy (mutated and returned)
     * @return the row to write, or null when the incoming copy must be ignored
     */
    @Nullable
    public static MessageEntity merge(@Nullable MessageEntity local, MessageEntity incoming) {
        if (local == null) return incoming;
        // Local delete-for-me is a durable tombstone. A later cached snapshot must never make that message visible again.
        if (Boolean.TRUE.equals(local.deleted) && !Boolean.TRUE.equals(incoming.deleted)) return null;
        // Delete wins over edit/status updates.
        if (Boolean.TRUE.equals(incoming.deleted)) {
            incoming.text = "";
            incoming.mediaUrl = null;
            incoming.thumbnailUrl = null;
            incoming.fileName = null;
        }
        // Last-write-wins for edits, with local optimistic edits winning when the server callback is older.
        if (local.editedAt != null && incoming.editedAt != null && local.editedAt > incoming.editedAt) {
            incoming.text = local.text;
            incoming.edited = local.edited;
            incoming.editedAt = local.editedAt;
            incoming.editHistoryJson = local.editHistoryJson;
        }
        // Firebase can replay a stale "sent" value after a local delivered/read tick. Never regress the tick state.
        if (statusRank(local.status) > statusRank(incoming.status)) {
            incoming.status = local.status;
            incoming.deliveredAt = local.deliveredAt;
            incoming.readAt = local.readAt;
        }
        // Keep the local file available for offline media rendering.
        if (notEmpty(local.mediaLocalPath) && isEmpty(incoming.mediaLocalPath)) {
            incoming.mediaLocalPath = local.mediaLocalPath;
        }
        // Keep the encrypted wire copy until the durable send is acknowledged.
        if (notEmpty(local.wireText) && isEmpty(incoming.wireText)) {
            incoming.wireText = local.wireText;
        }
        // Columns the server copy never carries (set only on this device) must not be wiped by REPLACE.
        // Reactions / poll votes / status are server-driven and deliberately NOT null-filled, otherwise a
        // removed reaction would stay forever.
        if (incoming.starred == null && local.starred != null) incoming.starred = local.starred;
        if (incoming.pinned == null && local.pinned != null) incoming.pinned = local.pinned;
        if (incoming.viewOnceState == null && local.viewOnceState != null) incoming.viewOnceState = local.viewOnceState;
        if (isEmpty(incoming.editHistoryJson) && notEmpty(local.editHistoryJson)) {
            incoming.editHistoryJson = local.editHistoryJson;
        }
        if (isEmpty(incoming.thumbInlineData) && notEmpty(local.thumbInlineData)) {
            incoming.thumbInlineData = local.thumbInlineData;
        }
        incoming.retryCount = Math.max(local.retryCount, incoming.retryCount);
        incoming.nextRetryAt = Math.max(local.nextRetryAt, incoming.nextRetryAt);
        if (incoming.lastError == null) incoming.lastError = local.lastError;
        return incoming;
    }

    private static boolean isEmpty(@Nullable String s) { return s == null || s.isEmpty(); }
    private static boolean notEmpty(@Nullable String s) { return s != null && !s.isEmpty(); }
}
