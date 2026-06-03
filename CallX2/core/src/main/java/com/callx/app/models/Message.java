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
    public String senderPhoto;
    public String text;
    /** text | image | video | audio | file | contact | location */
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
    /** pending | sent | delivered | read | failed */
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

    // ── Feature 9: Reel Seen Bubble ──────────────────────────────
    public String reelId;
    public String reelThumbUrl;

    // ── Feature 10: Status Seen Bubble ───────────────────────────
    public String statusOwnerUid;
    public String statusOwnerName;
    public String statusThumbUrl;

    // ── Group flag ───────────────────────────────────────
    public boolean isGroup;

    // ── Feature: Typing Font Style ────────────────────────────────────────
    public int fontStyle;

    // ── Feature: Link Preview ────────────────────────────────────────────
    /** Populated when message text contains a URL */
    public String linkPreviewUrl;
    public String linkPreviewTitle;
    public String linkPreviewDescription;
    public String linkPreviewImageUrl;
    public String linkPreviewSiteName;

    // ── Feature: Contact Share ───────────────────────────────────────────
    public String contactName;
    public String contactPhone;
    public String contactPhotoUrl;

    // ── Feature: Location Share ──────────────────────────────────────────
    public Double locationLat;
    public Double locationLng;
    public String locationName;

    // ── Feature: Disappearing Messages ──────────────────────────────────
    /** Unix ms when message should disappear. 0 = no expiry */
    public Long expiresAt;

    // ── Upload state (local only, not stored in Firebase) ────────────────
    /** 0–100 upload progress. -1 = failed. Not stored in Firebase. */
    public transient int uploadProgress = -1;

    public Message() {}
}
