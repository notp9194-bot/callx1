package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class CollabModel {
    public String collabId;
    public String reelId;
    public String ownerUid;
    public String ownerName;
    public String ownerPhoto;
    public String inviteeUid;
    public String inviteeName;
    public String inviteePhoto;
    public String status;        // "pending" | "accepted" | "rejected" | "cancelled"
    public long invitedAt;
    public long respondedAt;
    public String seriesId;
    public String seriesTitle;

    public static final String STATUS_PENDING   = "pending";
    public static final String STATUS_ACCEPTED  = "accepted";
    public static final String STATUS_REJECTED  = "rejected";
    public static final String STATUS_CANCELLED = "cancelled";

    public CollabModel() {}

    public CollabModel(String reelId, String ownerUid, String ownerName, String ownerPhoto,
                       String inviteeUid, String inviteeName, String inviteePhoto) {
        this.reelId       = reelId;
        this.ownerUid     = ownerUid;
        this.ownerName    = ownerName;
        this.ownerPhoto   = ownerPhoto;
        this.inviteeUid   = inviteeUid;
        this.inviteeName  = inviteeName;
        this.inviteePhoto = inviteePhoto;
        this.status       = STATUS_PENDING;
        this.invitedAt    = System.currentTimeMillis();
    }
}
