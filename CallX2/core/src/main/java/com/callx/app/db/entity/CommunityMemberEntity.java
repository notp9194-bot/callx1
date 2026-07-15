package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * v31: Room entity — one row per (community, member).
 * Firebase source of truth: communities/{communityId}/members/{uid}
 *
 * New in v31:
 *  - badge: member badge/level (see CommunityBadge constants)
 *  - isMuted: muted members can't post but remain in community
 *  - isBanned: banned members are soft-flagged before removal
 */
@Entity(
    tableName = "community_members",
    primaryKeys = {"communityId", "uid"},
    indices = { @Index(value = {"communityId"}), @Index(value = {"uid"}) }
)
public class CommunityMemberEntity {

    @NonNull
    public String communityId = "";

    @NonNull
    public String uid = "";

    public String name;
    public String photoUrl;
    public String role;       // OWNER | ADMIN | MEMBER
    public long   joinedAt;
    public long   syncedAt;

    // v31: Badges + moderation
    public String  badge;     // see CommunityBadge constants; null/"none" = no badge
    public boolean isMuted;   // muted = can read but not post
    public boolean isBanned;  // banned = pending removal (soft flag before Firebase delete)

    public CommunityMemberEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
