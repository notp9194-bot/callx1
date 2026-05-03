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

    // ── Feature 3: Emoji Reactions ────────────────────────
    /** Map of uid → emoji.  Firebase path: messages/{id}/reactions/{uid} */
    public Map<String, String> reactions;

    // ── Feature 4: Message Editing ────────────────────────
    public Boolean edited;
    public Long    editedAt;

    // ── Feature 5: Delete for Everyone ───────────────────
    public Boolean deleted;

    // ── Feature 6: Forward ───────────────────────────────
    /** Display name of original sender if message was forwarded */
    public String forwardedFrom;

    // ── Feature 7: Starred ───────────────────────────────
    public Boolean starred;

    // ── Feature 8: Pinned ────────────────────────────────
    public Boolean pinned;

    public Message() {}
}
