package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v30: Room entity — Community offline cache.
 *
 * Firebase source of truth: communities/{id}
 * Owner lookup index:       community_by_owner/{ownerUid} -> id
 *
 * A Community is opt-in per user (see CommunityRepository#createCommunity) —
 * only users who explicitly enabled one have a row here / in Firebase.
 * It bundles: a feed (posts + polls), an announcements channel (pinned
 * posts only admins can write), one or more linked group chats
 * (GroupEntity rows), and a member list with roles.
 */
@Entity(
    tableName = "communities",
    indices = { @Index(value = {"ownerUid"}) }
)
public class CommunityEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String name;
    public String description;
    public String iconUrl;
    public String ownerUid;
    public long   memberCount;
    public long   groupCount;
    public long   postCount;
    public long   createdAt;
    public long   syncedAt;

    public CommunityEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
