package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * Room entity for offline-first channel metadata.
 * Table: channels
 * Synced from Firebase: channels/{channelId}
 */
@Entity(
    tableName = "channels",
    indices = {
        @Index("ownerUid"),
        @Index(value = "followers"),
        @Index(value = "lastPostAt")
    }
)
public class ChannelEntity {

    @PrimaryKey @NonNull
    public String id = "";

    public String name;
    public String description;
    public String iconUrl;
    public long   followers;
    public boolean verified;
    public String category;
    public String ownerUid;
    public long   createdAt;

    // Denormalized last-post preview
    public long   lastPostAt;
    public String lastPostText;
    public String lastPostMediaUrl;
    public String lastPostType;

    // Local tracking
    public boolean isFollowed;        // true if current user follows this channel
    public long    syncedAt;          // last Firebase sync timestamp (ms)
}
