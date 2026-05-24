package com.callx.app.models;

public class YouTubeVideo {
    public String  videoId;
    public String  uploaderUid;
    public String  uploaderName;
    public String  uploaderPhotoUrl;
    public String  title;
    public String  description;
    public String  videoUrl;
    public String  thumbnailUrl;
    public String  altThumbnailUrl;   // A/B test thumbnail B
    public String  category;
    public long    duration;          // seconds
    public long    viewCount;
    public long    likeCount;
    public long    dislikeCount;
    public long    commentCount;
    public long    uploadedAt;
    public long    scheduledAt;       // 0 = publish now; >0 = scheduled
    public boolean isShort;
    public String  visibility;        // "public" | "unlisted" | "private" | "scheduled"
    public boolean isLive;
    public String  liveStreamUrl;
    public String  tags;              // comma-separated
    public String  language;
    public boolean madeForKids;
    public boolean ageRestricted;
    public String  locationName;
    public double  locationLat;
    public double  locationLng;
    public long    watchTimeSeconds;  // total aggregated watch time
    public int     relevanceScore;    // transient, not stored in Firebase

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
        this.dislikeCount    = 0;
        this.commentCount    = 0;
        this.uploadedAt      = uploadedAt;
        this.isShort         = isShort;
        this.visibility      = "public";
        this.watchTimeSeconds= 0;
    }
}
