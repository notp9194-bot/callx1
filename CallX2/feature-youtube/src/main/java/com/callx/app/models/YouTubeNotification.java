package com.callx.app.models;

public class YouTubeNotification {
    public String notifId;
    public String toUid;
    public String fromUid;
    public String fromName;
    public String fromPhotoUrl;
    public String type;        // "new_video" | "comment" | "reply" | "like" | "subscribe" | "mention" | "live"
    public String videoId;
    public String videoTitle;
    public String thumbnailUrl;
    public String commentText;
    public long   timestamp;
    public boolean read;
    public boolean notified;   // already sent OS notification

    public YouTubeNotification() {}

    public YouTubeNotification(String notifId, String toUid, String fromUid,
                               String fromName, String fromPhotoUrl,
                               String type, String videoId, String videoTitle,
                               String thumbnailUrl) {
        this.notifId       = notifId;
        this.toUid         = toUid;
        this.fromUid       = fromUid;
        this.fromName      = fromName;
        this.fromPhotoUrl  = fromPhotoUrl;
        this.type          = type;
        this.videoId       = videoId;
        this.videoTitle    = videoTitle;
        this.thumbnailUrl  = thumbnailUrl;
        this.timestamp     = System.currentTimeMillis();
        this.read          = false;
        this.notified      = false;
    }
}
