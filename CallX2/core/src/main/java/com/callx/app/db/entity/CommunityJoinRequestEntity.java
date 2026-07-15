package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v31: Room entity — community join request (private community approval flow).
 * Firebase source of truth: communities/{communityId}/join_requests/{id}
 *
 * status: "pending" | "approved" | "rejected"
 */
@Entity(
    tableName = "community_join_requests",
    indices = {
        @Index(value = {"communityId", "status"})
    }
)
public class CommunityJoinRequestEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String communityId;
    public String requesterUid;
    public String requesterName;
    public String requesterPhoto;
    public String status;        // "pending" | "approved" | "rejected"
    public String message;       // optional message from the requester
    public long   createdAt;
    public long   processedAt;
    public String processedByUid;
}
