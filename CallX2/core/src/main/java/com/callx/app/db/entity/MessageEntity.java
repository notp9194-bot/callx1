package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/**
 * Room DB entity for cached messages.
 *
 * v20 additions:
 *   • disappearAt        — unix ms when this message auto-deletes (0/null = never)
 *   • groupReadBy        — comma-separated UIDs who read this message (group read receipts)
 *   • locationLat/Lng   — coordinates for location message type
 *   • liveLocationExpiry — when live location sharing expires
 */
@Entity(
    tableName = "messages",
    indices = {
        @Index(value = {"chatId", "timestamp"}),
        @Index(value = {"chatId", "starred"}),
        @Index(value = {"syncedAt"}),
        @Index(value = {"disappearAt"}),        // v20: fast query for expired messages
        @Index(value = {"chatId", "text"})       // v20: search performance
    }
)
public class MessageEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String chatId;
    public String senderId;
    public String senderName;
    public String senderPhoto;
    public String text;
    /** text | image | video | audio | file | location | live_location | status_seen | reel_seen */
    public String type;
    public String mediaUrl;
    public String thumbnailUrl;
    public String fileName;
    public Long   fileSize;
    public Long   duration;
    public Long   timestamp;
    /** sent | delivered | read | pending */
    public String status;
    public String replyToId;
    public String replyToText;
    public String replyToSenderName;
    public String replyToType;
    public String replyToMediaUrl;
    public Boolean edited;
    public Long   editedAt;
    public Boolean deleted;
    public String forwardedFrom;
    public Boolean starred;
    public Boolean pinned;
    public Boolean isGroup;
    public long syncedAt;

    public String reelId;
    public String reelThumbUrl;
    public String statusOwnerUid;
    public String statusOwnerName;
    public String statusThumbUrl;

    public String mediaLocalPath;
    public String mediaResourceType;

    /** Font style ID — TypingStyleManager.STYLE_* (0–19). Default 0 = Normal. */
    public int fontStyle;

    // ── v20 NEW: Disappearing Messages ────────────────────────────────────
    /**
     * Unix ms when this message auto-deletes.
     * null / 0 = never (disappearing not enabled for this chat).
     * DisappearingMessageWorker queries: WHERE disappearAt > 0 AND disappearAt <= now.
     */
    public Long disappearAt;

    // ── v20 NEW: Group Read Receipts ──────────────────────────────────────
    /**
     * Comma-separated UIDs of group members who have read this message.
     * e.g. "uid1,uid2,uid3"
     * Firebase path: groups/{groupId}/messages/{msgId}/readBy/{uid} = serverTimestamp
     * UI: "Read by 3 of 7" in message info screen.
     */
    public String groupReadBy;

    // ── v20 NEW: Location Message ─────────────────────────────────────────
    /** For type = "location" / "live_location". Stored redundantly for fast map rendering. */
    public Double locationLat;
    public Double locationLng;

    /** For type = "live_location": unix ms when sharing stops. */
    public Long liveLocationExpiry;

    public MessageEntity() {}
}
