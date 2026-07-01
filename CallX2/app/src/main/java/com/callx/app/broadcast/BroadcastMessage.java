package com.callx.app.broadcast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BroadcastMessage — Firebase data model for messages sent through a broadcast list.
 *
 * Firebase path: broadcast_messages/{ownerUid}/{listId}/{messageId}
 *
 * Advanced features added:
 *  - Scheduled send        (scheduledAt)
 *  - Per-recipient seen timestamps (seenByTs)
 *  - Auto-expiry           (expiresAt)
 *  - Poll / Survey         (pollQuestion, pollOptions, pollVotes)
 *  - Multi-media           (mediaUrls)
 *  - Link preview          (linkPreviewUrl/Title/Desc/ImageUrl)
 *  - Reply count           (replyCount)
 */
public class BroadcastMessage {

    public String id;
    public String text;
    /** text | image | video | audio | file | poll | multi_media */
    public String type;
    public String mediaUrl;
    public String fileName;
    public String caption;
    public String senderId;
    public long   timestamp;
    public int    deliveredCount;
    public int    skippedCount;
    public int    totalRecipients;
    public int    seenCount;
    /** uid → true — kept as backward-compat dedupe guard alongside seenByTs. */
    public Map<String, Boolean> seenBy;
    /** sending | sent | failed | scheduled */
    public String status;

    // ── Feature 1: Scheduled Send ─────────────────────────────────────────────
    /**
     * Epoch ms when this message should be delivered.
     * 0 = deliver immediately (normal send).
     * WorkManager uses this as the initial delay.
     */
    public long scheduledAt;

    // ── Feature 2: Per-recipient Seen with Timestamps ─────────────────────────
    /**
     * uid → epoch ms when that recipient first read the message.
     * Populated atomically alongside seenBy by markSeen() transaction.
     */
    public Map<String, Long> seenByTs;

    // ── Feature 3: Auto-expiry ────────────────────────────────────────────────
    /**
     * Epoch ms when this message should auto-delete from broadcast_messages.
     * 0 = never expire. BroadcastExpiryWorker checks this periodically.
     */
    public long expiresAt;

    // ── Feature 4: Poll / Survey ──────────────────────────────────────────────
    /** Poll question text (non-null only when type = "poll"). */
    public String pollQuestion;
    /** Ordered list of poll option texts. */
    public List<String> pollOptions;
    /** uid → index of chosen option (0-based). */
    public Map<String, Integer> pollVotes;

    // ── Feature 5: Multi-media ────────────────────────────────────────────────
    /**
     * Ordered list of media URLs for a multi-image/video message.
     * Used only when type = "multi_media". First URL is primary.
     */
    public List<String> mediaUrls;

    // ── Feature 6: Link Preview ───────────────────────────────────────────────
    /** Canonical URL detected in text — used to fetch the preview. */
    public String linkPreviewUrl;
    /** Open Graph title fetched from the URL. */
    public String linkPreviewTitle;
    /** Open Graph description. */
    public String linkPreviewDesc;
    /** Open Graph image URL for the preview card thumbnail. */
    public String linkPreviewImageUrl;

    // ── Feature 7: Reply Count ────────────────────────────────────────────────
    /**
     * How many recipients replied to this broadcast in their personal 1:1 chat
     * after the message was delivered. Incremented by BroadcastChatActivity's
     * reply listener.
     */
    public int replyCount;

    // ── Constructors ──────────────────────────────────────────────────────────

    public BroadcastMessage() {}

    public BroadcastMessage(String id, String text, String type,
                             String mediaUrl, String fileName, String caption,
                             String senderId, long timestamp,
                             int totalRecipients) {
        this.id               = id;
        this.text             = text;
        this.type             = type;
        this.mediaUrl         = mediaUrl;
        this.fileName         = fileName;
        this.caption          = caption;
        this.senderId         = senderId;
        this.timestamp        = timestamp;
        this.deliveredCount   = 0;
        this.skippedCount     = 0;
        this.totalRecipients  = totalRecipients;
        this.seenCount        = 0;
        this.seenBy           = new HashMap<>();
        this.seenByTs         = new HashMap<>();
        this.status           = "sending";
        this.scheduledAt      = 0;
        this.expiresAt        = 0;
        this.replyCount       = 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** True if this message is a poll. */
    public boolean isPoll() {
        return "poll".equals(type);
    }

    /** True if this message carries multiple media items. */
    public boolean isMultiMedia() {
        return "multi_media".equals(type);
    }

    /** True if this message has a fetchable link preview. */
    public boolean hasLinkPreview() {
        return linkPreviewUrl != null && !linkPreviewUrl.isEmpty()
            && linkPreviewTitle != null && !linkPreviewTitle.isEmpty();
    }

    /** True if scheduled for future delivery (not yet sent). */
    public boolean isScheduled() {
        return "scheduled".equals(status) && scheduledAt > System.currentTimeMillis();
    }

    /** True if this message has expired and should not be shown. */
    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    /**
     * Tallies poll votes and returns an array of vote counts, one per option.
     * Returns an empty array if there are no poll options.
     */
    public int[] getPollVoteCounts() {
        if (pollOptions == null || pollOptions.isEmpty()) return new int[0];
        int[] counts = new int[pollOptions.size()];
        if (pollVotes != null) {
            for (int idx : pollVotes.values()) {
                if (idx >= 0 && idx < counts.length) counts[idx]++;
            }
        }
        return counts;
    }
}
