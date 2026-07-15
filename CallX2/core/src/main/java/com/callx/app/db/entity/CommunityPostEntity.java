package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v30: Room entity — one row per community feed/announcement post.
 * Firebase source of truth: communities/{communityId}/posts/{id}
 *
 * Covers both regular feed posts AND announcements (isAnnouncement=true) —
 * one adapter (CommunityPostAdapter) renders both, CommunityAnnouncementsFragment
 * just queries with isAnnouncement=1. A post optionally carries a poll,
 * serialized as JSON in pollJson (see community.CommunityPoll#toJson /
 * #fromJson) — kept as a single TEXT column instead of normalized poll
 * tables since polls are small (<=4 options) and always read/written whole.
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
    public String pollJson;       // null if this post has no poll
    public long   createdAt;
    public long   syncedAt;

    public CommunityPostEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
