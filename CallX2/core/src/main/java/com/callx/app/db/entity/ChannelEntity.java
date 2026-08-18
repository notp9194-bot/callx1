package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * Room entity for offline-first channel metadata.
 * Table: channels
 * Synced from Firebase: channels/{channelId}
 *
 * WhatsApp-level v2 — adds pinnedPostId, totalViews, ownerName/ownerIconUrl,
 * weeklyGrowth, inviteCode for comprehensive channel management.
 */
@Entity(
    tableName = "channels",
    indices = {
        @Index("ownerUid"),
        @Index(value = "followers"),
        @Index(value = "lastPostAt"),
        @Index(value = "category"),
        @Index(value = "isFollowed"),
        @Index(value = "weeklyGrowth")
    }
)
public class ChannelEntity {

    @PrimaryKey @NonNull
    public String id = "";

    public String name;
    public String description;
    public String iconUrl;
    public long   followers;
    public boolean verified;
    public String category;
    public String ownerUid;
    public String ownerName;          // cached for fast display in admin/info views
    public String ownerIconUrl;       // cached owner avatar URL
    public long   createdAt;
    public boolean isPrivate;
    public String inviteLink;
    public String inviteCode;         // short invite code (e.g. "abc123")
    public long   totalPosts;
    public long   totalViews;         // aggregate views across all posts
    public long   weeklyGrowth;       // follower delta last 7 days (trending sort)

    // Pinned post
    public String pinnedPostId;       // ID of pinned post, null if none

    // Denormalized last-post preview
    public long   lastPostAt;
    public String lastPostText;
    public String lastPostMediaUrl;
    public String lastPostType;

    // Local tracking
    public boolean isFollowed;            // true if current user follows this channel
    public boolean isMuted;               // true if user muted notifications for this channel
    public boolean isAdmin;               // true if current user is owner or admin
    public long    unreadCount;           // posts since lastSeenPostTimestamp
    public long    lastSeenPostTimestamp; // timestamp of last post the user viewed
    public long    syncedAt;              // last Firebase sync timestamp (ms)
    public long    followersSyncedAt;     // last time followers list was synced

    // ── NEW in v5 ─────────────────────────────────────────────────────────
    /** isVerified — shown with a checkmark badge in channel lists/header. */
    public boolean isVerified;

    /** topicTags — JSON array of topic tag strings, e.g. ["tech","news"] */
    public String topicTagsJson;

    /** Transient list of topic tags; populated by callers that parse topicTagsJson. */
    @androidx.room.Ignore
    public java.util.List<String> topicTags;

    /** isFollowing — alias kept for backwards compat with ExploreChannelsActivity. */
    public boolean isFollowing;
}
