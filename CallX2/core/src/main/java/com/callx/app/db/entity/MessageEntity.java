package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * Room DB entity for cached messages.
 * Indexed on chatId + timestamp for fast query performance.
 *
 * DB version history:
 *   v1-v3 — original columns
 *   v4    — mediaLocalPath, mediaResourceType (offline upload queue)
 *   v5    — readByJson, deliveredToJson, deliveredAt, readAt (group read receipts)
 */
@Entity(
    tableName = "messages",
    indices = {
        @Index(value = {"chatId", "timestamp"}),
        @Index(value = {"chatId", "starred"}),
        @Index(value = {"syncedAt"})
    }
)
public class MessageEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String chatId;
    public String senderId;
    public String senderName;
    public String senderPhoto;    // avatar URL — used by status_seen bubble
    public String text;
    public String type;           // text | image | video | audio | file | status_seen | reel_seen
    public String mediaUrl;
    public String thumbnailUrl;
    public String fileName;
    public Long   fileSize;
    public Long   duration;
    public Long   timestamp;

    /**
     * Message delivery status.
     * "pending" | "sent" | "delivered" | "read" | "failed"
     */
    public String status;

    public String replyToId;
    public String replyToText;
    public String replyToSenderName;
    public String replyToType;       // type of original message
    public String replyToMediaUrl;   // media URL of original for thumbnail
    public Boolean edited;
    public Long   editedAt;
    public Boolean deleted;
    public String forwardedFrom;
    public Boolean starred;
    public Boolean pinned;
    public Boolean isGroup;

    /** Last delta sync timestamp — used for incremental sync. */
    public long syncedAt;

    /** Reel ID — for reel_seen bubble; used to open reel on tap. */
    public String reelId;
    /** Reel thumbnail URL — shown in reel_seen bubble. */
    public String reelThumbUrl;

    /**
     * v18: Offline media upload queue.
     * Local file URI stored here. SyncWorker retries upload when online:
     *   mediaLocalPath != null && mediaUrl == null → re-upload + Firebase push.
     */
    public String mediaLocalPath;

    /**
     * v18: Media resource type for retry — "image" | "video" | "raw" | "auto"
     */
    public String mediaResourceType;

    /**
     * v5 GROUP READ RECEIPTS: JSON-serialised Map<String,Long> uid→readTimestamp.
     * Use Gson: new Gson().toJson(message.readBy) to store,
     *           new Gson().fromJson(json, type) to restore.
     * Room doesn't support Map natively — stored as TEXT.
     */
    public String readByJson;

    /**
     * v5 GROUP DELIVERY RECEIPTS: JSON-serialised Map<String,Long> uid→deliveredTimestamp.
     */
    public String deliveredToJson;

    /**
     * v5: Epoch ms when the message was delivered (1:1: other party; group: first member).
     */
    public Long deliveredAt;

    /**
     * v5: Epoch ms when the message was read (1:1: other party; group: last member = all-read).
     */
    public Long readAt;

    public MessageEntity() {}
}
