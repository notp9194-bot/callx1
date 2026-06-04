package com.callx.app.youtube.core.models;

public class YouTubeVideo {
    public String videoId;
    public String uploaderUid;
    public String uploaderName;
    public String uploaderPhotoUrl;
    public String title;
    public String description;
    public String videoUrl;        // Cloudinary video URL
    public String thumbnailUrl;    // Cloudinary thumbnail URL
    public String category;
    public long   duration;        // seconds
    public long   viewCount;
    public long   likeCount;
    public long   commentCount;
    public long   uploadedAt;
    public boolean isShort;          // true = YouTube Short (<=60s vertical)
    public boolean isAgeRestricted;  // true = mature/age-restricted content
    public String  visibility;     // "public", "unlisted", "private"
    public boolean isLive;
    public String  liveStreamUrl;
    public String  tags;           // comma-separated

    public YouTubeVideo() {}

    public YouTubeVideo(String videoId, String uploaderUid, String uploaderName,
                        String uploaderPhotoUrl, String title, String description,
                        String videoUrl, String thumbnailUrl, String category,
                        long duration, long uploadedAt, boolean isShort) {
        this.videoId         = videoId;
        this.uploaderUid     = uploaderUid;
        this.uploaderName    = uploaderName;
        this.uploaderPhotoUrl = uploaderPhotoUrl;
        this.title           = title;
        this.description     = description;
        this.videoUrl        = videoUrl;
        this.thumbnailUrl    = thumbnailUrl;
        this.category        = category;
        this.duration        = duration;
        this.viewCount       = 0;
        this.likeCount       = 0;
        this.commentCount    = 0;
        this.uploadedAt      = uploadedAt;
        this.isShort         = isShort;
        this.visibility      = "public";
    }
}
