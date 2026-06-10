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

    // ── Duet fields ──────────────────────────────────────────────────────────
    public String  allowDuetLevel = "everyone";
    public boolean allowDuet      = true;
    /** If this reel is a duet, the ID of the original reel it was made from. */
    public String  duetOf;
    /** UID of the original reel's creator (for push notification targeting). */
    public String  duetOfOwnerUid;
    /** URL of the original reel video (used by compositor). */
    public String  duetOriginalUrl;
    /**
     * Fix 4 — separate sound URL of the original reel's music track.
     * The compositor reads this to mix the correct music into the duet audio,
     * independent of what audio is embedded in the video file itself.
     */
    public String  duetOriginalSoundUrl;
    /** Running count of duets made on this reel. */
    public int     duetCount;
    /** Layout mode used when this duet was recorded (0=side, 1=top-bottom, 2=pip, 3=bubble). */
    public int     duetLayoutMode;
    /**
     * Fix 10 — Chain Duet support.
     * If this duet was itself made on top of another duet (chain duet),
     * this field holds the root original reel's ID.
     * Populated by DuetReelActivity when dueting a reel whose duetOf != null.
     */
    public String  chainDuetRootId;
    /** Depth of chain: 0 = original, 1 = first duet, 2 = duet-of-duet, etc. */
    public int     chainDuetDepth;

    // ── Stitch fields ────────────────────────────────────────────────────────
    public String  allowStitchLevel = "everyone";
    public boolean allowStitch      = true;
    public String  stitchOf;
    public String  stitchOfOwnerUid;
    public int     stitchCount;

    // ── Invite-to-Duet field ─────────────────────────────────────────────────
    /**
     * Fix 11 — Async / Invite-to-Duet.
     * Set to the UID of the user who was invited to duet this reel (if any).
     * Also stored in duetInvites/{targetUid}/{inviteId} in Firebase.
     */
    public String  invitedDuetUid;

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

    public String effectiveAllowDuetLevel() {
        if (allowDuetLevel != null && !allowDuetLevel.isEmpty()) return allowDuetLevel;
        return allowDuet ? "everyone" : "off";
    }

    public String effectiveAllowStitchLevel() {
        if (allowStitchLevel != null && !allowStitchLevel.isEmpty()) return allowStitchLevel;
        return allowStitch ? "everyone" : "off";
    }

    /** Fix 10: True if this reel is a chain duet (duet-of-a-duet) */
    public boolean isChainDuet() {
        return chainDuetDepth > 1 && chainDuetRootId != null && !chainDuetRootId.isEmpty();
    }

    public float trendingScore() {
        long ageHours = (System.currentTimeMillis() - timestamp) / 3_600_000L;
        float decay   = Math.max(0.05f, 1f - (ageHours / 72f));
        return (likesCount * 3f + commentsCount * 2f + sharesCount * 1.5f
                + repostCount * 2f + viewsCount * 0.1f + duetCount * 1.5f + stitchCount * 1.5f)
               * decay;
    }
}
