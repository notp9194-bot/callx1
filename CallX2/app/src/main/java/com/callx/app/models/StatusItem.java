package com.callx.app.models;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Production-grade StatusItem model.
 * Supports text / image / video / link-preview statuses with
 * per-viewer tracking, reactions, privacy, and rich metadata.
 */
public class StatusItem {

    // ── Core identity ─────────────────────────────────────────────────────
    public String id;
    public String ownerUid;
    public String ownerName;
    public String ownerPhoto;

    // ── Content ───────────────────────────────────────────────────────────
    /** "text" | "image" | "video" | "link" */
    public String type;
    public String text;
    public String mediaUrl;
    public String thumbnailUrl;      // video thumbnail / link OG image
    public String linkUrl;           // for type == "link"
    public String linkTitle;
    public String linkDescription;
    public String linkFaviconUrl;

    // ── Text-status styling ───────────────────────────────────────────────
    /** Hex color string e.g. "#FF6200EE" — background for text statuses */
    public String bgColor;
    /** "default" | "bold" | "italic" | "handwriting" */
    public String fontStyle;
    /** Hex color string for text foreground */
    public String textColor;
    /** 12–48 — display font size in sp */
    public int    textSize;

    // ── Timing ───────────────────────────────────────────────────────────
    public Long timestamp;
    public Long expiresAt;
    /** Duration in seconds — for video statuses */
    public int  durationSec;

    // ── Privacy ───────────────────────────────────────────────────────────
    /** "everyone" | "contacts" | "except" | "only" */
    public String privacy;
    /** UIDs excluded (privacy == "except") or included (privacy == "only") */
    public List<String> privacyList;

    // ── Viewer tracking ───────────────────────────────────────────────────
    /** uid → timestamp — written by viewers via StatusSeenTracker */
    public Map<String, Long> seenBy;

    // ── Reactions ─────────────────────────────────────────────────────────
    /** uid → emoji string */
    public Map<String, String> reactions;

    // ── Highlights ────────────────────────────────────────────────────────
    public boolean isHighlighted;
    public String  highlightAlbumId;
    public String  highlightAlbumName;

    // ── Location tag ─────────────────────────────────────────────────────
    public String locationName;
    public double locationLat;
    public double locationLng;

    // ── Caption (media statuses) ──────────────────────────────────────────
    public String caption;

    // ── Mute/delete ───────────────────────────────────────────────────────
    public boolean deleted;

    // ── Firebase no-arg constructor ───────────────────────────────────────
    public StatusItem() {}

    // ── Convenience helpers ───────────────────────────────────────────────

    public boolean isExpired() {
        return expiresAt != null && expiresAt < System.currentTimeMillis();
    }

    public boolean isDeleted() {
        return deleted;
    }

    public int getViewCount() {
        return seenBy == null ? 0 : seenBy.size();
    }

    public boolean seenBy(String uid) {
        return seenBy != null && seenBy.containsKey(uid);
    }

    public String getReaction(String uid) {
        return reactions == null ? null : reactions.get(uid);
    }

    public boolean isTextOnly() {
        return "text".equals(type);
    }

    public boolean isImage() {
        return "image".equals(type);
    }

    public boolean isVideo() {
        return "video".equals(type);
    }

    public boolean isLink() {
        return "link".equals(type);
    }

    /** Build a Firebase-safe map for writing a new status */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        if (id            != null) m.put("id",            id);
        if (ownerUid      != null) m.put("ownerUid",      ownerUid);
        if (ownerName     != null) m.put("ownerName",     ownerName);
        if (ownerPhoto    != null) m.put("ownerPhoto",    ownerPhoto);
        if (type          != null) m.put("type",          type);
        if (text          != null) m.put("text",          text);
        if (caption       != null) m.put("caption",       caption);
        if (mediaUrl      != null) m.put("mediaUrl",      mediaUrl);
        if (thumbnailUrl  != null) m.put("thumbnailUrl",  thumbnailUrl);
        if (linkUrl       != null) m.put("linkUrl",       linkUrl);
        if (linkTitle     != null) m.put("linkTitle",     linkTitle);
        if (linkDescription != null) m.put("linkDescription", linkDescription);
        if (bgColor       != null) m.put("bgColor",       bgColor);
        if (fontStyle     != null) m.put("fontStyle",     fontStyle);
        if (textColor     != null) m.put("textColor",     textColor);
        if (textSize      != 0)    m.put("textSize",      textSize);
        if (timestamp     != null) m.put("timestamp",     timestamp);
        if (expiresAt     != null) m.put("expiresAt",     expiresAt);
        if (durationSec   != 0)    m.put("durationSec",   durationSec);
        if (privacy       != null) m.put("privacy",       privacy);
        if (privacyList   != null) m.put("privacyList",   privacyList);
        if (locationName  != null) m.put("locationName",  locationName);
        if (locationLat   != 0)    m.put("locationLat",   locationLat);
        if (locationLng   != 0)    m.put("locationLng",   locationLng);
        m.put("isHighlighted", isHighlighted);
        m.put("deleted",       deleted);
        return m;
    }
}
