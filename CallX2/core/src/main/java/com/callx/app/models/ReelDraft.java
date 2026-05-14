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
