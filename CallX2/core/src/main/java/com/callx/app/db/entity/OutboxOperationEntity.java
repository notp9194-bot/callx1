package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Durable write-ahead queue for chat mutations.
 *
 * The messages table is the optimistic UI store; this table is the durable
 * intent store. Keeping the intent separate means a process kill cannot lose
 * an edit/delete/media upload merely because the optimistic row already
 * exists. Operation ids are deterministic, so re-enqueuing the same intent is
 * idempotent and never creates duplicate sends.
 */
@Entity(
        tableName = "outbox_operations",
        indices = {
                @Index(value = {"state", "nextAttemptAt"}),
                @Index(value = {"chatId", "messageId"}),
                @Index(value = {"messageId", "operationType"})
        }
)
public class OutboxOperationEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String chatId;
    public String messageId;
    /** send | media_upload | edit | delete_everyone */
    public String operationType;
    /** JSON for edits; null for send/media/delete operations. */
    public String payloadJson;
    public String mediaLocalPath;
    public String mediaResourceType;
    public String mediaFileName;
    public Boolean isGroup;

    /** pending | processing | failed */
    public String state = "pending";
    public int attemptCount;
    public long nextAttemptAt;
    public long createdAt;
    public long updatedAt;
    public String lastError;

    public OutboxOperationEntity() {}
}