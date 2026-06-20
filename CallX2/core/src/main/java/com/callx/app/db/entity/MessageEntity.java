package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/**
 * Room DB entity for cached messages.
 * Indexed on chatId + timestamp for fast query performance.
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

    /**
     * Disappearing messages — epoch ms at which this message auto-deletes.
     * 0 / null = no expiry. Set by sender at push time.
     */
    public Long expiresAt;

    // ── Feature 12: Polls ─────────────────────────────────────────
    /** Set when type = "poll". The poll question text. */
    public String pollQuestion;
    /** Poll options serialized as JSON array string, e.g. ["Yes","No"]. */
    public String pollOptionsJson;
    /** Poll votes serialized as JSON object string, e.g. {"uid1":[0],"uid2":[0,2]}
     *  — each voter maps to a list of ticked option indices (see PollJsonUtil). */
    public String pollVotesJson;
    public Boolean pollAnonymous;
    public Boolean pollClosed;
    /** Advanced polls: true = voters may tick multiple options (checkbox style). */
    public Boolean pollMultiChoice;

    public MessageEntity() {}
}
