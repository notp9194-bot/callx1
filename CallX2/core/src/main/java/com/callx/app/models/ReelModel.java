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
    public String  musicCoverUrl;
    public String  musicArtist;
    public int     musicStartSec;
    public String  originalAudioUrl;
    public String  thumbnailUrl;
    public long    timestamp;
    public int     duration;
    public int     width;
    public int     height;
    public int     likesCount;
    public int     commentsCount;
    public int     sharesCount;
    public int     viewsCount;
    public int     repostCount;
    public String  compressionSummary;
    public float   savingsPercent;
    public String  audienceType;
    public List<String>         hashtags;
    public Map<String, Integer> reactions;
    public boolean isVerified;
    public boolean allowReposts  = true;
    public String  repostCaption;
    public String  repostedFromReelId;
    public String  repostedFromUid;
    public String  repostedFromName;

    // ── Photo Slideshow fields ────────────────────────────────────────────────
    /**
     * Media type: "video" (default) | "photo_slideshow"
     * photo_slideshow = Instagram-style reel made of photos (up to 10).
     */
    public String mediaType = "video";
    /**
     * Ordered list of photo URLs for photo_slideshow type reels.
     * Each photo is displayed for photoDurationMs milliseconds with
     * a cross-fade transition before advancing to the next.
     */
    public List<String> photoUrls;
    /**
     * Duration each photo is shown in milliseconds.
     * Default: 3000ms (3 seconds per photo), min 1s, max 10s.
     */
    public int photoDurationMs = 3000;
    /**
     * Transition animation between photos: "fade" | "slide" | "zoom" | "none".
     * Default: "fade" (cross-fade, like Instagram).
     */
    public String transitionType = "fade";
    /**
     * Index of the photo used as the reel cover / thumbnail.
     * Default: 0 (first photo). Stored so feed can show the chosen cover.
     */
    public int coverPhotoIndex = 0;
    /**
     * Visual color filter applied to every photo in the slideshow.
     * Values: "normal" | "warm" | "cool" | "vivid" | "bw". Default: "normal".
     */
    public String photoFilter = "normal";
    /**
     * Optional per-photo captions (index-matched with photoUrls).
     * A null list or a null/empty string at an index means no caption for that photo.
     */
    public java.util.List<String> photoCaptions;
    /** When true the slideshow loops back to photo 1 after the last photo. Default: false. */
    public boolean autoLoop = false;
    /** Show Instagram-style dot indicator below photos. Default: true. */
    public boolean showDotIndicator = true;

    // ── Duet fields ──────────────────────────────────────────────────────────
    public String  allowDuetLevel = "everyone";
    public boolean allowDuet      = true;
    public String  duetOf;
    public String  duetRootId;
    public String  duetOfOwnerUid;
    public String  duetOriginalUrl;
    public int     duetCount;
    public int     duetLayoutMode;

    // ── Stitch fields ────────────────────────────────────────────────────────
    public String  allowStitchLevel = "everyone";
    public boolean allowStitch      = true;
    public String  stitchOf;
    public String  stitchOfOwnerUid;
    public int     stitchCount;

    // ── Duet Series fields ───────────────────────────────────────────────────
    public String seriesId;
    public int    seriesEpisodeNumber;
    public String seriesTitle;

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
        this.mediaType    = "video";
        this.hashtags     = extractHashtags(caption);
        this.reactions    = new HashMap<>();
    }

    /** Convenience: returns true if this reel is a photo slideshow. */
    public boolean isPhotoSlideshow() {
        return "photo_slideshow".equals(mediaType) && photoUrls != null && !photoUrls.isEmpty();
    }

    public static List<String> extractHashtags(String text) {
        List<String> tags = new ArrayList<>();
        if (text == null || text.isEmpty()) return tags;
        Matcher m = Pattern.compile("#(\\w+)").matcher(text);
        while (m.find()) tags.add(m.group(1).toLowerCase());
        return tags;
    }

    public String effectiveAllowDuetLevel() {
        if (allowDuetLevel != null && !allowDuetLevel.isEmpty()) return allowDuetLevel;
        return allowDuet ? "everyone" : "off";
    }

    public String effectiveAllowStitchLevel() {
        if (allowStitchLevel != null && !allowStitchLevel.isEmpty()) return allowStitchLevel;
        return allowStitch ? "everyone" : "off";
    }

    public float trendingScore() {
        long ageHours = (System.currentTimeMillis() - timestamp) / 3_600_000L;
        float decay   = Math.max(0.05f, 1f - (ageHours / 72f));
        return (likesCount * 3f + commentsCount * 2f + sharesCount * 1.5f
                + repostCount * 2f + viewsCount * 0.1f + duetCount * 1.5f + stitchCount * 1.5f)
               * decay;
    }
}
