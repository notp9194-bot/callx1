package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * StatusItem — Production-level model for WhatsApp-style Stories/Status.
 *
 * Firebase node: statuses/{ownerUid}/{statusId}
 */
@IgnoreExtraProperties
public class StatusItem {

    // ── Identity ──────────────────────────────────────────────────────────
    public String  statusId;
    public String  ownerUid;
    public String  ownerName;
    public String  ownerPhoto;

    // ── Content ───────────────────────────────────────────────────────────
    /** "text" | "image" | "video" | "gif" | "link" | "poll" */
    public String  type;
    public String  text;           // text content or caption on media
    public String  mediaUrl;       // full-res image/video URL
    public String  thumbnailUrl;   // thumb for chat list & fast preview
    public String  mediaLocalPath; // offline: local file path before upload
    public long    mediaDuration;  // video duration in ms
    public String  mimeType;

    // ── Text style ────────────────────────────────────────────────────────
    public String  bgColor;        // hex string e.g. "#25D366"
    public String  bgGradientEnd;  // optional second color for gradient
    public int     fontStyle;      // 0=default 1=serif 2=mono 3=cursive 4=bold
    public int     textAlign;      // 0=center 1=left 2=right
    public float   textSize;       // sp, default 24

    // ── Timestamps & TTL ─────────────────────────────────────────────────
    public long    timestamp;
    public long    expiresAt;       // default: timestamp + 24h; custom TTL supported
    public boolean deleted;         // soft-delete
    public boolean archived;        // moved to archive on expiry

    // ── Privacy ───────────────────────────────────────────────────────────
    /** "everyone" | "contacts" | "except" | "only" */
    public String  privacy;
    /** UIDs excluded when privacy="except", or allowed when privacy="only" */
    public List<String> privacyList;
    public boolean closeFriendsOnly; // shortcut for "only" → closeFriends group

    // ── Seen & Reactions ──────────────────────────────────────────────────
    /** Map<uid, timestamp> — written to statusSeen/{ownerUid}/{statusId}/{viewerUid} */
    public Map<String, Long>   seenBy;
    /** Map<uid, emoji> — ❤️ 😂 😮 😢 😡 👍 */
    public Map<String, String> reactions;
    public int seenCount;   // denormalized for fast badge display

    // ── Link preview (type="link") ────────────────────────────────────────
    public String linkUrl;
    public String linkTitle;
    public String linkDescription;
    public String linkThumbUrl;
    public String linkDomain;

    // ── Poll (type="poll") ────────────────────────────────────────────────
    public String       pollQuestion;
    public List<String> pollOptions;
    /** Map<optionIndex, Map<uid, true>> */
    public Map<String, Map<String, Boolean>> pollVotes;
    public boolean pollMultipleChoice;
    public long    pollExpiresAt;

    // ── Music overlay ─────────────────────────────────────────────────────
    public String musicTrackId;
    public String musicTitle;
    public String musicArtist;
    public String musicCoverUrl;
    public long   musicStartMs;  // trim start in ms
    public long   musicEndMs;    // trim end in ms

    // ── Location tag ──────────────────────────────────────────────────────
    public double locationLat;
    public double locationLng;
    public String locationName;

    // ── Feeling / Activity ───────────────────────────────────────────────
    public String feeling;       // "happy", "excited", etc.
    public String feelingEmoji;

    // ── Mentions ─────────────────────────────────────────────────────────
    /** List of mentioned UIDs */
    public List<String> mentions;
    /** List of hashtags without # */
    public List<String> hashtags;

    // ── Highlight ────────────────────────────────────────────────────────
    public boolean inHighlight;
    public String  highlightId;  // which highlight collection this belongs to

    // ── Question sticker ─────────────────────────────────────────────────
    public boolean hasQuestion;
    public String  questionText;
    /** Map<uid, answer> */
    public Map<String, String> questionAnswers;

    // ── Drawing/Doodle overlay ────────────────────────────────────────────
    public String drawingDataUrl; // base64 or URL of doodle layer PNG

    // ── Stickers ─────────────────────────────────────────────────────────
    /** Serialized JSON array of sticker placements */
    public String stickersJson;

    // ── Constructors ──────────────────────────────────────────────────────
    public StatusItem() {}

    public StatusItem(String ownerUid, String ownerName, String ownerPhoto,
                      String type, String text, String bgColor, int fontStyle) {
        this.ownerUid   = ownerUid;
        this.ownerName  = ownerName;
        this.ownerPhoto = ownerPhoto;
        this.type       = type;
        this.text       = text;
        this.bgColor    = bgColor;
        this.fontStyle  = fontStyle;
        this.privacy    = "contacts";
        this.timestamp  = System.currentTimeMillis();
        this.expiresAt  = this.timestamp + 24L * 60 * 60 * 1000; // 24h default
        this.deleted    = false;
        this.archived   = false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public boolean hasMedia() {
        return "image".equals(type) || "video".equals(type) || "gif".equals(type);
    }

    public int getReactionCount() {
        return reactions == null ? 0 : reactions.size();
    }

    public int getSeenCount() {
        return seenBy == null ? 0 : seenBy.size();
    }

    /** Build the Firebase map for saving a new status */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("statusId",      statusId);
        m.put("ownerUid",      ownerUid);
        m.put("ownerName",     ownerName);
        m.put("ownerPhoto",    ownerPhoto != null ? ownerPhoto : "");
        m.put("type",          type != null ? type : "text");
        m.put("text",          text != null ? text : "");
        m.put("mediaUrl",      mediaUrl != null ? mediaUrl : "");
        m.put("thumbnailUrl",  thumbnailUrl != null ? thumbnailUrl : "");
        m.put("mediaDuration", mediaDuration);
        m.put("mimeType",      mimeType != null ? mimeType : "");
        m.put("bgColor",       bgColor != null ? bgColor : "#075E54");
        m.put("bgGradientEnd", bgGradientEnd != null ? bgGradientEnd : "");
        m.put("fontStyle",     fontStyle);
        m.put("textAlign",     textAlign);
        m.put("textSize",      textSize > 0 ? textSize : 24f);
        m.put("timestamp",     timestamp > 0 ? timestamp : ServerValue.TIMESTAMP);
        m.put("expiresAt",     expiresAt);
        m.put("deleted",       false);
        m.put("archived",      false);
        m.put("privacy",       privacy != null ? privacy : "contacts");
        m.put("seenCount",     0);
        m.put("closeFriendsOnly", closeFriendsOnly);
        // Optional fields
        if (linkUrl != null)       m.put("linkUrl",       linkUrl);
        if (linkTitle != null)     m.put("linkTitle",     linkTitle);
        if (linkDescription != null) m.put("linkDescription", linkDescription);
        if (linkThumbUrl != null)  m.put("linkThumbUrl",  linkThumbUrl);
        if (linkDomain != null)    m.put("linkDomain",    linkDomain);
        if (pollQuestion != null)  m.put("pollQuestion",  pollQuestion);
        if (pollOptions != null)   m.put("pollOptions",   pollOptions);
        m.put("pollMultipleChoice", pollMultipleChoice);
        if (pollExpiresAt > 0)     m.put("pollExpiresAt", pollExpiresAt);
        if (musicTitle != null)    m.put("musicTitle",    musicTitle);
        if (musicArtist != null)   m.put("musicArtist",   musicArtist);
        if (musicCoverUrl != null) m.put("musicCoverUrl", musicCoverUrl);
        m.put("musicStartMs",      musicStartMs);
        m.put("musicEndMs",        musicEndMs);
        if (locationName != null)  m.put("locationName",  locationName);
        m.put("locationLat",       locationLat);
        m.put("locationLng",       locationLng);
        if (feeling != null)       m.put("feeling",       feeling);
        if (feelingEmoji != null)  m.put("feelingEmoji",  feelingEmoji);
        if (mentions != null)      m.put("mentions",      mentions);
        if (hashtags != null)      m.put("hashtags",      hashtags);
        m.put("hasQuestion",       hasQuestion);
        if (questionText != null)  m.put("questionText",  questionText);
        if (drawingDataUrl != null) m.put("drawingDataUrl", drawingDataUrl);
        if (stickersJson != null)  m.put("stickersJson",  stickersJson);
        return m;
    }
}
