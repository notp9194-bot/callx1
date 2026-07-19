package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * Room entity for offline-first channel posts.
 * Table: channel_posts
 * Synced from Firebase: channelPosts/{channelId}/{postId}
 */
@Entity(
    tableName = "channel_posts",
    indices = {
        @Index(value = {"channelId", "timestamp"})
    }
)
public class ChannelPostEntity {

    @PrimaryKey @NonNull
    public String id = "";

    public String channelId;
    public String text;
    public String type;          // "text" | "image" | "video" | "link"
    public String mediaUrl;
    public String thumbnailUrl;
    public String linkUrl;
    public String linkTitle;
    public String linkDescription;
    public long   timestamp;
    public long   viewCount;
    public long   forwardCount;
    public String reactionsJson;  // JSON-serialized Map<String,String>
    public long   syncedAt;
}
