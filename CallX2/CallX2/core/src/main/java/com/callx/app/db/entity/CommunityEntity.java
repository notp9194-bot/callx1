package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v31: Room entity — Community offline cache.
 *
 * v34 additions:
 *  - bannerUrl:  wide banner image shown at top of CommunityActivity (1200×400 ideal)
 *  - rules:      plain-text community rules/guidelines (newline separated)
 *  - category:   community category string e.g. "Tech", "Sports", "Gaming", etc.
 *  - isPublic:   true = discoverable in Discover page (inverse of isPrivate)
 *  - isVerified: owner-verified badge shown next to community name
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

    // v34: Visual identity
    public String  bannerUrl;        // wide banner/cover image (nullable)
    public String  rules;            // plain-text rules, newline-separated (nullable)
    public String  category;         // e.g. "Tech", "Sports", "Gaming", "Music"
    public boolean isVerified;       // verified badge next to name

    public CommunityEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
