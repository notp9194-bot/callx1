package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/**
 * Room DB entity — local cache of pending scheduled messages, mirroring
 * Firebase's scheduledMessages/{chatId}/{scheduleId} node. Lets the
 * "Scheduled" banner / manage list render instantly offline, the same way
 * MessageEntity caches the live messages table.
 *
 * A row here is deleted once ChatScheduledMessageWorker fires successfully
 * (the message then exists as a normal MessageEntity instead) or when the
 * user cancels it from the manage-scheduled list.
 */
@Entity(
    tableName = "scheduled_messages",
    indices = {
        @Index(value = {"chatId", "sendAt"})
    }
)
public class ScheduledMessageEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String chatId;
    public String senderId;
    public String senderName;
    public String partnerUid;
    public String text;
    public String type;
    public int    fontStyle;
    public long   sendAt;
    public long   createdAt;

    public ScheduledMessageEntity() {}
}
