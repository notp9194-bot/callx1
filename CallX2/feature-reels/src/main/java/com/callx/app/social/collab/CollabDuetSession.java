package com.callx.app.social.collab;

/**
 * CollabDuetSession — Firebase RTDB model for a live collab-duet session.
 *
 * Path: collabDuetSessions/{sessionId}
 *
 * State machine:
 *   WAITING   → host created, waiting for partner to join
 *   BOTH_READY→ both tapped "Ready", countdown starts
 *   RECORDING → both recording simultaneously
 *   DONE      → both recordings uploaded; compositor queued
 *   DECLINED  → partner declined the invite
 */
public class CollabDuetSession {

    // Status constants
    public static final String STATUS_WAITING    = "waiting";
    public static final String STATUS_BOTH_READY = "both_ready";
    public static final String STATUS_RECORDING  = "recording";
    public static final String STATUS_DONE       = "done";
    public static final String STATUS_DECLINED   = "declined";

    // Session metadata
    public String sessionId;
    public String status         = STATUS_WAITING;
    public long   createdAt      = 0;

    // Original reel
    public String reelId;
    public String reelVideoUrl;
    public String reelThumbUrl;
    public String reelCaption;

    // Host (the user who initiated the collab duet)
    public String hostUid;
    public String hostName;
    public String hostPhoto;
    public boolean hostReady     = false;
    public String hostVideoUrl;  // Firebase Storage URL after upload

    // Partner (the user who was invited)
    public String partnerUid;
    public String partnerName;
    public String partnerPhoto;
    public boolean partnerReady  = false;
    public String partnerVideoUrl;

    // Synchronized start: both clients use this millis timestamp to start simultaneously
    public long startAtMillis    = 0;

    // Duration of the duet recording (ms)
    public long durationMs       = 0;

    // Final composited reel ID (written by CompositorWorker after mixing)
    public String compositedReelId;

    public CollabDuetSession() {}

    public boolean isHost(String uid) { return uid != null && uid.equals(hostUid); }
    public boolean isPartner(String uid) { return uid != null && uid.equals(partnerUid); }
    public boolean bothReady() { return hostReady && partnerReady; }
    public boolean bothUploaded() {
        return hostVideoUrl != null && !hostVideoUrl.isEmpty()
            && partnerVideoUrl != null && !partnerVideoUrl.isEmpty();
    }
}
