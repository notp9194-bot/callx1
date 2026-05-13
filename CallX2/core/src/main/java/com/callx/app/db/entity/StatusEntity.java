package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v17: Room entity for status cache.
 * Offline mein Status tab dikhane ke liye.
 * Sirf essential fields cache karte hain.
 */
@Entity(
    tableName = "statuses",
    indices = {
        @Index(value = {"ownerUid"}),
        @Index(value = {"timestamp"})
    }
)
public class StatusEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String ownerUid;
    public String ownerName;
    public String ownerPhoto;
    public String type;          // text | image | video
    public String text;
    public String mediaUrl;
    public String thumbnailUrl;
    public String bgColor;
    public String fontStyle;
    public String textColor;
    public Long   timestamp;
    public Long   expiresAt;
    public Boolean deleted;
    public long   syncedAt;

    public StatusEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
