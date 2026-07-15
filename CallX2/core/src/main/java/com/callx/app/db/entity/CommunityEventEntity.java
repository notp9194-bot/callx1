package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v31: Room entity — community event (calendar + RSVP).
 * Firebase source of truth: communities/{communityId}/events/{id}
 *
 * rsvpJson: JSON Map<uid, "going"|"not_going"> cached locally.
 */
@Entity(
    tableName = "community_events",
    indices = {
        @Index(value = {"communityId", "startTimeMs"})
    }
)
public class CommunityEventEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String communityId;
    public String title;
    public String description;
    public String location;
    public String createdByUid;
    public String createdByName;
    public long   startTimeMs;
    public long   endTimeMs;
    public long   rsvpCount;
    public String rsvpJson;     // JSON Map<uid, status> local cache
    public long   createdAt;
    public long   syncedAt;
}
