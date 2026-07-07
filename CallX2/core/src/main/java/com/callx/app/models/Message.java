package com.callx.app.models;

import java.util.Map;
import java.util.Objects;

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
    // Pixel dimensions of the original image/video-thumbnail, captured once
    // at send time (see ChatMediaController's inJustDecodeBounds read) and
    // carried in the message payload itself — lets both the sender's own
    // bubble and the receiver's bubble size the chat bubble correctly on
    // the very FIRST layout pass, with zero dependency on when Glide
    // actually finishes decoding the bitmap (mirrors WhatsApp/Telegram,
    // which always know a photo's aspect ratio up front). Null on messages
    // sent before this field existed — those fall back to the old
    // decode-then-relayout behavior (MessageBubbleCanvasView's
    // MEDIA_ASPECT_CACHE path) for backward compatibility.
    public Integer mediaWidth;
    public Integer mediaHeight;

    // ── Feature 1: Read Receipts ──────────────────────────
    /** sent | delivered | read */
    public String status;
    /** TICK ADVANCE #5: server timestamps for status transitions — set by
     *  MessageStatusSync (and the batched read-flush in ChatPresenceController).
     *  Nullable: absent for messages sent before this field existed. */
    public Long deliveredAt;
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
    /** Prior text versions, oldest first. Each entry: {"text":..,"editedAt":..}.
     *  Null/empty for never-edited messages. Current text always lives in
     *  {@link #text} — this list only holds what it WAS before each edit.
     *  Firebase path: messages/{id}/editHistory. Room: editHistoryJson
     *  (see EditHistoryJsonUtil for the List ↔ JSON-string conversion). */
    public java.util.List<Map<String, Object>> editHistory;

    // ── Feature 5: Delete for Everyone ───────────────────
    public Boolean deleted;

    // ── Feature 6: Forward ───────────────────────────────
    /** Display name of original sender if message was forwarded */
    public String forwardedFrom;

    // ── Feature 7: Starred ───────────────────────────────
    public Boolean starred;

    // ── Feature 8: Pinned ────────────────────────────────
    public Boolean pinned;


    // ── Feature: Reel Share (Instagram reel shared into chat) ────────────────
    /** Shared Instagram reel URL — set when type = "reel_share". */
    public String reelShareUrl;
    /** Instagram username of the reel creator — shown in the card header. */
    public String reelShareUsername;
    /** Caption/description of the reel — shown below the thumbnail. */
    public String reelShareCaption;
    /** Thumbnail URL for the reel card — may be blank if not available. */
    public String reelShareThumb;
    /** Owner profile photo URL — shown as circular avatar in card header. */
    public String reelShareOwnerPhoto;

    // ── Feature 9: Reel Seen Bubble ──────────────────────────────
    /** Reel ID — set when type = "reel_seen". Used to open reel on tap. */
    public String reelId;
    /** Reel thumbnail URL — shown in the reel_seen bubble. */
    public String reelThumbUrl;
    /** UID of the reel's owner — set when type = "reel_seen". The bubble
     *  must render ONLY for this user (the person whose reel was watched),
     *  never for the viewer who watched it. See MessageAdapter /
     *  MessagePagingAdapter getItemViewType(). */
    public String reelOwnerUid;

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

    // ── Broadcast flag ───────────────────────────────────
    /**
     * True when this message was delivered to the recipient as part of a
     * broadcast list (set by BroadcastDeliveryWorker). Recipients see a
     * 📢 indicator in ChatActivity so they know it is a broadcast.
     */
    public Boolean broadcast;

    // ── Feature 11: Disappearing Messages ────────────────────────────────
    /**
     * Epoch ms at which this message should auto-delete.
     * Set by sender = timestamp + disappearingMs from ChatPrivacyManager.
     * 0 or null = no expiry.
     */
    public Long expiresAt;

    // ── Feature: Typing Font Style ────────────────────────────────────────
    /**
     * Font style ID used when this message was typed.
     * Maps to TypingStyleManager.STYLE_* constants (0–19).
     * 0 = Normal (default). Stored in Firebase & Room so receiver sees same style.
     */
    public int fontStyle;

    // ── Feature 12: Polls ─────────────────────────────────────────
    /** Set when type = "poll". The poll question text. */
    public String pollQuestion;
    /** Poll answer options, in display order. Firebase path: messages/{id}/pollOptions */
    public java.util.List<String> pollOptions;
    /** Map of uid → list of ticked option indices. A single-choice poll just
     *  keeps a one-element list per voter; a multi-choice poll (see
     *  {@link #pollMultiChoice}) can have several indices per voter. */
    public Map<String, java.util.List<Integer>> pollVotes;
    /** If true, voter identities + results are hidden until the poll creator reveals them. */
    public Boolean pollAnonymous;
    /** If true, voting is closed — no further votes accepted. */
    public Boolean pollClosed;
    /** Advanced polls: if true, voters may tick more than one option (checkbox
     *  style); if false/null, voting is single-choice (radio style, default). */
    public Boolean pollMultiChoice;


    // ── Feature 13: View Once / Secret Message ───────────────────────────
    /**
     * True if this message can be viewed only once.
     * Supported for type: text | image | video | audio | file.
     * Set by sender before push. Never mutated after send.
     */
    public Boolean viewOnce;

    /**
     * Current state of a view-once message lifecycle.
     * Values: "sent" | "opened" | "deleted"
     * See ChatViewOnceController.STATE_* constants.
     * Only meaningful when viewOnce == true.
     */
    public String viewOnceState;

    /**
     * Epoch ms when receiver first opened this view-once message.
     * Set server-side via ServerValue.TIMESTAMP for accuracy.
     * 0 / null = not yet opened.
     */
    public Long openedAt;

    /**
     * Feature 2 (Expiry timer): Unix timestamp (ms) after which the view-once message
     * auto-expires even if the receiver hasn't opened it.
     * 0 / null = no expiry (message stays until receiver opens it).
     */
    public Long viewOnceExpiresAt;

    // ── Feature: Multi Media ──────────────────────────────────────────────
    /** Set when type = "multi_media". Each entry has keys:
     *  "url" (CDN URL), "thumbUrl" (optional), "mediaType" ("image" or "video") */
    public java.util.List<java.util.Map<String, Object>> mediaItems;
    /** Optional caption attached to a multi_media message. */
    public String caption;

    // ── Feature: Contact Card Share ───────────────────────────────────────
    /** Set when type = "contact". Display name of the shared contact. */
    public String contactName;
    /** Phone number of the shared contact (E.164 or raw, as picked from device). */
    public String contactPhone;
    /** Optional secondary phone number, if the contact has more than one. */
    public String contactPhone2;
    /** Optional photo URI/URL of the shared contact (device content:// URI is NOT
     *  portable across devices, so this is best-effort — may be null). */
    public String contactPhotoUrl;

    // ── Feature: Location Share (pin-drop) ──────────────────────────────────
    /** Set when type = "location". Latitude of the shared point. */
    public Double locationLat;
    /** Longitude of the shared point. */
    public Double locationLng;
    /** Reverse-geocoded human readable address — may be null if geocoding failed. */
    public String locationAddress;

    public Message() {}

    /**
     * Fast structural equality — used by DiffUtil's areContentsTheSame().
     * Map fields (reactions, pollVotes) use their own equals() which iterates
     * entries; keeping this method here ensures the comparison happens once
     * per diff cycle, not scattered across anonymous DiffUtil callbacks.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Message)) return false;
        Message o = (Message) obj;
        // Fast-exit on the most commonly-changing field first
        if (!java.util.Objects.equals(status, o.status))       return false;
        if (!java.util.Objects.equals(text, o.text))           return false;
        if (!java.util.Objects.equals(type, o.type))           return false;
        if (!java.util.Objects.equals(messageId, o.messageId)) return false;
        if (!java.util.Objects.equals(id, o.id))               return false;
        if (!java.util.Objects.equals(edited, o.edited))       return false;
        if (!java.util.Objects.equals(deleted, o.deleted))     return false;
        if (!java.util.Objects.equals(starred, o.starred))     return false;
        if (!java.util.Objects.equals(pinned, o.pinned))       return false;
        if (!java.util.Objects.equals(pollClosed, o.pollClosed)) return false;
        // Expensive map comparisons last — only reached if scalar fields match
        if (!java.util.Objects.equals(reactions, o.reactions))  return false;
        if (!java.util.Objects.equals(pollVotes, o.pollVotes))  return false;
        return true;
    }

    @Override
    public int hashCode() {
        String key = (messageId != null ? messageId : (id != null ? id : "")) + status;
        return key.hashCode();
    }
}
// INJECTED BY PATCH — do not edit manually
// The actual insertion is done via sed below
