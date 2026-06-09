package com.callx.app.models;

import java.util.Map;

/**
 * Represents a single chat message (1-on-1 or group).
 *
 * Fields added for new features are clearly annotated.
 * Firebase serialisation uses default no-arg constructor + public fields.
 */
public class Message {

    // ── Core ──────────────────────────────────────────────
    public String id;
    /** Alias for id — used by adapters and converters */
    public String messageId;
    public String senderId;
    public String senderName;
    public String senderPhoto;   // used by status_seen bubble for circular avatar
    public String text;
    /** text | image | video | audio | file */
    public String type;
    public String mediaUrl;
    public String thumbnailUrl;
    public String fileName;
    public Long   fileSize;
    public Long   duration;     // ms — audio/video
    public Long   timestamp;
    /** Legacy field kept for backward compatibility */
    public String imageUrl;

    // ── Feature 1: Read Receipts ──────────────────────────
    /** sent | delivered | read */
    public String status;

    // ── Feature 2: Reply / Quote ──────────────────────────
    public String replyToId;
    public String replyToText;
    public String replyToSenderName;
    public String replyToType;       // Added: type of original message
    public String replyToMediaUrl;   // Added: media URL of original (for thumbnail)

    // ── Feature 3: Emoji Reactions ────────────────────────
    /** Map of uid → emoji.  Firebase path: messages/{id}/reactions/{uid} */
    public Map<String, String> reactions;

    // ── Feature 4: Message Editing ────────────────────────
    public Boolean edited;
    public Long    editedAt;

    // ── Feature: Delivery Timestamps (for message info sheet) ────────────
    /** Unix ms when message was delivered to recipient device. Firebase path: messages/{id}/deliveredAt */
    public Long deliveredAt;
    /** Unix ms when recipient opened/read the message. Firebase path: messages/{id}/seenAt */
    public Long seenAt;

    // ── Feature 5: Delete for Everyone ───────────────────
    public Boolean deleted;

    // ── Feature 6: Forward ───────────────────────────────
    /** Display name of original sender if message was forwarded */
    public String forwardedFrom;

    // ── Feature 7: Starred ───────────────────────────────
    public Boolean starred;

    // ── Feature 8: Pinned ────────────────────────────────
    public Boolean pinned;


    // ── Feature 9: Reel Seen Bubble ──────────────────────────────
    /** Reel ID — set when type = "reel_seen". Used to open reel on tap. */
    public String reelId;
    /** Reel thumbnail URL — shown in the reel_seen bubble. */
    public String reelThumbUrl;

    // ── Feature 10: Status Seen Bubble ───────────────────────────
    /** Status owner UID — passed to StatusViewerActivity to load their statuses. */
    public String statusOwnerUid;
    /** Status owner name — passed to StatusViewerActivity. */
    public String statusOwnerName;
    /** Status thumbnail URL — shown in the status_seen bubble (image/video statuses). */
    public String statusThumbUrl;

    // ── Group flag ───────────────────────────────────────
    /** True if this message belongs to a group chat */
    public boolean isGroup;

    // ── Feature: Typing Font Style ────────────────────────────────────────
    /**
     * Font style ID used when this message was typed.
     * Maps to TypingStyleManager.STYLE_* constants (0–19).
     * 0 = Normal (default). Stored in Firebase & Room so receiver sees same style.
     */
    public int fontStyle;

    public Message() {}
}
