package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v31: Room entity — Community offline cache.
 *
 * New in v31:
 *  - isPrivate:    join requires admin approval (join_requests flow)
 *  - inviteToken:  shareable invite token (callx://community/{id}?invite={token})
 *  - inviteEnabled: whether the invite link is active
 *
 * Firebase source of truth: communities/{id}
 * Owner lookup index:       community_by_owner/{ownerUid} -> id
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

    // v31: Privacy + invite link
    public boolean isPrivate;        // true = join requests required
    public String  inviteToken;      // shareable token for invite links
    public boolean inviteEnabled;    // whether invite link is active

    public CommunityEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
