package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.database.FirebaseDatabase;
import java.util.HashMap;
import java.util.Map;

/**
 * RepostPrivacyManager — controls who can repost your reels.
 * Mirrors the existing duet/stitch allowDuetLevel / allowStitchLevel pattern.
 *
 * allowRepostLevel values:
 *   "everyone"   — anyone can repost
 *   "followers"  — only followers can repost
 *   "off"        — reposting disabled
 */
public class RepostPrivacyManager {

    public static final String LEVEL_EVERYONE  = "everyone";
    public static final String LEVEL_FOLLOWERS = "followers";
    public static final String LEVEL_OFF       = "off";

    private static final String PREFS_NAME = "repost_prefs";
    private static final String KEY_GLOBAL = "global_repost_level";

    private final SharedPreferences prefs;

    public RepostPrivacyManager(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Save global repost level ──────────────────────────────────────────────
    public void setGlobalRepostLevel(String level) {
        prefs.edit().putString(KEY_GLOBAL, level).apply();
    }

    public String getGlobalRepostLevel() {
        return prefs.getString(KEY_GLOBAL, LEVEL_EVERYONE);
    }

    // ── Per-reel allowRepostLevel in Firebase ─────────────────────────────────
    public static void setRepostLevel(String reelId, String level) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("allowRepostLevel", level);
        updates.put("allowRepost", !LEVEL_OFF.equals(level));
        FirebaseDatabase.getInstance().getReference("reels").child(reelId)
            .updateChildren(updates);
    }

    // ── Check if user can repost ──────────────────────────────────────────────
    public static void canUserRepost(String reelId, String viewerUid, String ownerUid,
                                     CanRepostCallback cb) {
        FirebaseDatabase.getInstance().getReference("reels").child(reelId)
            .child("allowRepostLevel").get().addOnSuccessListener(snap -> {
                String level = snap.exists() ? snap.getValue(String.class) : LEVEL_EVERYONE;
                if (level == null || LEVEL_EVERYONE.equals(level)) {
                    cb.onResult(true);
                } else if (LEVEL_OFF.equals(level)) {
                    cb.onResult(false);
                } else {
                    // followers — check if viewerUid follows ownerUid
                    FirebaseDatabase.getInstance().getReference("followers")
                        .child(ownerUid).child(viewerUid)
                        .get().addOnSuccessListener(s2 -> cb.onResult(s2.exists()));
                }
            });
    }

    public interface CanRepostCallback { void onResult(boolean canRepost); }
}
