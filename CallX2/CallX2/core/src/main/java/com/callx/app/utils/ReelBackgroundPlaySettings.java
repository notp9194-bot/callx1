package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User toggle for "Background Play" on Reels — exposed as an option in the
 * Reels 3-dot menu (ReelMoreBottomSheet, ACTION_BACKGROUND_PLAY).
 *
 * OFF (default): leaving the app (home button, recents, screen off) pauses
 *                the currently playing reel immediately, same as before —
 *                no audio/video keeps running once the app is backgrounded.
 * ON:            the currently playing reel keeps playing (with audio) after
 *                the app is backgrounded, WhatsApp/Instagram-style, until the
 *                user returns to the app, swipes away, or explicitly pauses.
 *
 * Checked from ReelPlayerFragment#onPause() and ReelsFragment#onPause() —
 * the two points that fire specifically when the Activity itself goes to the
 * background (NOT on in-app tab switches or feed scroll, which always pause
 * regardless of this setting).
 */
public final class ReelBackgroundPlaySettings {

    private static final String PREFS_NAME = "callx_prefs";
    private static final String KEY_ENABLED = "reel_background_play_enabled";

    private ReelBackgroundPlaySettings() {}

    /** OFF by default — reels pause as soon as the app is backgrounded unless the user opts in. */
    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean toggle(Context context) {
        boolean newValue = !isEnabled(context);
        setEnabled(context, newValue);
        return newValue;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
