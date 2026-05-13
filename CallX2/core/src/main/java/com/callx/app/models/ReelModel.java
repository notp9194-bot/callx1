package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@IgnoreExtraProperties
public class ReelModel {
    public String  reelId;
    public String  uid;
    public String  ownerName;
    public String  ownerPhoto;
    public String  videoUrl;
    public String  thumbUrl;
    public String  caption;
    public String  musicName;
    public String  musicId;
    public String  musicUrl;
    public String  thumbnailUrl;
    public long    timestamp;
    public int     duration;
    public int     width;
    public int     height;
    public int     likesCount;
    public int     commentsCount;
    public int     sharesCount;
    public int     viewsCount;
    /** FIX #5: Number of times this reel has been reposted by other users. */
    public int     repostCount;
    public String  compressionSummary;
    public float   savingsPercent;
    public String  audienceType;
    public List<String>         hashtags;
    public Map<String, Integer> reactions;
    public boolean isVerified;
    /** Privacy: creator can set false to block reposts. Default true. */
    public boolean allowReposts  = true;
    /** Set when reel was originally reposted by current user (UI state cache). */
    public String  repostCaption;
    /** If this feed entry is a repost, the original reel's ID. */
    public String  repostedFromReelId;
    /** If this feed entry is a repost, the original creator's UID. */
    public String  repostedFromUid;
    /** Display name of original creator — used for attribution banner. */
    public String  repostedFromName;

    public ReelModel() {}

    public ReelModel(String reelId, String uid, String ownerName, String ownerPhoto,
                     String videoUrl, String thumbUrl, String caption, String musicName,
                     long timestamp, int duration, int width, int height) {
        this.reelId       = reelId;
        this.uid          = uid;
        this.ownerName    = ownerName;
        this.ownerPhoto   = ownerPhoto;
        this.videoUrl     = videoUrl;
        this.thumbUrl     = thumbUrl;
        this.caption      = caption;
        this.musicName    = musicName;
        this.timestamp    = timestamp;
        this.duration     = duration;
        this.width        = width;
        this.height       = height;
        this.repostCount  = 0;
        this.audienceType = "everyone";
        this.hashtags     = extractHashtags(caption);
        this.reactions    = new HashMap<>();
    }

    public static List<String> extractHashtags(String text) {
        List<String> tags = new ArrayList<>();
        if (text == null || text.isEmpty()) return tags;
        Matcher m = Pattern.compile("#(\\w+)").matcher(text);
        while (m.find()) tags.add(m.group(1).toLowerCase());
        return tags;
    }

    public float trendingScore() {
        long ageHours = (System.currentTimeMillis() - timestamp) / 3_600_000L;
        float decay   = Math.max(0.05f, 1f - (ageHours / 72f));
        return (likesCount * 3f + commentsCount * 2f + sharesCount * 1.5f
                + repostCount * 2f + viewsCount * 0.1f) * decay;
    }
}
