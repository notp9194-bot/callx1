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
        @Index(value = {"syncedAt"}),
        // PERF FIX: Speeds up getPendingMessages() and getAllPendingMessages()
        // queries that filter by status='pending'. Without this index SQLite
        // does a full table scan of every message in the DB on every retry call.
        @Index(value = {"chatId", "status"}),
        @Index(value = {"status"})
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
    /** JSON array string of prior text versions, oldest first. See
     *  EditHistoryJsonUtil for the List<Map<String,Object>> ↔ JSON
     *  conversion. Null for never-edited messages. */
    public String editHistoryJson;
    public Boolean deleted;
    public String forwardedFrom;
    public Boolean starred;
    public Boolean pinned;
    public Boolean isGroup;

    /** Map<uid, emoji> serialized as JSON object string — see
     *  ReactionJsonUtil / ChatReactionController. Null = no reactions yet. */
    public String reactionsJson;


    /** Last delta sync timestamp — used for incremental sync. */
    public long syncedAt;


    /** Reel ID — for reel_seen bubble; used to open reel on tap. */
    public String reelId;
    /** Reel thumbnail URL — shown in reel_seen bubble. */
    public String reelThumbUrl;
    /** UID of the reel owner — the bubble renders ONLY for this user. */
    public String reelOwnerUid;

    // ── Reel Share Card (type = "reel_share") ─────────────────────────────
    public String reelShareUrl;
    public String reelShareThumb;
    public String reelShareCaption;
    public String reelShareUsername;
    public String reelShareOwnerPhoto;

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


    // ── Feature 13: View Once / Secret Message ───────────────────────────
    /**
     * True = this message is view-once.
     * Indexed alongside messageId for fast lookup in softDelete / state queries.
     */
    public Boolean viewOnce;

    /**
     * State: "sent" | "opened" | "deleted"
     * SENT      → message exists, badge shows "View Once"
     * OPENED    → receiver tapped; hard-delete pipeline triggered
     * DELETED   → wiped from Firebase; local tombstone only
     */
    public String viewOnceState;

    /**
     * Epoch ms when receiver first opened the message.
     * Populated from Firebase ServerValue.TIMESTAMP for clock accuracy.
     */
    public Long openedAt;

    /**
     * Feature 2: Unix timestamp (ms) when this view-once message expires automatically.
     * Null / 0 means no expiry.
     */
    public Long viewOnceExpiresAt;

    public MessageEntity() {}
}
