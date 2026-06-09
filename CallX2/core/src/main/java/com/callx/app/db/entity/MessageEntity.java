package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/**
 * Room DB entity for cached messages.
 * Indexed on chatId + timestamp for fast query performance.
 *
 * DB VERSION BUMP REQUIRED: Add migration for new columns:
 *   deliveredAt, readAt, deliveredToJson, readByJson
 * See AppDatabase.MIGRATION_X_Y below.
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
    public String type;           // text | image | video | audio | file | status_seen
    public String mediaUrl;
    public String thumbnailUrl;
    public String fileName;
    public Long   fileSize;
    public Long   duration;
    public Long   timestamp;
    public String status;         // sent | delivered | read
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
     * v18 IMPROVEMENT 5: Offline media upload queue.
     * Image/video/file bhejne ka try offline karo — CloudinaryUploader fail hoga.
     * Local file URI yahan store karo. SyncWorker ke HEAVY pass mein retry karega:
     *   mediaLocalPath != null && mediaUrl == null → re-upload aur Firebase push.
     */
    public String mediaLocalPath;

    /**
     * v18 IMPROVEMENT 5: Media resource type — retry ke liye zaroori.
     * "image" | "video" | "raw" | "auto"
     */
    public String mediaResourceType;

    /**
     * Font style ID — TypingStyleManager.STYLE_* (0–19).
     * Sender ke selected typing style ko receiver pe bhi render karo.
     * Default 0 = Normal.
     */
    public int fontStyle;

    // ── FIX: Message Info timestamps ──────────────────────────────────────

    /**
     * FIX: Kab message receiver ke paas deliver hua (millis).
     * Room mein store hota hai taaki info screen offline bhi kaam kare.
     */
    public Long deliveredAt;

    /**
     * FIX: Kab receiver ne message padha (millis).
     * Set hota hai jab receiver ChatActivity foreground mein aata hai.
     */
    public Long readAt;

    /**
     * FIX: Group delivery map — JSON string (uid → deliveredAt millis).
     * Map<String,Long> Room mein directly store nahi hoti; Gson se serialize karo.
     * Use MessageInfoActivity.parseReadMap() to deserialise.
     *
     * Example JSON: {"uid1":1700000001000,"uid2":1700000002000}
     */
    public String deliveredToJson;

    /**
     * FIX: Group read map — JSON string (uid → readAt millis).
     * Same serialisation as deliveredToJson.
     *
     * Example JSON: {"uid1":1700000003000}
     */
    public String readByJson;

    public MessageEntity() {}
}
