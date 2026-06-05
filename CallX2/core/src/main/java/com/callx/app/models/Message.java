package com.callx.app.models;

import java.util.Map;

/**
 * Represents a single chat message (1-on-1 or group).
 *
 * Firebase serialisation uses default no-arg constructor + public fields.
 *
 * v20 additions:
 *   • disappearAt        — unix ms when message auto-deletes (disappearing messages)
 *   • groupReadBy        — Map<uid, readTimestamp> for group read receipts
 *   • locationLat/Lng   — for location message type
 *   • liveLocationExpiry — when live location sharing expires
 */
public class Message {

    // ── Core ──────────────────────────────────────────────
    public String id;
    /** Alias for id — used by adapters and converters */
    public String messageId;
    public String senderId;
    public String senderName;
    public String senderPhoto;
    /**
     * text | image | video | audio | file
     * | location | live_location
     * | status_seen | reel_seen
     */
    public String type;
    public String mediaUrl;
    public String thumbnailUrl;
    public String fileName;
    public Long   fileSize;
    public Long   duration;     // ms — audio/video
    public Long   timestamp;
    /** Legacy field kept for backward compatibility */
    public String imageUrl;

    // ── Text content ──────────────────────────────────────
    /**
     * For type=text/audio: message text.
     * For type=location: "location|lat|lng|address" (see LocationMessageHelper).
     * For type=live_location: "location|lat|lng|address".
     */
    public String text;

    // ── Feature 1: Read Receipts ──────────────────────────
    /** sent | delivered | read */
    public String status;

    // ── Feature 2: Reply / Quote ──────────────────────────
    public String replyToId;
    public String replyToText;
    public String replyToSenderName;
    public String replyToType;
    public String replyToMediaUrl;

    // ── Feature 3: Emoji Reactions ────────────────────────
    /** Map of uid → emoji. Firebase path: messages/{id}/reactions/{uid} */
    public Map<String, String> reactions;

    // ── Feature 4: Message Editing ────────────────────────
    public Boolean edited;
    public Long    editedAt;

    // ── Feature 5: Delete for Everyone ───────────────────
    public Boolean deleted;

    // ── Feature 6: Forward ───────────────────────────────
    public String forwardedFrom;

    // ── Feature 7: Starred ───────────────────────────────
    public Boolean starred;

    // ── Feature 8: Pinned ────────────────────────────────
    public Boolean pinned;

    // ── Feature 9: Reel Seen Bubble ──────────────────────
    public String reelId;
    public String reelThumbUrl;

    // ── Feature 10: Status Seen Bubble ───────────────────
    public String statusOwnerUid;
    public String statusOwnerName;
    public String statusThumbUrl;

    // ── Group flag ───────────────────────────────────────
    public boolean isGroup;

    // ── Font Style ───────────────────────────────────────
    public int fontStyle;

    // ── v20 NEW: Disappearing Messages ───────────────────
    /**
     * Unix ms when this message auto-deletes on all devices.
     * null / 0 = never.
     * Set by sender: disappearAt = timestamp + chat.disappearTimer.
     * Firebase path: messages/{id}/disappearAt
     */
    public Long disappearAt;

    // ── v20 NEW: Group Read Receipts ─────────────────────
    /**
     * Map of uid → readTimestamp for group messages.
     * Firebase path: groups/{groupId}/messages/{msgId}/readBy/{uid} = serverTimestamp
     * UI: "Read by N of M members" in long-press message info.
     * Note: For 1:1 chats, use the 'status' field instead.
     */
    public Map<String, Long> groupReadBy;

    // ── v20 NEW: Location Message ─────────────────────────
    /**
     * For type = "location" or "live_location".
     * Coordinates stored separately for fast map SDK rendering.
     * Address is also embedded in text field as: "location|lat|lng|address".
     */
    public Double locationLat;
    public Double locationLng;

    /**
     * For type = "live_location": unix ms when sharing stops.
     * After expiry, receiver sees "Stopped sharing location" state.
     */
    public Long liveLocationExpiry;

    public Message() {}
}
