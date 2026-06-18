package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class RepostModel {
    public String repostId;
    public String reelId;
    public String reposterId;
    public String reposterName;
    public String reposterPhoto;
    public String caption;
    public long timestamp;
    public String repostType;   // "simple" | "quote" | "story"
    public String quoteVideoUrl;
    public String quoteThumb;
    public boolean isScheduled;
    public long scheduledAt;
    public int repostCount;

    public RepostModel() {}

    public RepostModel(String reelId, String reposterId, String reposterName,
                       String reposterPhoto, String caption, String repostType) {
        this.reelId        = reelId;
        this.reposterId    = reposterId;
        this.reposterName  = reposterName;
        this.reposterPhoto = reposterPhoto;
        this.caption       = caption;
        this.repostType    = repostType;
        this.timestamp     = System.currentTimeMillis();
    }
}
