package com.callx.app.models;

import java.util.List;
import java.util.Map;

/**
 * Represents a single chat message (1-on-1 or group).
 * All 15 new feature fields are annotated below.
 */
public class Message {

    // ── Core ──────────────────────────────────────────────────────────────
    public String id;
    public String senderId;
    public String senderName;
    public String text;
    /**
     * text | image | video | audio | file |
     * location | contact | gif | sticker | poll | link_preview
     */
    public String type;
    public String mediaUrl;
    public String thumbnailUrl;
    public String fileName;
    public Long   fileSize;
    public Long   duration;
    public Long   timestamp;
    public String imageUrl; // legacy

    // ── Feature 1 (existing): Read Receipts ──────────────────────────────
    /** sent | delivered | read */
    public String status;

    // ── Feature 2 (existing): Reply / Quote ──────────────────────────────
    public String replyToId;
    public String replyToText;
    public String replyToSenderName;

    // ── Feature 3 (existing): Emoji Reactions ────────────────────────────
    /**
     * Feature 13 (NEW): Multiple reactions per user.
     * Map structure: uid → List<String> (multiple emojis per user)
     * Firebase path: messages/{id}/reactions/{uid}/{index} = emoji
     */
    public Map<String, Object> reactions; // uid → String or List<String>

    // ── Feature 4 (existing): Message Editing ────────────────────────────
    public Boolean edited;
    public Long    editedAt;

    // ── Feature 5 (existing): Delete for Everyone ────────────────────────
    public Boolean deleted;

    // ── Feature 6 (existing): Forward ────────────────────────────────────
    public String forwardedFrom;

    // ── Feature 7 (existing): Starred ────────────────────────────────────
    public Boolean starred;

    // ── Feature 8 (existing): Pinned ─────────────────────────────────────
    public Boolean pinned;

    // ── NEW Feature 1: Location Sharing ──────────────────────────────────
    public Double  locationLat;
    public Double  locationLng;
    public String  locationAddress; // human-readable reverse geocoded address
    public String  locationMapUrl;  // static map thumbnail URL

    // ── NEW Feature 2: Contact / vCard Sharing ────────────────────────────
    public String  contactName;
    public String  contactPhone;
    public String  contactEmail;
    public String  contactVCard;    // raw vCard string for import

    // ── NEW Feature 3: GIF / Sticker ─────────────────────────────────────
    public String  gifUrl;
    public String  stickerPackId;
    public String  stickerId;

    // ── NEW Feature 4: Link Preview ──────────────────────────────────────
    public String  linkUrl;
    public String  linkTitle;
    public String  linkDescription;
    public String  linkImageUrl;
    public String  linkSiteName;

    // ── NEW Feature 5: Poll ──────────────────────────────────────────────
    public String       pollQuestion;
    public List<String> pollOptions;
    /** uid → option index (0-based) — single choice */
    public Map<String, Integer> pollVotes;
    public Boolean pollMultiChoice;
    public Long    pollExpiresAt; // epoch ms; null = no expiry

    // ── NEW Feature 7: @Mention ──────────────────────────────────────────
    /** List of UIDs mentioned in this message with @name */
    public List<String> mentionedUids;

    // ── NEW Feature 8: Disappearing Messages ─────────────────────────────
    /** Epoch ms at which this message should be auto-deleted; null = never */
    public Long    expiresAt;

    // ── NEW Feature 9: Broadcast ─────────────────────────────────────────
    /** If sent via broadcast list, stores the broadcast list ID */
    public String  broadcastListId;

    // ── NEW Feature 10: Seen By (Group) ──────────────────────────────────
    /**
     * uid → epochMs — populated when each group member reads the message.
     * Firebase path: groupMessages/{groupId}/{msgId}/seenBy/{uid} = timestamp
     */
    public Map<String, Long> seenBy;

    // ── NEW Feature 12: Voice Message Transcription ───────────────────────
    public String  transcript;        // transcribed text
    public Boolean transcriptLoading; // transient flag (not stored in Firebase)

    // ── NEW Feature 14: Chat Wallpaper (per-message metadata) ────────────
    // Wallpaper is per-chat, stored separately — no message field needed.

    // ── NEW Feature 15: E2E Encryption metadata ───────────────────────────
    /**
     * If E2E is active the actual text/mediaUrl are encrypted (AES-256-GCM).
     * iv is the base64-encoded initialization vector.
     */
    public Boolean e2eEncrypted;
    public String  iv;

    public Message() {}
}
