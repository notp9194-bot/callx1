package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v31: Room entity — in-app community notification.
 * Firebase source of truth: community_notifications/{targetUid}/{id}
 *
 * type values:
 *   "new_post"       — someone posted in a community you're in
 *   "mention"        — you were @mentioned
 *   "reply"          — someone replied to your post/comment
 *   "reaction"       — someone reacted to your post
 *   "role_change"    — your role was updated
 *   "join_approved"  — your join request was approved
 *   "join_rejected"  — your join request was rejected
 *   "event_reminder" — an event you RSVPd to is starting soon
 */
@Entity(
    tableName = "community_notifications",
    indices = {
        @Index(value = {"targetUid", "communityId"})
    }
)
public class CommunityNotificationEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String targetUid;
    public String communityId;
    public String type;
    public String title;
    public String body;
    public String postId;    // null if not post-related
    public String fromUid;
    public String fromName;
    public String fromPhoto;
    public boolean isRead;
    public long   createdAt;
}
