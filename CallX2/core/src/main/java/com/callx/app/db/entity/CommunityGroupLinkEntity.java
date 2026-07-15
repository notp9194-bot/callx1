package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * v30: Room entity — links an existing group chat (GroupEntity.id) into a
 * Community's "Groups" tab. The group chat itself still works exactly as
 * before (GroupChatActivity, GroupDao, etc.) — this is purely a membership
 * record saying "this group belongs to this community" so
 * CommunityGroupsFragment can list it and tap-through to open it.
 *
 * Firebase source of truth: communities/{communityId}/groups/{groupId}
 */
@Entity(
    tableName = "community_group_links",
    primaryKeys = {"communityId", "groupId"},
    indices = { @Index(value = {"communityId"}), @Index(value = {"groupId"}) }
)
public class CommunityGroupLinkEntity {

    @NonNull
    public String communityId = "";

    @NonNull
    public String groupId = "";

    public String addedByUid;
    public long   addedAt;
    public long   syncedAt;

    public CommunityGroupLinkEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
