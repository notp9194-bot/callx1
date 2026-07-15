package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v31: Room entity — a post scheduled to be published at a future time.
 * Stored locally; published by ScheduledPostPublishWorker.
 *
 * status: "pending" | "published" | "cancelled"
 */
@Entity(
    tableName = "community_scheduled_posts",
    indices = {
        @Index(value = {"communityId", "status"})
    }
)
public class CommunityScheduledPostEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String communityId;
    public String authorUid;
    public String authorName;
    public String authorPhoto;
    public String text;
    public String mediaUrl;
    public String mediaType;
    public boolean isAnnouncement;
    public long   scheduledAt;    // epoch ms when to publish
    public String status;         // "pending" | "published" | "cancelled"
    public String pollJson;       // null if no poll
    public long   createdAt;
}
