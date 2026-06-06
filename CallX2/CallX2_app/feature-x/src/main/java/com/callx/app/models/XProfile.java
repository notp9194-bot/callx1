package com.callx.app.models;

import java.util.HashMap;
import java.util.Map;

/**
 * XProfile — X module ka dedicated profile model.
 * Replaces the old XUser profile fields.
 * Firebase node: x/users/{uid}
 */
public class XProfile {

    // ── Identity ──────────────────────────────────────────────────────────────
    public String uid;
    public String name;
    public String handle;          // @handle (unique, indexed at x/x_handles/{handle})
    public String bio;
    public String photoUrl;        // Full avatar (800×800 JPEG)  → x/avatars/
    public String thumbUrl;        // Feed avatar  (100×100 WebP) → x/avatars/thumbs/
    public String bannerUrl;       // Profile banner              → x/banners/
    public String website;
    public String location;
    public String birthday;        // "DD/MM/YYYY"
    public String gender;          // "Male" / "Female" / "Other" / "Prefer not to say"

    // ── Verification ──────────────────────────────────────────────────────────
    public boolean verified;       // Official verified
    public boolean blueVerified;   // Paid blue tick
    public boolean privateAccount; // Protected account

    // ── Stats ─────────────────────────────────────────────────────────────────
    public long followerCount;
    public long followingCount;
    public long tweetCount;
    public long profileViews;

    // ── Content ───────────────────────────────────────────────────────────────
    public String pinnedTweetId;

    // ── Timestamps ────────────────────────────────────────────────────────────
    public long joinedTs;
    public long updatedAt;

    // ── Social graph (loaded separately, NOT stored in profile node) ──────────
    // These are transient helpers populated at runtime — not written to Firebase
    public transient Map<String, Boolean> followers  = new HashMap<>();
    public transient Map<String, Boolean> muted      = new HashMap<>();
    public transient Map<String, Boolean> blocked    = new HashMap<>();

    // ── Helpers ───────────────────────────────────────────────────────────────
    public boolean isFollowedBy(String uid) {
        return uid != null && Boolean.TRUE.equals(followers.get(uid));
    }
    public boolean hasMuted(String uid) {
        return uid != null && Boolean.TRUE.equals(muted.get(uid));
    }
    public boolean hasBlocked(String uid) {
        return uid != null && Boolean.TRUE.equals(blocked.get(uid));
    }

    /** Avatar URL to show — prefers thumb for lists, falls back to full. */
    public String avatarForList() {
        return (thumbUrl != null && !thumbUrl.isEmpty()) ? thumbUrl : photoUrl;
    }

    /** Firebase-safe Map for profile save. */
    public Map<String, Object> toProfileMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("name",           name   != null ? name   : "");
        m.put("handle",         handle != null ? handle : "");
        m.put("bio",            bio    != null ? bio    : "");
        m.put("website",        website  != null ? website  : "");
        m.put("location",       location != null ? location : "");
        m.put("birthday",       birthday != null ? birthday : "");
        m.put("gender",         gender   != null ? gender   : "Prefer not to say");
        m.put("privateAccount", privateAccount);
        m.put("updatedAt",      System.currentTimeMillis());
        return m;
    }
}
