package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v31: Room entity — one row per community feed/announcement post.
 *
 * v34 additions:
 *  - mediaUrlsJson:  JSON array of media URLs for carousel posts (up to 5 items)
 *  - mediaTypesJson: JSON array matching mediaUrlsJson — "image"|"video" per item
 *  - viewCount:      how many unique members have viewed this post
 *  - bookmarkCount:  how many members bookmarked (saved) this post
 *  - shareCount:     how many times this post was shared out
 */
@Entity(
    tableName = "community_posts",
    indices = {
        @Index(value = {"communityId", "isAnnouncement", "createdAt"}),
        @Index(value = {"communityId", "mediaUrl"})
    }
)
public class CommunityPostEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String communityId;
    public String authorUid;
    public String authorName;
    public String authorPhoto;
    public String text;
    public String mediaUrl;       // first / primary media URL (backward compat)
    public String mediaType;      // "image" | "video" | null
    public boolean isAnnouncement;
    public boolean pinned;
    public long   likeCount;
    public long   commentCount;
    public String pollJson;       // null if no poll
    public long   createdAt;
    public long   syncedAt;

    // v31: Multi-emoji reactions
    public String reactionCountsJson; // JSON map: {"LIKE":5, "LOVE":2, ...}
    public String myReactionType;     // local-only cache of current user's reaction

    // v31: @mentions
    public String mentionedUids;      // comma-separated UIDs e.g. "uid1,uid2"

    // v31: Scheduled post origin
    public long scheduledAt;          // 0 = posted immediately; >0 = was scheduled

    // v34: Carousel / multi-media
    public String mediaUrlsJson;      // JSON array: ["url1","url2",...] — null = single media
    public String mediaTypesJson;     // JSON array: ["image","video",...] — parallel to mediaUrlsJson

    // v34: Engagement counters
    public long viewCount;            // unique member views
    public long bookmarkCount;        // saves/bookmarks
    public long shareCount;           // shares out

    public CommunityPostEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
