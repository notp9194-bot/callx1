package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v16: Room entity for group list cache.
 * Offline mein groups tab dikhane ke liye.
 */
@Entity(
    tableName = "groups",
    indices = { @Index(value = {"lastMessageAt"}) }
)
public class GroupEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String name;
    public String description;
    public String iconUrl;
    public String createdBy;
    public String lastMessage;
    public String lastSenderName;
    public Long   lastMessageAt;
    public long   syncedAt;

    public GroupEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
