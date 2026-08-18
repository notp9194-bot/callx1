package com.callx.app.social;

import com.google.firebase.database.IgnoreExtraProperties;

/**
 * ReelRemixModel — Firebase RTDB model for a reel remix entry
 *
 * Firebase path: reelRemixes/{originalReelId}/{remixReelId}
 *
 * Fields:
 *  remixReelId       — Firebase key of the published remix reel
 *  originalReelId    — Firebase key of the reel that was remixed
 *  originalOwnerUid  — UID of the original reel creator
 *  originalOwnerName — Display name of original creator
 *  remixerUid        — UID of user who created this remix
 *  remixerName       — Display name of remixer
 *  remixerPhoto      — Avatar URL of remixer
 *  remixVideoUrl     — Final composite video URL
 *  remixThumbUrl     — Thumbnail URL of remix
 *  remixCaption      — Remixer's caption
 *  layoutMode        — "side_by_side" | "react_cam" | "green_screen" | "overlay"
 *  timestamp         — Unix ms when remix was published
 *  likesCount        — Like count on the remix itself
 *  viewsCount        — View count on the remix
 */
@IgnoreExtraProperties
public class ReelRemixModel {

    public String remixReelId;
    public String originalReelId;
    public String originalOwnerUid;
    public String originalOwnerName;
    public String originalThumbUrl;
    public String originalVideoUrl;

    public String remixerUid;
    public String remixerName;
    public String remixerPhoto;

    public String remixVideoUrl;
    public String remixThumbUrl;
    public String remixCaption;

    /** Layout mode: "side_by_side" | "react_cam" | "green_screen" | "overlay" */
    public String layoutMode = "side_by_side";

    public long   timestamp;
    public int    likesCount;
    public int    viewsCount;
    public int    commentsCount;

    /** True if the original reel creator has approved this remix */
    public boolean approved = true;

    public ReelRemixModel() {}

    public ReelRemixModel(String remixReelId, String originalReelId,
                          String originalOwnerUid, String originalOwnerName,
                          String originalThumbUrl, String originalVideoUrl,
                          String remixerUid, String remixerName, String remixerPhoto,
                          String remixVideoUrl, String remixThumbUrl,
                          String remixCaption, String layoutMode) {
        this.remixReelId       = remixReelId;
        this.originalReelId    = originalReelId;
        this.originalOwnerUid  = originalOwnerUid;
        this.originalOwnerName = originalOwnerName;
        this.originalThumbUrl  = originalThumbUrl;
        this.originalVideoUrl  = originalVideoUrl;
        this.remixerUid        = remixerUid;
        this.remixerName       = remixerName;
        this.remixerPhoto      = remixerPhoto;
        this.remixVideoUrl     = remixVideoUrl;
        this.remixThumbUrl     = remixThumbUrl;
        this.remixCaption      = remixCaption;
        this.layoutMode        = layoutMode;
        this.timestamp         = System.currentTimeMillis();
        this.approved          = true;
    }
}
