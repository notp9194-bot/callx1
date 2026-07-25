package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores the user's chosen "Reels display mode":
 *   MODE_IMMERSIVE — TikTok-style full-screen. Status bar AND the main app's
 *                     bottom navigation tab are both hidden while on the Reels tab.
 *   MODE_NORMAL    — Status bar AND the main app's bottom navigation tab stay
 *                     visible while on the Reels tab.
 *
 * The very first time the user opens the Reels tab they're asked to pick one
 * (see ReelDisplayModeBottomSheet). They can change it anytime afterwards from
 * the Reels 3-dot menu (Display Mode option). Lives in :core so both :app
 * (MainActivity, which owns the actual immersive/nav toggling) and
 * :feature-reels (the 3-dot menu + first-visit chooser) can share one prefs
 * source of truth without a circular module dependency.
 */
public final class ReelDisplayModePrefs {

    private ReelDisplayModePrefs() {}

    private static final String PREFS_NAME = "callx_prefs";
    private static final String KEY_MODE    = "reel_display_mode";
    private static final String KEY_ASKED   = "reel_display_mode_asked";

    public static final String MODE_IMMERSIVE = "immersive";
    public static final String MODE_NORMAL    = "normal";

    /** Default mode is IMMERSIVE — matches the app's original always-full-screen Reels behavior. */
    public static String getMode(Context context) {
        if (context == null) return MODE_IMMERSIVE;
        return prefs(context).getString(KEY_MODE, MODE_IMMERSIVE);
    }

    public static boolean isNormalMode(Context context) {
        return MODE_NORMAL.equals(getMode(context));
    }

    public static boolean isImmersiveMode(Context context) {
        return !isNormalMode(context);
    }

    public static void setMode(Context context, String mode) {
        if (context == null || mode == null) return;
        prefs(context).edit().putString(KEY_MODE, mode).apply();
    }

    /** True once the user has been shown (and answered) the first-visit chooser. */
    public static boolean hasBeenAsked(Context context) {
        if (context == null) return true; // fail-safe: never re-prompt if context missing
        return prefs(context).getBoolean(KEY_ASKED, false);
    }

    public static void markAsked(Context context) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
