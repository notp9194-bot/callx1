package com.callx.app.models;

import com.google.firebase.database.Exclude;
import com.google.firebase.database.IgnoreExtraProperties;
import com.google.firebase.database.ServerValue;
import java.util.*;

/**
 * StatusItem v26 — Added geo-fence fields, viewDurations, music fields,
 * poll/countdown/collage support, toMap(), getAvgViewDurationSec().
 */
@IgnoreExtraProperties
public class StatusItem {
    // ── Core ─────────────────────────────────────────────
    public String  id;
    public String  ownerUid;
    public String  ownerName;
    public String  ownerPhotoUrl;
    public String  type;           // text|image|video|link|gif|sticker|reel|collage
    public boolean deleted;

    // ── Content ───────────────────────────────────────────
    public String  text;
    public String  caption;
    public String  mediaUrl;
    public String  thumbnailUrl;

    // ── Link preview ─────────────────────────────────────
    public String  linkUrl;
    public String  linkTitle;
    public String  linkDescription;
    public String  linkImageUrl;
    public String  linkDomain;
    public String  linkFaviconUrl;

    // ── Text styling ──────────────────────────────────────
    public String  bgColor;
    public String  bgColor2;          // gradient end color
    public List<String> gradientColors;
    public String  textColor;
    public String  fontStyle;         // default|bold|italic|handwriting|condensed|serif
    public String  textAlign;         // left|center|right
    public float   textSize;
    public String  templateId;

    // ── Location ─────────────────────────────────────────
    public String  location;
    public double  latitude;
    public double  longitude;

    // ── GeoFence (NEW v26) ────────────────────────────────
    public double  geoFenceLat;
    public double  geoFenceLng;
    public double  geoFenceRadiusKm;
    public boolean hasGeoFence() { return geoFenceRadiusKm > 0; }

    // ── Privacy ───────────────────────────────────────────
    public String  privacy;           // everyone|contacts|except|only|close_friends
    public List<String> exceptUids;
    public List<String> onlyUids;
    public boolean isCloseFriends;

    // ── Timing ────────────────────────────────────────────
    public Object  timestamp;         // ServerValue.TIMESTAMP (Long at runtime)
    public int     expiryHours;       // 0 = default 24h
    public Long    expiresAt;         // absolute timestamp

    public boolean isExpired() {
        if (expiresAt != null && expiresAt > 0) return System.currentTimeMillis() > expiresAt;
        if (timestamp instanceof Long) return System.currentTimeMillis() - (Long) timestamp > 24L * 3600_000;
        return false;
    }

    // ── Engagement ────────────────────────────────────────
    public Map<String, Object> seenBy;       // uid → timestamp or {t, r}
    public Map<String, String> reactions;    // uid → emoji
    public Map<String, Long>   viewDurations;// uid → ms (NEW v26 — enables true avg calc)
    public int     forwardCount;
    public int     totalViews;               // legacy field

    /** NEW v26 — average view duration from viewDurations map */
    @Exclude
    public double getAvgViewDurationSec() {
        if (viewDurations == null || viewDurations.isEmpty()) return 0;
        long total = 0;
        for (Long d : viewDurations.values()) if (d != null) total += d;
        return (total / 1000.0) / viewDurations.size();
    }

    // ── Analytics ─────────────────────────────────────────
    public boolean isHighlighted;
    public String  highlightAlbumId;
    public String  highlightAlbumName;
    public boolean isArchived;
    public Long    archivedAt;

    // ── Mentions ──────────────────────────────────────────
    public Map<String, String> mentionUids;  // username → uid (resolved)
    public List<String>        mentionNames; // @usernames

    // ── Music (NEW v26) ────────────────────────────────────
    public String  musicSongId;
    public String  musicTitle;
    public String  musicArtist;
    public String  musicAudioUrl;
    public int     musicStartSec;
    public boolean musicMuted;

    // ── Poll/Countdown/Collage (NEW v26) ──────────────────
    public String  pollQuestion;
    public List<String> pollOptions;
    public Map<String, Integer> pollVotes;   // option index (str) → count
    public String  pollType;                 // poll|question|quiz
    public Long    countdownTargetTs;
    public String  countdownLabel;
    public List<String> collageImageUrls;   // for collage type

    // ── Boomerang ────────────────────────────────────────
    public boolean isBoomerang;

    /** Convert to Firebase-compatible Map */
    @Exclude
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        if (id            != null) m.put("id",            id);
        if (ownerUid      != null) m.put("ownerUid",      ownerUid);
        if (ownerName     != null) m.put("ownerName",     ownerName);
        if (ownerPhotoUrl != null) m.put("ownerPhotoUrl", ownerPhotoUrl);
        if (type          != null) m.put("type",          type);
        if (text          != null) m.put("text",          text);
        if (caption       != null) m.put("caption",       caption);
        if (mediaUrl      != null) m.put("mediaUrl",      mediaUrl);
        if (thumbnailUrl  != null) m.put("thumbnailUrl",  thumbnailUrl);
        if (linkUrl       != null) m.put("linkUrl",       linkUrl);
        if (linkTitle     != null) m.put("linkTitle",     linkTitle);
        if (linkDescription!=null) m.put("linkDescription", linkDescription);
        if (linkImageUrl  != null) m.put("linkImageUrl",  linkImageUrl);
        if (linkDomain    != null) m.put("linkDomain",    linkDomain);
        if (bgColor       != null) m.put("bgColor",       bgColor);
        if (bgColor2      != null) m.put("bgColor2",      bgColor2);
        if (textColor     != null) m.put("textColor",     textColor);
        if (fontStyle     != null) m.put("fontStyle",     fontStyle);
        if (textAlign     != null) m.put("textAlign",     textAlign);
        if (textSize      != 0)    m.put("textSize",      textSize);
        if (templateId    != null) m.put("templateId",    templateId);
        if (location      != null) m.put("location",      location);
        if (privacy       != null) m.put("privacy",       privacy);
        if (isCloseFriends)        m.put("isCloseFriends", true);
        if (geoFenceRadiusKm > 0) {
            m.put("geoFenceLat", geoFenceLat);
            m.put("geoFenceLng", geoFenceLng);
            m.put("geoFenceRadiusKm", geoFenceRadiusKm);
        }
        m.put("timestamp",   ServerValue.TIMESTAMP);
        if (expiryHours != 0) m.put("expiryHours", expiryHours);
        if (expiresAt   != null) m.put("expiresAt",   expiresAt);
        if (musicSongId != null) { m.put("musicSongId", musicSongId); m.put("musicTitle", musicTitle);
            m.put("musicArtist", musicArtist); m.put("musicAudioUrl", musicAudioUrl); m.put("musicStartSec", musicStartSec); }
        if (pollQuestion != null) { m.put("pollQuestion", pollQuestion); m.put("pollOptions", pollOptions); m.put("pollType", pollType); }
        if (countdownTargetTs != null) { m.put("countdownTargetTs", countdownTargetTs); m.put("countdownLabel", countdownLabel); }
        if (collageImageUrls  != null) m.put("collageImageUrls", collageImageUrls);
        if (isBoomerang)               m.put("isBoomerang", true);
        return m;
    }
}
