package com.callx.app.social;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * v790 — The post a Remix / Collab is built on. Travels between the screens of the flow as intent extras:
 * Entry (3-dot) → Choose Mode → Select Post → Request Permission → Request Sent → Recorder / Editor.
 */
public final class RemixCollabTarget {
    public static final String X_REEL_ID    = "rc_reel_id";
    public static final String X_OWNER_UID  = "rc_owner_uid";
    public static final String X_OWNER_NAME = "rc_owner_name";
    public static final String X_OWNER_PHOTO= "rc_owner_photo";
    public static final String X_VIDEO_URL  = "rc_video_url";
    public static final String X_THUMB_URL  = "rc_thumb_url";
    public static final String X_MODE       = "rc_mode";          // "remix" | "collab"
    public static final String X_REQUEST_ID = "rc_request_id";

    public static final String MODE_REMIX  = "remix";
    public static final String MODE_COLLAB = "collab";

    public String reelId = "", ownerUid = "", ownerName = "", ownerPhoto = "", videoUrl = "", thumbUrl = "";

    public RemixCollabTarget() {}

    public RemixCollabTarget(@Nullable String reelId, @Nullable String ownerUid, @Nullable String ownerName,
                             @Nullable String ownerPhoto, @Nullable String videoUrl, @Nullable String thumbUrl) {
        this.reelId = nz(reelId);
        this.ownerUid = nz(ownerUid);
        this.ownerName = nz(ownerName);
        this.ownerPhoto = nz(ownerPhoto);
        this.videoUrl = nz(videoUrl);
        this.thumbUrl = nz(thumbUrl);
    }

    @NonNull
    public static RemixCollabTarget from(@NonNull Intent i) {
        return new RemixCollabTarget(
                i.getStringExtra(X_REEL_ID), i.getStringExtra(X_OWNER_UID), i.getStringExtra(X_OWNER_NAME),
                i.getStringExtra(X_OWNER_PHOTO), i.getStringExtra(X_VIDEO_URL), i.getStringExtra(X_THUMB_URL));
    }

    @NonNull
    public Intent put(@NonNull Intent i) {
        i.putExtra(X_REEL_ID, reelId);
        i.putExtra(X_OWNER_UID, ownerUid);
        i.putExtra(X_OWNER_NAME, ownerName);
        i.putExtra(X_OWNER_PHOTO, ownerPhoto);
        i.putExtra(X_VIDEO_URL, videoUrl);
        i.putExtra(X_THUMB_URL, thumbUrl);
        return i;
    }

    public boolean isValid() { return !reelId.isEmpty() && !ownerUid.isEmpty(); }

    private static String nz(String s) { return s == null ? "" : s; }
}
