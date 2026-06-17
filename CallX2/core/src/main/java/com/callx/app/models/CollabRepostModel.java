package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * CollabRepostModel — Data model for a Collaborative Repost.
 *
 * A collab repost is a joint repost where two users (initiator + collaborator)
 * both appear as co-authors. Each user adds their own caption.
 * The reel is shown on both profiles once the collaborator accepts.
 *
 * Firebase path: collabReposts/{collabRepostId}
 * Invite path:   collabRepostInvites/{collaboratorUid}/{inviteId}
 */
@IgnoreExtraProperties
public class CollabRepostModel {

    // ── Core IDs ─────────────────────────────────────────────────────────────
    /** Auto-generated Firebase key for this collab repost record. */
    public String collabRepostId;

    /** The original reel being reposted. */
    public String originalReelId;

    /** UID of the original reel's owner. */
    public String originalOwnerUid;

    /** Display name of the original reel's owner. */
    public String originalOwnerName;

    /** Thumbnail URL of the original reel (for preview). */
    public String originalThumbUrl;

    /** Video URL of the original reel. */
    public String originalVideoUrl;

    /** Original reel caption (for display in the collab post). */
    public String originalCaption;

    // ── Initiator (User A — the one who creates the collab repost invite) ────
    public String initiatorUid;
    public String initiatorName;
    public String initiatorPhoto;

    /** Initiator's own caption / comment on the repost. */
    public String initiatorCaption;

    // ── Collaborator (User B — invited to co-author) ─────────────────────────
    public String collaboratorUid;
    public String collaboratorName;
    public String collaboratorPhoto;

    /** Collaborator's own caption / comment on the repost. */
    public String collaboratorCaption;

    // ── Status ────────────────────────────────────────────────────────────────
    /**
     * Status of the collab repost:
     *  "pending"  — invite sent, waiting for collaborator to respond
     *  "accepted" — both accepted; reel appears on both profiles
     *  "declined" — collaborator declined
     *  "cancelled"— initiator cancelled before collaborator responded
     *  "expired"  — collaborator didn't respond within 48 hours
     */
    public String status = "pending";

    // ── Timestamps ────────────────────────────────────────────────────────────
    public long createdAt;
    public long acceptedAt;
    public long declinedAt;
    public long expiresAt;   // createdAt + 48h; expiry enforced by Cloud Function / client

    // ── Result reel ──────────────────────────────────────────────────────────
    /**
     * Once accepted, the new collab repost reel ID written to:
     *  reels/{collabReelId} — with isCollabRepost=true
     *  user_videos/{initiatorUid}/{collabReelId}
     *  user_videos/{collaboratorUid}/{collabReelId}
     */
    public String collabReelId;

    // ── Privacy ───────────────────────────────────────────────────────────────
    /**
     * Audience for the collab repost reel.
     * "everyone" | "followers" | "close_friends"
     * Defaults to "everyone".
     */
    public String audienceType = "everyone";

    // ── Media type ───────────────────────────────────────────────────────────
    /**
     * Whether the original reel is a video or photo slideshow.
     * "video" | "photo_slideshow"
     */
    public String mediaType = "video";

    // ── Flags ─────────────────────────────────────────────────────────────────
    /** True when collaborator has seen the invite (for unread badge). */
    public boolean collaboratorSeen = false;

    /** True when initiator has been notified of accept/decline. */
    public boolean initiatorNotified = false;

    // ── Constructor ───────────────────────────────────────────────────────────
    public CollabRepostModel() {}

    public CollabRepostModel(
            String collabRepostId,
            String originalReelId,
            String originalOwnerUid,
            String originalOwnerName,
            String originalThumbUrl,
            String originalVideoUrl,
            String originalCaption,
            String initiatorUid,
            String initiatorName,
            String initiatorPhoto,
            String initiatorCaption,
            String collaboratorUid,
            String collaboratorName,
            String audienceType,
            String mediaType) {
        this.collabRepostId     = collabRepostId;
        this.originalReelId     = originalReelId;
        this.originalOwnerUid   = originalOwnerUid;
        this.originalOwnerName  = originalOwnerName;
        this.originalThumbUrl   = originalThumbUrl;
        this.originalVideoUrl   = originalVideoUrl;
        this.originalCaption    = originalCaption;
        this.initiatorUid       = initiatorUid;
        this.initiatorName      = initiatorName;
        this.initiatorPhoto     = initiatorPhoto;
        this.initiatorCaption   = initiatorCaption;
        this.collaboratorUid    = collaboratorUid;
        this.collaboratorName   = collaboratorName;
        this.audienceType       = audienceType != null ? audienceType : "everyone";
        this.mediaType          = mediaType    != null ? mediaType    : "video";
        this.status             = "pending";
        this.createdAt          = System.currentTimeMillis();
        this.expiresAt          = this.createdAt + 48L * 3600 * 1000; // 48h
    }

    /** Converts this model to a Firebase-ready map. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("collabRepostId",    collabRepostId);
        m.put("originalReelId",    originalReelId);
        m.put("originalOwnerUid",  originalOwnerUid);
        m.put("originalOwnerName", originalOwnerName   != null ? originalOwnerName   : "");
        m.put("originalThumbUrl",  originalThumbUrl    != null ? originalThumbUrl    : "");
        m.put("originalVideoUrl",  originalVideoUrl    != null ? originalVideoUrl    : "");
        m.put("originalCaption",   originalCaption     != null ? originalCaption     : "");
        m.put("initiatorUid",      initiatorUid        != null ? initiatorUid        : "");
        m.put("initiatorName",     initiatorName       != null ? initiatorName       : "");
        m.put("initiatorPhoto",    initiatorPhoto      != null ? initiatorPhoto      : "");
        m.put("initiatorCaption",  initiatorCaption    != null ? initiatorCaption    : "");
        m.put("collaboratorUid",   collaboratorUid     != null ? collaboratorUid     : "");
        m.put("collaboratorName",  collaboratorName    != null ? collaboratorName    : "");
        m.put("collaboratorPhoto", collaboratorPhoto   != null ? collaboratorPhoto   : "");
        m.put("collaboratorCaption", collaboratorCaption != null ? collaboratorCaption : "");
        m.put("status",            status);
        m.put("createdAt",         createdAt);
        m.put("expiresAt",         expiresAt);
        m.put("audienceType",      audienceType);
        m.put("mediaType",         mediaType);
        m.put("collaboratorSeen",  collaboratorSeen);
        m.put("initiatorNotified", initiatorNotified);
        if (collabReelId != null && !collabReelId.isEmpty())
            m.put("collabReelId", collabReelId);
        if (acceptedAt > 0) m.put("acceptedAt", acceptedAt);
        if (declinedAt > 0) m.put("declinedAt", declinedAt);
        return m;
    }

    /** Returns true if this invite has expired (> 48h without response). */
    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    /** Display label for collab status badge. */
    public String statusLabel() {
        if (isExpired() && "pending".equals(status)) return "EXPIRED";
        switch (status) {
            case "accepted":  return "ACCEPTED";
            case "declined":  return "DECLINED";
            case "cancelled": return "CANCELLED";
            default:          return "PENDING";
        }
    }
}
