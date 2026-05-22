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
    /**
     * Message delivery status.
     * Values: "pending" | "sent" | "delivered" | "read" | "failed"
     *
     * "pending"   — Message queued locally, not yet sent to Firebase.
     * "sent"      — Successfully written to Firebase (single grey tick ✓).
     * "delivered" — Other device received it (double grey tick ✓✓).
     * "read"      — Other party opened the chat and saw it (double blue tick ✓✓).
     * "failed"    — Permanent send failure (red error icon).
     *
     * For group chats, this top-level status reflects the worst-case member state:
     *   pending → sent → delivered (any member) → read (ALL members).
     * Per-member details are in readBy / deliveredTo maps.
     */
    public String status;

    /**
     * Group read receipts — uid → timestamp (epoch ms) when each member READ the message.
     * Firebase path: chats/{chatId}/messages/{msgId}/readBy/{uid} = timestamp
     * Only meaningful when isGroup = true.
     */
    public Map<String, Long> readBy;

    /**
     * Group delivery receipts — uid → epoch ms when each member's device RECEIVED the message.
     * Firebase path: chats/{chatId}/messages/{msgId}/deliveredTo/{uid} = timestamp
     * Only meaningful when isGroup = true.
     */
    public Map<String, Long> deliveredTo;

    /**
     * For 1:1: timestamp when the other party's device received (delivered) this message.
     * For group: timestamp when the FIRST member received it.
     */
    public Long deliveredAt;

    /**
     * For 1:1: timestamp when the other party READ this message.
     * For group: timestamp when the LAST member read it (i.e., all-read time).
     */
    public Long readAt;

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

    public Message() {}

    /**
     * Returns the effective display status for tick rendering.
     * Never returns null — defaults to "sent".
     */
    public String getEffectiveStatus() {
        if (status == null) return "sent";
        return status;
    }

    /** For group messages: how many members have read this message. */
    public int getReadCount() {
        return readBy == null ? 0 : readBy.size();
    }

    /** For group messages: how many members have received this message. */
    public int getDeliveredCount() {
        return deliveredTo == null ? 0 : deliveredTo.size();
    }
}
