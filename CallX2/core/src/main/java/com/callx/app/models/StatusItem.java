package com.callx.app.models;

import com.google.firebase.database.Exclude;
import com.google.firebase.database.IgnoreExtraProperties;
import java.util.*;

/**
 * StatusItem v25 — Full-featured status data model.
 *
 * Original fields: id, ownerUid, ownerName, ownerPhoto, type, text, caption,
 *   mediaUrl, thumbnailUrl, bgColor, fontStyle, textColor, timestamp,
 *   expiresAt, deleted, privacy, seenBy, reactions, viewCount.
 *
 * New fields added:
 *   reactions       — Map<viewerUid, emoji> (replaces old Map<uid, boolean>)
 *   viewDurations   — Map<viewerUid, durationMs>
 *   mentionNames    — Map<username, uid>
 *   privacyList     — List<String> (except/only UIDs)
 *   isCloseFriends  — boolean
 *   expiryHours     — int (1/3/6/12/24/48/72)
 *   isHighlighted   — boolean
 *   highlightAlbumId, highlightAlbumName
 *   isArchived      — boolean
 *   archivedAt      — Long
 *   forwardCount    — int
 *   gifUrl          — String
 *   stickerUrl      — String
 *   linkUrl/Title/Description/ImageUrl/Domain — link preview fields
 *   gradientColors  — List<String>
 *   textAlign       — "left"/"center"/"right"
 *   textSize        — float
 *   locationName, locationLat, locationLng
 *   bgColor2        — for gradient end
 *   durationSec     — video duration hint
 *   mediaWidth/Height
 *   caption
 *   closeFriendsOnly (deprecated → use isCloseFriends)
 */
@IgnoreExtraProperties
public class StatusItem {

    // ── Core identity ──────────────────────────────────────────────────
    public String  id;
    public String  ownerUid;
    public String  ownerName;
    public String  ownerPhoto;

    // ── Type ──────────────────────────────────────────────────────────
    /** "text", "image", "video", "link", "gif", "sticker", "reel_story", "reel_clip" */
    public String  type;

    // ── Content ───────────────────────────────────────────────────────
    public String  text;
    public String  caption;
    public String  mediaUrl;
    public String  thumbnailUrl;
    public int     mediaWidth;
    public int     mediaHeight;
    public int     durationSec;      // video duration hint in seconds

    // ── Text style ────────────────────────────────────────────────────
    public String  bgColor;          // hex, e.g. "#6200EE"
    public String  bgColor2;         // gradient end color (null = solid)
    public List<String> gradientColors; // multi-stop gradient
    public String  fontStyle;        // "default"/"bold"/"italic"/"handwriting"/"condensed"/"serif"
    public String  textColor;        // hex
    public float   textSize;         // sp, 0 = default
    public String  textAlign;        // "left"/"center"/"right"

    // ── Timing ────────────────────────────────────────────────────────
    public Long    timestamp;
    public Long    expiresAt;
    public int     expiryHours;      // 1/3/6/12/24/48/72 — default 24

    // ── State ────────────────────────────────────────────────────────
    public Boolean deleted;

    // ── Privacy ───────────────────────────────────────────────────────
    /** "everyone"/"contacts"/"except"/"only"/"close_friends" */
    public String       privacy;
    public List<String> privacyList;     // UIDs for except/only
    public boolean      isCloseFriends;  // post to close friends only

    // ── Seen / reactions / analytics ─────────────────────────────────
    /** seenBy: Map<viewerUid, timestampMs> */
    public Map<String, Long>    seenBy;
    /** reactions: Map<viewerUid, emoji> e.g. "❤️","😂","🔥" */
    public Map<String, String>  reactions;
    /** viewDurations: Map<viewerUid, durationMs> */
    public Map<String, Long>    viewDurations;

    // ── Mentions ──────────────────────────────────────────────────────
    /** mentionNames: Map<username, uid> */
    public Map<String, String>  mentionNames;

    // ── Highlights / Archive ─────────────────────────────────────────
    public boolean isHighlighted;
    public String  highlightAlbumId;
    public String  highlightAlbumName;
    public boolean isArchived;
    public Long    archivedAt;

    // ── Stats ─────────────────────────────────────────────────────────
    public int forwardCount;

    // ── GIF / Sticker ─────────────────────────────────────────────────
    public String gifUrl;
    public String stickerUrl;

    // ── Interactive stickers (v26) ─────────────────────────────────────
    /** JSON array of all interactive sticker configs added to this status.
     *  Format: [{"type":"music","song":"..."},{"type":"countdown",...},...]
     *  Each element is a sticker produced by StatusStickerPickerSheet. */
    public String stickersJson;

    // ── Link preview ──────────────────────────────────────────────────
    public String linkUrl;
    public String linkTitle;
    public String linkDescription;
    public String linkImageUrl;
    public String linkDomain;

    // ── Location ──────────────────────────────────────────────────────
    public String locationName;
    public double locationLat;
    public double locationLng;

    // ── Legacy / Deprecated (keep for backward compat) ────────────────
    /** @deprecated use isCloseFriends */
    @Deprecated public boolean closeFriendsOnly;

    // ── Computed helpers (excluded from Firebase) ─────────────────────

    @Exclude
    public int getViewCount() {
        return seenBy != null ? seenBy.size() : 0;
    }

    @Exclude
    public double getAvgViewDurationSec() {
        if (viewDurations == null || viewDurations.isEmpty()) return 0;
        long sum = 0;
        for (long d : viewDurations.values()) sum += d;
        return (double) sum / viewDurations.size() / 1000.0;
    }

    @Exclude
    public boolean hasReaction(String uid) {
        return uid != null && reactions != null && reactions.containsKey(uid);
    }

    @Exclude
    public String getReaction(String uid) {
        if (uid == null || reactions == null) return null;
        return reactions.get(uid);
    }

    @Exclude
    public int getReactionCount(String emoji) {
        if (emoji == null || reactions == null) return 0;
        int count = 0;
        for (String e : reactions.values()) if (emoji.equals(e)) count++;
        return count;
    }

    @Exclude
    public int getTotalReactionCount() {
        return reactions != null ? reactions.size() : 0;
    }

    /** Human-readable expiry label: "Expires in 2h", "Expired", etc. */
    @Exclude
    public String getExpiryLabel() {
        if (expiresAt == null) return "";
        long diff = expiresAt - System.currentTimeMillis();
        if (diff <= 0) return "Expired";
        long hours = diff / 3_600_000L;
        long mins  = (diff % 3_600_000L) / 60_000L;
        if (hours >= 24) return "Expires in " + (hours / 24) + "d";
        if (hours >= 1)  return "Expires in " + hours + "h";
        return "Expires in " + mins + "m";
    }

    // ── Story Reshare fields ────────────────────────────────────────────────
    /** "reel", "post", or "channel_post" — what content was reshared */
    public String resharedFromType       = "";
    /** ID of the original reel/post that was reshared */
    public String resharedFromId         = "";
    /** UID of the original content creator */
    public String resharedFromOwnerUid   = "";
    /** Display name of the original creator */
    public String resharedFromOwnerName  = "";
    /** Avatar URL of the original creator */
    public String resharedFromOwnerAvatar= "";
    /** Thumbnail URL of the original content for the card sticker */
    public String resharedThumbnailUrl   = "";
    /** Attribution text e.g. "Originally posted by @username" */
    public String attribution            = "";
    /** Text overlay typed by the resharer on top of story background */
    public String stickerText            = "";
    /** Relative X position of the card sticker (0.0–1.0 fraction of width) */
    public float  cardStickerX           = 0.1f;
    /** Relative Y position of the card sticker (0.0–1.0 fraction of height) */
    public float  cardStickerY           = 0.35f;
    /** Background hex color chosen for a text/card-style reshare story */
    public String reshareBackgroundColor = "";

    /** Convert to Firebase Map (preserves all fields, excludes nulls). */
    @Exclude
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        if (id != null)               m.put("id", id);
        if (ownerUid != null)         m.put("ownerUid", ownerUid);
        if (ownerName != null)        m.put("ownerName", ownerName);
        if (ownerPhoto != null)       m.put("ownerPhoto", ownerPhoto);
        if (type != null)             m.put("type", type);
        if (text != null)             m.put("text", text);
        if (caption != null)          m.put("caption", caption);
        if (mediaUrl != null)         m.put("mediaUrl", mediaUrl);
        if (thumbnailUrl != null)     m.put("thumbnailUrl", thumbnailUrl);
        if (mediaWidth != 0)          m.put("mediaWidth", mediaWidth);
        if (mediaHeight != 0)         m.put("mediaHeight", mediaHeight);
        if (durationSec != 0)         m.put("durationSec", durationSec);
        if (bgColor != null)          m.put("bgColor", bgColor);
        if (bgColor2 != null)         m.put("bgColor2", bgColor2);
        if (gradientColors != null)   m.put("gradientColors", gradientColors);
        if (fontStyle != null)        m.put("fontStyle", fontStyle);
        if (textColor != null)        m.put("textColor", textColor);
        if (textSize != 0)            m.put("textSize", textSize);
        if (textAlign != null)        m.put("textAlign", textAlign);
        if (timestamp != null)        m.put("timestamp", timestamp);
        if (expiresAt != null)        m.put("expiresAt", expiresAt);
        if (expiryHours != 0)         m.put("expiryHours", expiryHours);
        if (deleted != null)          m.put("deleted", deleted);
        if (privacy != null)          m.put("privacy", privacy);
        if (privacyList != null)      m.put("privacyList", privacyList);
        m.put("isCloseFriends",       isCloseFriends);
        if (seenBy != null)           m.put("seenBy", seenBy);
        if (reactions != null)        m.put("reactions", reactions);
        if (viewDurations != null)    m.put("viewDurations", viewDurations);
        if (mentionNames != null)     m.put("mentionNames", mentionNames);
        m.put("isHighlighted",        isHighlighted);
        if (highlightAlbumId != null) m.put("highlightAlbumId", highlightAlbumId);
        if (highlightAlbumName != null) m.put("highlightAlbumName", highlightAlbumName);
        m.put("isArchived",           isArchived);
        if (archivedAt != null)       m.put("archivedAt", archivedAt);
        if (forwardCount != 0)        m.put("forwardCount", forwardCount);
        if (gifUrl != null)           m.put("gifUrl", gifUrl);
        if (stickerUrl != null)       m.put("stickerUrl", stickerUrl);
        if (stickersJson != null && !stickersJson.isEmpty()) m.put("stickersJson", stickersJson);
        if (linkUrl != null)          m.put("linkUrl", linkUrl);
        if (linkTitle != null)        m.put("linkTitle", linkTitle);
        if (linkDescription != null)  m.put("linkDescription", linkDescription);
        if (linkImageUrl != null)     m.put("linkImageUrl", linkImageUrl);
        if (linkDomain != null)       m.put("linkDomain", linkDomain);
        if (locationName != null)     m.put("locationName", locationName);
        if (locationLat != 0)         m.put("locationLat", locationLat);
        if (locationLng != 0)         m.put("locationLng", locationLng);
        return m;
    }
}
