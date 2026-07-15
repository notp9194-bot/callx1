package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * v31: Room entity — one row per (group, member). Offline cache for
 * GroupInfoActivity / GroupMemberAdapter so member list isn't blank
 * while Firebase is loading.
 * Firebase source of truth: groups/{groupId}/members/{uid}
 * (see FirebaseUtils.getGroupMembersRef)
 */
@Entity(
    tableName = "group_members",
    primaryKeys = {"groupId", "uid"},
    indices = { @Index(value = {"groupId"}), @Index(value = {"uid"}) }
)
public class GroupMemberEntity {

    @NonNull
    public String groupId = "";

    @NonNull
    public String uid = "";

    public String name;
    public String role;       // "creator" | "admin" | "member"
    public String photoUrl;
    public String thumbUrl;
    public boolean online;
    public Long   lastSeen;
    public long   joinedAt;
    public long   syncedAt;

    public GroupMemberEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
