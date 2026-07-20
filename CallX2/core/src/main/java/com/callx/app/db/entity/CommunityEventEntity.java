package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v31: Room entity — community event (calendar + RSVP).
 *
 * v34 additions:
 *  - coverImageUrl:   optional banner/cover image for the event card
 *  - interestedCount: members who tapped "Interested" (new 3rd RSVP status)
 *  - notGoingCount:   members who tapped "Not Going"
 *  - eventType:       OFFLINE | ONLINE | HYBRID
 *  - onlineLink:      meeting link for ONLINE/HYBRID events
 *  - endTimeMs:       end time (was missing from v31, now stored)
 *  - reminderSet:     whether current user set a reminder (local-only)
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
    public long   rsvpCount;       // "going" count
    public String rsvpJson;        // JSON Map<uid, "going"|"interested"|"not_going">
    public long   createdAt;
    public long   syncedAt;

    // v34: Cover image
    public String coverImageUrl;   // nullable — Cloudinary URL for event banner

    // v34: Granular RSVP counts
    public long interestedCount;   // "interested" RSVPs
    public long notGoingCount;     // "not_going" RSVPs

    // v34: Event type
    public String eventType;       // "OFFLINE" | "ONLINE" | "HYBRID"
    public String onlineLink;      // nullable — Google Meet / Zoom link

    // v34: Local reminder flag (NOT synced to Firebase — per-device preference)
    public boolean reminderSet;    // true if user set a reminder via WorkManager

    public CommunityEventEntity() {
        this.syncedAt = System.currentTimeMillis();
        this.eventType = "OFFLINE";
    }
}
