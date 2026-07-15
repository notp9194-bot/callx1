package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v31: Room entity — one row per community feed/announcement post.
 * Firebase source of truth: communities/{communityId}/posts/{id}
 *
 * New in v31:
 *  - reactionCountsJson: JSON Map<reactionType, count> for multi-emoji reactions
 *  - myReactionType:     current user's reaction type (not persisted to Firebase — local only)
 *  - mentionedUids:      comma-separated UIDs mentioned in the post text
 *  - scheduledAt:        if > 0, post was originally a scheduled post
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
    public String mediaUrl;
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

    public CommunityPostEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
