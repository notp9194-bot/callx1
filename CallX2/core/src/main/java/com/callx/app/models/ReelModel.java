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
    public String  video480;
    public String  video720;
    public String  video1080;
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

    // ══════════════════════════════════════════════════════════════════════════
    // ── Photo Slideshow fields ─ Ultra-Advanced v5 ───────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Media type: "video" (default) | "photo_slideshow"
     * photo_slideshow = Instagram-style reel made of photos (up to 10).
     */
    public String mediaType = "video";

    /**
     * Ordered list of photo URLs for photo_slideshow type reels.
     * Each photo is displayed according to its per-photo duration (or the global
     * photoDurationMs) with the configured transition before advancing to the next.
     */
    public List<String> photoUrls;

    /**
     * Default duration each photo is shown in milliseconds.
     * Default: 3000ms. Per-photo overrides live in photoDurationList.
     * Minimum: 1000ms. Maximum: 15000ms.
     */
    public int photoDurationMs = 3000;

    /**
     * Per-photo duration overrides in ms (index-matched with photoUrls).
     * A value of 0 or null at an index uses the global photoDurationMs.
     * Enables rhythmic slideshows where some photos linger longer than others.
     */
    public List<Integer> photoDurationList;

    /**
     * Default transition animation between photos.
     * Values: "fade" | "zoom" | "slide" | "cube" | "flip3d" | "carousel"
     *       | "stack" | "parallax" | "blur" | "glitch" | "reveal" | "none"
     * Default: "fade" (cross-fade, like Instagram).
     */
    public String transitionType = "fade";

    /**
     * Per-photo transition overrides (index-matched with photoUrls).
     * Each entry overrides the global transitionType for that specific slide.
     * A null or empty entry at an index uses the global transitionType.
     */
    public List<String> photoTransitionList;

    /**
     * Index of the photo used as the reel cover / thumbnail.
     * Default: 0 (first photo).
     */
    public int coverPhotoIndex = 0;

    /**
     * Default visual color filter applied to every photo in the slideshow.
     * Values: "normal" | "warm" | "cool" | "vivid" | "bw" | "golden_hour"
     *       | "rose" | "sunset" | "neon_pop" | "matrix" | "dream" | "chrome"
     *       | "matte" | "vintage" | "fade" | "noir"
     * Default: "normal".
     */
    public String photoFilter = "normal";

    /**
     * Per-photo color filter overrides (index-matched with photoUrls).
     * A null or "normal" at an index uses the global photoFilter.
     * Enables a gradient of moods across photos in one slideshow.
     */
    public List<String> photoFilterList;

    /**
     * Per-photo visual effect overlays (index-matched with photoUrls).
     * Values: "none" | "vignette" | "grain" | "glitch" | "neon_glow"
     *       | "matte_overlay" | "chrome_leak" | "bokeh" | "scanlines"
     *       | "dust" | "double_exposure"
     * A null or "none" at an index applies no overlay effect.
     */
    public List<String> photoEffectList;

    /**
     * Per-photo caption text (index-matched with photoUrls).
     * A null or empty string means no caption for that photo.
     * Displayed as an animated overlay at the bottom of each slide.
     */
    public List<String> photoCaptions;

    /**
     * Per-photo caption style JSON (index-matched with photoUrls).
     * Format: {"color":"#FFFFFF","bold":false,"italic":false,"size":14,
     *          "font":"sans","align":"start","bg":"#BB000000"}
     * A null entry uses the default caption style.
     */
    public List<String> photoCaptionStyleList;

    /**
     * Per-photo sticker/text overlay JSON array (index-matched with photoUrls).
     * Format per item: [{"type":"emoji","value":"🔥","x":0.5,"y":0.3,"scale":1.2},...]
     * A null entry means no stickers on that photo.
     */
    public List<String> photoStickerJsonList;

    /**
     * Per-photo AR filter / preset names (index-matched with photoUrls).
     * Values: "" | "beauty_v1" | "glam_v2" | "sunset_skin" | "smooth_pro"
     * Applied during upload; stored for re-processing.
     */
    public List<String> photoArFilterList;

    /**
     * Per-photo crop rect JSON (index-matched with photoUrls).
     * Format: {"left":0.0,"top":0.0,"right":1.0,"bottom":1.0,"rotation":0}
     * Values are fractions of the original image dimensions (0.0–1.0).
     * A null entry means no crop (full image shown with centerCrop).
     */
    public List<String> photoCropList;

    /**
     * When true, beat-sync is enabled: the slideshow auto-advance fires on
     * detected music beat intervals. Requires musicId or musicUrl to be set.
     * The beat interval is estimated server-side and stored in beatIntervalMs.
     * Default: false.
     */
    public boolean photoBeatSync = false;

    /**
     * Estimated beat interval in milliseconds derived from the track's BPM.
     * Used when photoBeatSync == true to drive auto-advance timing.
     * 0 means not computed (falls back to photoDurationMs).
     */
    public int beatIntervalMs = 0;

    /**
     * When true the slideshow loops back to photo 1 after the last photo.
     * Default: false (stops on last photo and freezes there).
     */
    public boolean autoLoop = false;

    /**
     * Show Instagram-style dot page indicator below photos. Default: true.
     */
    public boolean showDotIndicator = true;

    /**
     * Show story-style segmented progress bar at the top. Default: true.
     */
    public boolean showStoryProgress = true;

    /**
     * Show photo counter badge "N / total" at top-right. Default: true.
     */
    public boolean showPhotoCounter = true;

    /**
     * Enable pinch-to-zoom interaction on individual photos. Default: true.
     */
    public boolean allowPinchZoom = true;

    /**
     * Enable double-tap zoom on individual photos. Default: true.
     */
    public boolean allowDoubleTapZoom = true;

    /**
     * Max zoom scale for pinch/double-tap. Default: 3.0x. Min: 1.5.
     */
    public float maxZoomScale = 3.0f;

    /**
     * Ken Burns motion direction preset per photo: "tl_br" | "tr_bl" | "center_out"
     * | "bottom_up" | "random" (default). Index-matched with photoUrls.
     * Controls the pan direction of the Ken Burns effect for each slide.
     */
    public List<String> photoKenBurnsDirectionList;

    /**
     * Ken Burns zoom intensity: "subtle" (1.05x) | "normal" (1.14x) |
     * "dramatic" (1.25x) | "cinematic" (1.35x). Default: "normal".
     */
    public String kenBurnsIntensity = "normal";

    /**
     * Computed total slideshow duration in milliseconds.
     * Sum of per-photo durations (or global duration × photo count).
     * Stored for display in the feed and progress bar setup.
     */
    public int totalSlideshowDurationMs = 0;

    /**
     * Number of times this photo slideshow has been fully viewed
     * (user watched all photos at least once). Tracked separately from viewsCount.
     */
    public int slideshowCompletionCount = 0;

    /**
     * Average completion rate: 0.0–1.0. Computed server-side from analytics.
     * 1.0 = all viewers watched every photo.
     */
    public float avgCompletionRate = 0f;

    /**
     * Aspect ratio of the photo slideshow: "9:16" (full-screen, default) |
     * "4:5" (portrait) | "1:1" (square). Determines ViewPager2 layout.
     */
    public String slideshowAspectRatio = "9:16";

    // ══════════════════════════════════════════════════════════════════════════
    // ── Photo Slideshow v6 extras ─────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Enable breathing/pulse animation on each photo (gentle 1.0↔1.025 scale loop).
     * Default: false. Set to true by story templates that call for it.
     */
    public boolean photoPulseAnimation = false;

    /**
     * ID of the story template last applied to this slideshow (e.g. "travel", "hype").
     * Null if no template has been applied.
     * Stored so the UI can show which template is active.
     */
    public String slideshowTemplateName;

    /**
     * Estimated BPM of the background music track.
     * Computed via tap-tempo (ReelPhotoBeatSyncController) or server-side audio analysis.
     * 0 = not computed. Used together with photoBeatSync + beatIntervalMs.
     */
    public float musicBpm = 0f;

    /**
     * Per-slide collage layout override (index-matched with photoUrls when using
     * multi-photo collage slides). Values from ReelPhotoCollageView.Layout:
     * "single" | "split_h" | "split_v" | "triptych" | "grid_2x2"
     * | "featured_left" | "featured_right" | "wide_top"
     * A null entry means the global collageLayout applies.
     */
    public List<String> photoCollageLayoutList;

    /**
     * Global default collage layout for all slides. "single" = normal 1-photo slide.
     * Default: "single".
     */
    public String collageLayout = "single";

    /**
     * When true, each collage slide shows multiple photos from photoUrls in a grid.
     * The photos consumed per slide depends on the collageLayout (e.g. grid_2x2 = 4 photos).
     * Default: false (normal 1-photo-per-slide slideshow).
     */
    public boolean collageModeEnabled = false;

    // ══════════════════════════════════════════════════════════════════════════
    // ── Duet fields ──────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════
    public String  allowDuetLevel = "everyone";
    public boolean allowDuet      = true;
    public String  duetOf;
    public String  duetRootId;
    public String  duetOfOwnerUid;
    public String  duetOriginalUrl;
    public int     duetCount;
    public int     duetLayoutMode;

    // ── Stitch fields ─────────────────────────────────────────────────────────
    public String  allowStitchLevel = "everyone";
    public boolean allowStitch      = true;
    public String  stitchOf;
    public String  stitchOfOwnerUid;
    public int     stitchCount;

    // ── Duet Series fields ────────────────────────────────────────────────────
    public String seriesId;
    public int    seriesEpisodeNumber;
    public String seriesTitle;

    // ── Multi-Duet fields ─────────────────────────────────────────────────────
    /** Firebase key of the multi_duet_sessions node this reel belongs to. */
    public String multiDuetSessionId;
    /** Slot index (0 = host, 1-3 = participants) in the multi-duet grid. */
    public int    multiDuetSlot;

    // ══════════════════════════════════════════════════════════════════════════
    // ── Collab Repost fields ──────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * True when this reel is a collaborative repost (created by CollabRepostAcceptActivity).
     * Both co-authors' profiles show this reel. Default: false.
     */
    public boolean isCollabRepost = false;

    /**
     * Firebase key of the CollabRepostModel record that produced this reel.
     * Path: collabReposts/{collabRepostId}
     * Allows linking back to the invite / acceptance record.
     */
    public String collabRepostId;

    /**
     * UID of the user who initiated (sent) the collab repost invite.
     * On the published reel, this user is shown as the primary co-author.
     */
    public String collabInitiatorUid;

    /** Display name of the collab repost initiator. */
    public String collabInitiatorName;

    /** Profile photo URL of the collab repost initiator (for in-feed avatar). */
    public String collabInitiatorPhoto;

    /**
     * Initiator's own caption / commentary on this collab repost.
     * Shown beneath the initiator's avatar on the reel card.
     */
    public String collabInitiatorCaption;

    /**
     * UID of the user who accepted the collab repost invite (the collaborator).
     * Note: field name intentionally spelled "Colaborator" (one 'l') to match
     * legacy data written by CollabRepostAcceptActivity v1; both spellings are
     * accepted by @IgnoreExtraProperties.
     */
    public String collabColaboratorUid;

    /** Display name of the collab repost collaborator. */
    public String collabCollaboratorName;

    /** Profile photo URL of the collab repost collaborator. */
    public String collabCollaboratorPhoto;

    /**
     * Collaborator's own caption / commentary on this collab repost.
     * Shown beneath the collaborator's avatar on the reel card.
     */
    public String collabCollaboratorCaption;

    /**
     * The original reel's caption — preserved here for display inside the
     * collab repost card without requiring a secondary Firestore read.
     */
    public String originalCaption;

    /**
     * Number of times this reel has been collab-reposted by pairs of users.
     * Incremented atomically by CollabRepostAcceptActivity on each acceptance.
     * Default: 0.
     */
    public int collabRepostCount;

    /**
     * Combined (joint) caption shown at the top of the reel post.
     * Built by CollabRepostAcceptActivity.buildJointCaption():
     *   "initiatorCaption | collaboratorCaption"
     * If only one caption was provided, only that caption is stored.
     * Falls back to originalCaption if both users left their captions empty.
     */
    // Note: joint caption is already stored in the `caption` field.
    // These sub-captions allow UIs to display each author's caption separately.

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

    /**
     * Returns the effective display duration in ms for the photo at the given index.
     * Respects per-photo override in photoDurationList; falls back to the global
     * photoDurationMs. Also handles beat-sync override via beatIntervalMs.
     */
    public int effectiveDurationForPhoto(int index) {
        if (photoBeatSync && beatIntervalMs > 0) return beatIntervalMs;
        if (photoDurationList != null && index >= 0 && index < photoDurationList.size()) {
            Integer perPhoto = photoDurationList.get(index);
            if (perPhoto != null && perPhoto > 0) return perPhoto;
        }
        return photoDurationMs > 0 ? photoDurationMs : 3000;
    }

    /**
     * Returns the effective transition type for the photo at the given index.
     * Respects per-photo override in photoTransitionList; falls back to transitionType.
     */
    public String effectiveTransitionForPhoto(int index) {
        if (photoTransitionList != null && index >= 0 && index < photoTransitionList.size()) {
            String t = photoTransitionList.get(index);
            if (t != null && !t.isEmpty()) return t;
        }
        return transitionType != null ? transitionType : "fade";
    }

    /**
     * Returns the effective color filter for the photo at the given index.
     * Respects per-photo override in photoFilterList; falls back to photoFilter.
     */
    public String effectiveFilterForPhoto(int index) {
        if (photoFilterList != null && index >= 0 && index < photoFilterList.size()) {
            String f = photoFilterList.get(index);
            if (f != null && !f.isEmpty()) return f;
        }
        return photoFilter != null ? photoFilter : "normal";
    }

    /**
     * Returns the effective visual effect for the photo at the given index.
     */
    public String effectiveEffectForPhoto(int index) {
        if (photoEffectList != null && index >= 0 && index < photoEffectList.size()) {
            String e = photoEffectList.get(index);
            if (e != null && !e.isEmpty()) return e;
        }
        return "none";
    }

    /**
     * Returns the caption for the photo at the given index, or null if none.
     */
    public String captionForPhoto(int index) {
        if (photoCaptions != null && index >= 0 && index < photoCaptions.size()) {
            return photoCaptions.get(index);
        }
        return null;
    }

    /**
     * Returns the sticker JSON for the photo at the given index, or null if none.
     */
    public String stickerJsonForPhoto(int index) {
        if (photoStickerJsonList != null && index >= 0 && index < photoStickerJsonList.size()) {
            return photoStickerJsonList.get(index);
        }
        return null;
    }

    /**
     * Returns the Ken Burns direction for the photo at the given index.
     */
    public String kenBurnsDirectionForPhoto(int index) {
        if (photoKenBurnsDirectionList != null && index >= 0
                && index < photoKenBurnsDirectionList.size()) {
            String d = photoKenBurnsDirectionList.get(index);
            if (d != null && !d.isEmpty()) return d;
        }
        return "random";
    }

    /**
     * Returns the Ken Burns scale target based on kenBurnsIntensity setting.
     */
    public float kenBurnsScaleTarget() {
        if (kenBurnsIntensity == null) return 1.14f;
        switch (kenBurnsIntensity) {
            case "subtle":    return 1.05f;
            case "dramatic":  return 1.25f;
            case "cinematic": return 1.35f;
            default:          return 1.14f; // "normal"
        }
    }

    /**
     * Recomputes and stores totalSlideshowDurationMs from per-photo durations.
     * Call after changing photoDurationList or photoDurationMs.
     */
    public void recomputeTotalDuration() {
        if (photoUrls == null || photoUrls.isEmpty()) { totalSlideshowDurationMs = 0; return; }
        int total = 0;
        for (int i = 0; i < photoUrls.size(); i++) {
            total += effectiveDurationForPhoto(i);
        }
        totalSlideshowDurationMs = total;
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
