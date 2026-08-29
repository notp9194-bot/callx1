package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/**
 * Room DB entity for cached user profiles.
 */
@Entity(
    tableName = "users",
    indices = { @Index(value = {"callxId"}), @Index(value = {"lastSeen"}) }
)
public class UserEntity {

    @PrimaryKey
    @NonNull
    public String uid = "";

    public String email;
    public String name;
    public String emoji;
    public String callxId;
    public String about;
    public String photoUrl;
    public String thumbUrl;   // 100×100 WebP thumbnail — chat list / avatars
    // Bumped by 1 (Firebase ServerValue.increment) every time this user
    // uploads a new avatar — see ProfileActivity#uploadAvatar. Lets
    // AvatarUrlBuilder append a cache-busting ?v= param so a locally
    // cached (possibly stale) photoUrl/thumbUrl string still produces a
    // fresh Glide cache key once a newer version is known, instead of
    // waiting for photoUrl/thumbUrl itself to be re-synced from Firebase.
    public long   avatarVersion;
    public String fcmToken;
    public Long   lastSeen;
    public String lastMessage;
    public Long   lastMessageAt;
    public Long   unread;
    public long   cachedAt;

    public UserEntity() {
        this.cachedAt = System.currentTimeMillis();
    }
}
