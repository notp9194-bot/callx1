package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class ReelDraft {
    public String draftId;
    public String videoUri;
    public String thumbUrl;
    public String caption;
    public String musicName;
    public String audienceType;
    public long   trimStartMs;
    public long   trimEndMs;
    public long   timestamp;
    // Filter + text/sticker overlay state — mirrors ReelEditorActivity's
    // EXTRA_PRESET_FILTER_*/EXTRA_PRESET_STICKERS_JSON camera-preset extras,
    // so resuming a draft reuses that same restore path unchanged.
    public String filterName       = "";
    public float  filterBrightness = 0f;
    public float  filterContrast   = 1f;
    public float  filterSaturation = 1f;
    public float  filterBeauty     = 0f;
    public String stickerJson      = "";

    public ReelDraft() {}

    public ReelDraft(String videoUri, String caption, String musicName,
                     long trimStart, long trimEnd) {
        this.videoUri     = videoUri;
        this.caption      = caption;
        this.musicName    = musicName;
        this.trimStartMs  = trimStart;
        this.trimEndMs    = trimEnd;
        this.audienceType = "everyone";
        this.timestamp    = System.currentTimeMillis();
    }
}
