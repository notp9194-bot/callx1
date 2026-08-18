package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.List;
import java.util.Map;

/**
 * ChannelPost — shared core model for a post inside a channel.
 *
 * Firebase node: channelPosts/{channelId}/{postId}/
 *
 * Post types:
 *   "text"     — plain text
 *   "image"    — mediaUrl = downloadable image URL
 *   "video"    — mediaUrl = video URL, thumbnailUrl = cover frame
 *   "link"     — linkUrl + linkTitle + linkDescription + linkImageUrl
 *   "poll"     — pollQuestion + pollOptions (list) + pollVotes (map uid→optionIndex)
 *   "audio"    — audioUrl + audioDurationMs + optional text caption
 *   "document" — documentUrl + documentName + documentSize + optional text caption
 *
 * WhatsApp-level v2 — adds isPinned, scheduledAt, replyCount, mentionedUids,
 * allowReactions, and allowForward flags.
 */
@IgnoreExtraProperties
public class ChannelPost {

    public String id;
    public String channelId;

    // ── Author (for admin/multi-admin channels) ─────────────────────────
    public String authorUid;
    public String authorName;
    public String authorIconUrl;   // cached avatar for fast display

    // ── Content ──────────────────────────────────────────────────────────
    public String text;
    public String type;

    // Image / Video
    public String mediaUrl;
    public String thumbnailUrl;
    public int    mediaWidth;      // original pixel dimensions for aspect ratio
    public int    mediaHeight;

    // Link preview
    public String linkUrl;
    public String linkTitle;
    public String linkDescription;
    public String linkImageUrl;     // OG image for the link
    public String linkDomain;       // e.g. "bbc.com" extracted from linkUrl

    // Poll
    public String pollQuestion;
    public List<String> pollOptions;
    /** uid → option index (0-based) */
    public Map<String, Long> pollVotes;
    public boolean pollMultiSelect; // if true, allow multiple option votes
    public long    pollExpiresAt;   // 0 = no expiry

    // Audio
    public String audioUrl;
    public long   audioDurationMs;
    public String audioWaveformJson; // JSON array of amplitude samples for waveform display

    // Document
    public String documentUrl;
    public String documentName;
    public long   documentSizeBytes;
    public String documentMimeType;

    // ── Pinning ──────────────────────────────────────────────────────────
    public boolean isPinned;       // true if this is the pinned post

    // ── Scheduling ───────────────────────────────────────────────────────
    public long scheduledAt;       // > 0 if this is a scheduled (not yet published) post
    public boolean isDraft;        // true if saved as draft

    // ── Metadata ──────────────────────────────────────────────────────────
    public long   timestamp;
    public long   editedAt;         // 0 if never edited
    public boolean isDeleted;       // soft delete — show "This message was deleted"
    public long   viewCount;
    public long   forwardCount;
    public int    replyCount;       // number of replies / comments

    // ── Interaction flags (admin-configurable) ────────────────────────────
    public boolean allowReactions;  // default true
    public boolean allowForward;    // default true

    // ── Social ────────────────────────────────────────────────────────────
    /** uid → emoji, e.g. "uid123" → "👍" */
    public Map<String, String> reactions;

    public ChannelPost() {
        this.allowReactions = true;
        this.allowForward   = true;
    }

    /** Returns true if this post has been edited after initial publish. */
    public boolean wasEdited() { return editedAt > 0 && editedAt != timestamp; }

    /** Returns true if this post is still scheduled (not yet published). */
    public boolean isScheduled() { return scheduledAt > 0 && scheduledAt > System.currentTimeMillis(); }

    /** Returns total reaction count across all emojis. */
    public int getTotalReactions() {
        return reactions != null ? reactions.size() : 0;
    }

    /** Returns total poll vote count. */
    public int getTotalVotes() {
        return pollVotes != null ? pollVotes.size() : 0;
    }

    /** Returns vote count for a given option index. */
    public int getVotesForOption(int optionIndex) {
        if (pollVotes == null) return 0;
        int count = 0;
        for (Long v : pollVotes.values()) {
            if (v != null && v == optionIndex) count++;
        }
        return count;
    }

    /** Returns the emoji the given uid reacted with, or null if no reaction. */
    public String getMyReaction(String uid) {
        if (reactions == null || uid == null) return null;
        return reactions.get(uid);
    }

    /** Returns a map of emoji → count for displaying reaction bubbles. */
    public Map<String, Integer> getReactionCounts() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        if (reactions == null) return counts;
        for (String emoji : reactions.values()) {
            counts.put(emoji, counts.getOrDefault(emoji, 0) + 1);
        }
        return counts;
    }

    /** Returns human-readable file size. */
    public String getFormattedDocumentSize() {
        if (documentSizeBytes <= 0) return "";
        if (documentSizeBytes < 1024) return documentSizeBytes + " B";
        if (documentSizeBytes < 1024 * 1024) return String.format("%.1f KB", documentSizeBytes / 1024.0);
        return String.format("%.1f MB", documentSizeBytes / (1024.0 * 1024.0));
    }

    /** Returns human-readable audio duration. */
    public String getFormattedDuration() {
        if (audioDurationMs <= 0) return "0:00";
        long secs = audioDurationMs / 1000;
        return String.format("%d:%02d", secs / 60, secs % 60);
    }

    // ── NEW in v5: Broadcast ─────────────────────────────────────────────
    /**
     * broadcastPriority: "normal" | "important" | "urgent"
     * Used only when type == "broadcast"
     */
    public String broadcastPriority;

    // ── NEW in v5: Event ─────────────────────────────────────────────────
    /**
     * Event fields — used only when type == "event"
     */
    public String eventTitle;
    public String eventLocation;
    public long   eventStartAt;        // epoch millis
    public long   eventEndAt;          // epoch millis; 0 = open-ended
    public String eventImageUrl;       // banner image
    public boolean eventRsvpEnabled;   // true = show Going/Maybe/Not Going buttons

    // ── NEW in v5: Anonymous poll voting ─────────────────────────────────
    /**
     * pollAnonymous — when true, voter identities are hidden in ChannelPollResultsActivity.
     * Only the aggregate vote count per option is shown.
     */
    public boolean pollAnonymous;

    // ── NEW in v5: Topic tags ─────────────────────────────────────────────
    /**
     * topicTags — list of topic tag strings (e.g. ["news", "sports"]).
     * Used for filtering in ChannelViewerActivity topic chip row.
     */
    public java.util.List<String> topicTags;

    // ── NEW in v5: @Mentions ─────────────────────────────────────────────
    /**
     * mentionedUids — list of UIDs @mentioned in this post's text.
     */
    public java.util.List<String> mentionedUids;

}
