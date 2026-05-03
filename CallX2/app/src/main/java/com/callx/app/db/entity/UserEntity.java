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
