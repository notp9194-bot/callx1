package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * v30: Room entity — one row per (community, member).
 * Firebase source of truth: communities/{communityId}/members/{uid}
 *
 * role is one of {@link com.callx.app.community.CommunityRole}'s raw
 * values: "OWNER", "ADMIN", "MEMBER" — kept as a plain String here (not an
 * enum) so Room doesn't need a TypeConverter.
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

    public CommunityMemberEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
