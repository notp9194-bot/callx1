package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User toggles from the Reels long-press "playback options" sheet
 * (ReelPlaybackOptionsSheet). Same prefs file as ReelBackgroundPlaySettings.
 *
 *  • Auto scroll     — when ON, a reel that reaches its end swipes to the next
 *                      reel instead of looping. OFF by default (a reel loops
 *                      until the user swipes away, Instagram-style).
 *  • Closed Captions — when ON, a caption/subtitle text track is selected on
 *                      any reel whose stream carries one.
 */
public final class ReelPlaybackPrefs {

    private static final String PREFS_NAME       = "callx_prefs";
    private static final String KEY_AUTO_SCROLL  = "reel_auto_scroll_enabled";
    private static final String KEY_CAPTIONS     = "reel_captions_enabled";

    private ReelPlaybackPrefs() {}

    public static boolean isAutoScrollEnabled(Context c) {
        return c != null && prefs(c).getBoolean(KEY_AUTO_SCROLL, false);
    }

    public static void setAutoScrollEnabled(Context c, boolean on) {
        if (c != null) prefs(c).edit().putBoolean(KEY_AUTO_SCROLL, on).apply();
    }

    public static boolean isCaptionsEnabled(Context c) {
        return c != null && prefs(c).getBoolean(KEY_CAPTIONS, false);
    }

    public static void setCaptionsEnabled(Context c, boolean on) {
        if (c != null) prefs(c).edit().putBoolean(KEY_CAPTIONS, on).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
