package com.callx.app.models;

import java.util.HashMap;
import java.util.Map;

public class YouTubeVideo {
    public String videoId;
    public String uploaderUid;
    public String uploaderName;
    public String uploaderPhotoUrl;
    public String title;
    public String description;
    public String videoUrl;
    public String thumbnailUrl;
    public String category;
    public long   duration;
    public long   viewCount;
    public long   likeCount;
    public long   dislikeCount;
    public long   commentCount;
    public long   shareCount;
    public long   savedCount;
    public long   uploadedAt;
    public boolean isShort;
    public boolean isAgeRestricted;
    public boolean isLive;
    public boolean isMonetized;
    public String  visibility;   // "public" | "unlisted" | "private"
    public String  liveStreamUrl;
    public String  tags;
    public String  location;
    public String  language;
    public double  trendingScore; // computed: (viewCount * 2 + likeCount * 5 + commentCount * 3) / hoursOld
    public Map<String, String> qualityUrls; // "360p" -> url, "720p" -> url, "1080p" -> url
    public Map<String, Long>  chapters;     // "chapterTitle" -> startTimeMs

    public YouTubeVideo() {}

    public YouTubeVideo(String videoId, String uploaderUid, String uploaderName,
                        String uploaderPhotoUrl, String title, String description,
                        String videoUrl, String thumbnailUrl, String category,
                        long duration, long uploadedAt, boolean isShort) {
        this.videoId          = videoId;
        this.uploaderUid      = uploaderUid;
        this.uploaderName     = uploaderName;
        this.uploaderPhotoUrl = uploaderPhotoUrl;
        this.title            = title;
        this.description      = description;
        this.videoUrl         = videoUrl;
        this.thumbnailUrl     = thumbnailUrl;
        this.category         = category;
        this.duration         = duration;
        this.viewCount        = 0;
        this.likeCount        = 0;
        this.dislikeCount     = 0;
        this.commentCount     = 0;
        this.shareCount       = 0;
        this.savedCount       = 0;
        this.uploadedAt       = uploadedAt;
        this.isShort          = isShort;
        this.visibility       = "public";
        this.trendingScore    = 0;
    }

    /** Recompute trendingScore based on engagement + recency. Call after upload/update. */
    public double computeTrendingScore() {
        long hoursOld = Math.max(1, (System.currentTimeMillis() - uploadedAt) / 3_600_000L);
        trendingScore = (viewCount * 2.0 + likeCount * 5.0 + commentCount * 3.0
                        + shareCount * 4.0 + savedCount * 2.0) / hoursOld;
        return trendingScore;
    }
}
