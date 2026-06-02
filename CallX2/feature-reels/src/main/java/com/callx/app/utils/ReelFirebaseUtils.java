package com.callx.app.utils;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

/**
 * ReelFirebaseUtils — Reels system ke liye dedicated Firebase helper.
 *
 * ROOT NODE: "reels"
 * Sab kuch reels/... ke neeche — chat users/{uid} se bilkul alag.
 *
 * Firebase Database Structure:
 * ├── reels/
 * │   ├── users/{uid}/              ← ReelProfile data (alag from chat users)
 * │   │   ├── displayName
 * │   │   ├── handle
 * │   │   ├── bio
 * │   │   ├── category
 * │   │   ├── photoUrl              ← Cloudinary: callx/reels/avatars/{uid}.jpg
 * │   │   ├── thumbUrl              ← Cloudinary: callx/reels/avatars/thumbs/{uid}.webp
 * │   │   ├── bannerUrl             ← Cloudinary: callx/reels/banners/{uid}.jpg
 * │   │   ├── followerCount
 * │   │   ├── followingCount
 * │   │   ├── reelCount
 * │   │   ├── verified
 * │   │   └── ...
 * │   ├── handles/{handle} → uid    ← Handle uniqueness index
 * │   ├── videos/{reelId}/          ← Reel video data (existing)
 * │   ├── user_videos/{uid}/        ← User ke reels index
 * │   ├── user_followers/{uid}/     ← Followers map
 * │   ├── user_following/{uid}/     ← Following map
 * │   └── ...
 */
public class ReelFirebaseUtils {

    private static final String REELS_ROOT = "reels";

    private static DatabaseReference root() {
        return FirebaseDatabase.getInstance(Constants.DB_URL).getReference(REELS_ROOT);
    }

    // ── Reel Profiles (alag from chat users/) ────────────────────────────────
    public static DatabaseReference reelUsersRef()                   { return root().child("users"); }
    public static DatabaseReference reelUserRef(String uid)          { return reelUsersRef().child(uid); }
    public static DatabaseReference reelHandlesRef()                 { return root().child("handles"); }
    public static DatabaseReference reelHandleRef(String handle)     { return reelHandlesRef().child(handle); }

    // ── Follower / Following ─────────────────────────────────────────────────
    public static DatabaseReference reelFollowersRef(String uid)     { return root().child("user_followers").child(uid); }
    public static DatabaseReference reelFollowingRef(String uid)     { return root().child("user_following").child(uid); }

    // ── Reels / Videos ───────────────────────────────────────────────────────
    public static DatabaseReference reelsRef()                       { return root().child("videos"); }
    public static DatabaseReference reelRef(String reelId)           { return reelsRef().child(reelId); }
    public static DatabaseReference userReelsRef(String uid)         { return root().child("user_videos").child(uid); }

    // ── Likes / Comments / Saves ──────────────────────────────────────────────
    public static DatabaseReference reelLikesRef(String reelId)     { return root().child("reel_likes").child(reelId); }
    public static DatabaseReference reelCommentsRef(String reelId)  { return root().child("reel_comments").child(reelId); }
    public static DatabaseReference reelSavesRef(String reelId)     { return root().child("reel_saves").child(reelId); }
    public static DatabaseReference userLikedReelsRef(String uid)   { return root().child("user_liked_reels").child(uid); }
    public static DatabaseReference userSavedReelsRef(String uid)   { return root().child("user_saved_reels").child(uid); }

    // ── Profile Views ─────────────────────────────────────────────────────────
    public static DatabaseReference profileViewsRef(String uid)     { return reelUserRef(uid).child("profileViews"); }

    // ── Helper ────────────────────────────────────────────────────────────────
    /** Handle clean karo — lowercase, alphanumeric + underscore only */
    public static String cleanHandle(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase().replaceAll("[^a-z0-9_]", "").replaceAll("@", "");
    }

    // ── Duet System ──────────────────────────────────────────────────────────
    /** All duets made of a specific reel: reel_duets/{originalReelId}/{duetReelId} */
    public static DatabaseReference reelDuetsRef(String originalReelId) {
        return root().child("reel_duets").child(originalReelId);
    }
    /** All duets a user has posted: user_duets/{uid}/{duetReelId} */
    public static DatabaseReference userDuetsRef(String uid) {
        return root().child("user_duets").child(uid);
    }
    /** Remix / duet / stitch permission settings per reel */
    public static DatabaseReference remixSettingsRef(String reelId) {
        return root().child("remix_settings").child(reelId);
    }
    /** FCM notification queue for duet notifications */
    public static DatabaseReference duetFcmQueueRef(String ownerUid) {
        return root().child("fcm_queue").child(ownerUid);
    }
}