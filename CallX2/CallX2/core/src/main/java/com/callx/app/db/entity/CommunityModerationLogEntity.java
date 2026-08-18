package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v31: Room entity — admin action audit log for a community.
 * Firebase source of truth: communities/{communityId}/moderation_log/{id}
 *
 * action values:
 *   "mute" | "unmute" | "ban" | "unban"
 *   "delete_post" | "report_post"
 *   "make_admin" | "remove_admin"
 *   "approve_join" | "reject_join"
 */
@Entity(
    tableName = "community_moderation_logs",
    indices = {
        @Index(value = {"communityId"})
    }
)
public class CommunityModerationLogEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String communityId;
    public String actionByUid;
    public String actionByName;
    public String targetUid;     // member acted on (null for post actions)
    public String targetName;
    public String action;
    public String reason;        // optional
    public String targetPostId;  // set for delete_post / report_post actions
    public long   createdAt;
}
