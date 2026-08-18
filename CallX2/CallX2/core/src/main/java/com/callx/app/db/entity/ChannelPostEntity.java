package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * Room entity for offline-first channel posts.
 * Table: channel_posts
 * Synced from Firebase: channelPosts/{channelId}/{postId}
 *
 * WhatsApp-level v2 — adds isPinned, scheduledAt, replyCount, allowReactions,
 * allowForward, authorIconUrl, linkDomain, pollMultiSelect, pollExpiresAt,
 * mediaWidth/Height for comprehensive post rendering.
 */
@Entity(
    tableName = "channel_posts",
    indices = {
        @Index(value = {"channelId", "timestamp"}),
        @Index(value = {"channelId", "isDeleted"}),
        @Index(value = {"channelId", "isPinned"}),
        @Index(value = {"channelId", "scheduledAt"})
    }
)
public class ChannelPostEntity {

    @PrimaryKey @NonNull
    public String id = "";

    public String channelId;

    // Author
    public String authorUid;
    public String authorName;
    public String authorIconUrl;     // cached avatar

    // Content
    public String text;
    public String type;              // "text"|"image"|"video"|"link"|"poll"|"audio"|"document"
    public String mediaUrl;
    public String thumbnailUrl;
    public int    mediaWidth;        // original pixel width
    public int    mediaHeight;       // original pixel height

    // Link
    public String linkUrl;
    public String linkTitle;
    public String linkDescription;
    public String linkImageUrl;
    public String linkDomain;        // e.g. "bbc.com"

    // Poll (serialized)
    public String pollQuestion;
    public String pollOptionsJson;   // JSON array of option strings
    public String pollVotesJson;     // JSON map uid→optionIndex
    public int    pollTotalVotes;    // denormalized
    public boolean pollMultiSelect;  // allow multi-select
    public long   pollExpiresAt;     // 0 = no expiry

    // Audio
    public String audioUrl;
    public long   audioDurationMs;
    public String audioWaveformJson; // JSON array of amplitude samples

    // Document
    public String documentUrl;
    public String documentName;
    public long   documentSizeBytes;
    public String documentMimeType;

    // Pinning
    public boolean isPinned;         // true if currently pinned

    // Scheduling
    public long   scheduledAt;       // > 0 if scheduled; publish when time passes
    public boolean isDraft;          // saved draft (not published, not scheduled)

    // Metadata
    public long   timestamp;
    public long   editedAt;
    public boolean isDeleted;
    public long   viewCount;
    public long   forwardCount;
    public int    replyCount;        // number of replies/comments
    public String reactionsJson;     // JSON-serialized Map<String,String>

    // Interaction flags
    public boolean allowReactions;   // default true
    public boolean allowForward;     // default true

    public long   syncedAt;

    // ── NEW in v5: Broadcast ────────────────────────────────────────────
    public String broadcastPriority;   // "normal"|"important"|"urgent"

    // ── NEW in v5: Event ────────────────────────────────────────────────
    public String  eventTitle;
    public String  eventLocation;
    public long    eventStartAt;
    public long    eventEndAt;
    public String  eventImageUrl;
    public boolean eventRsvpEnabled;

    // ── NEW in v5: Anonymous poll ───────────────────────────────────────
    public boolean pollAnonymous;

    // ── NEW in v5: Topic tags ───────────────────────────────────────────
    public String topicTagsJson;       // JSON array of tag strings

    // ── NEW in v5: Mentions ─────────────────────────────────────────────
    public String mentionedUidsJson;   // JSON array of mentioned UIDs
}
