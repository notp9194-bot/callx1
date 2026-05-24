package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ServerValue;

/**
 * Tracks per-video watch progress (resume position) and total watch time.
 * Position is stored locally in SharedPreferences for instant access.
 * Watch time totals are aggregated in Firebase.
 */
public class YouTubeWatchProgressUtils {

    private static final String PREFS = "yt_watch_progress";

    public static void save(Context ctx, String videoId, long positionMs) {
        if (videoId == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(videoId, positionMs).apply();
    }

    public static long load(Context ctx, String videoId) {
        if (videoId == null) return 0;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(videoId, 0);
    }

    public static void clear(Context ctx, String videoId) {
        if (videoId == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(videoId).apply();
    }

    /** Called every N seconds while playing — increments total watch time in Firebase. */
    public static void trackWatchTime(Context ctx, String videoId, long deltaSeconds) {
        if (videoId == null) return;
        // Increment video-level watch time
        YouTubeFirebaseUtils.videoRef(videoId).child("watchTimeSeconds")
            .setValue(ServerValue.increment(deltaSeconds));
        // Increment channel-level watch time
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid != null) {
            YouTubeFirebaseUtils.userWatchTimeRef(myUid).child(videoId)
                .setValue(ServerValue.increment(deltaSeconds));
        }
    }
}
