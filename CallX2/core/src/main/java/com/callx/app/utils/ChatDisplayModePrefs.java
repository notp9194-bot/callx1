package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores the user's chosen "Chat display mode" — same idea as
 * {@link ReelDisplayModePrefs} but for chat screens (ChatActivity /
 * GroupChatActivity):
 *   MODE_IMMERSIVE — full-screen look. Status bar AND the system navigation
 *                     bar are both hidden while a chat is open (the app's
 *                     original always-full-screen chat behavior).
 *   MODE_NORMAL    — Status bar AND the system navigation bar stay visible
 *                     while a chat is open.
 *
 * The very first time the user ever opens a chat they're asked to pick one
 * (see ChatDisplayModeBottomSheet, first-visit flow). They can change it
 * anytime afterwards from the chat's 3-dot menu (Display Mode option).
 * Lives in :core so both :feature-chat (ChatActivity, GroupChatActivity)
 * and any future callers can share one prefs source of truth.
 */
public final class ChatDisplayModePrefs {

    private ChatDisplayModePrefs() {}

    private static final String PREFS_NAME = "callx_prefs";
    private static final String KEY_MODE    = "chat_display_mode";
    private static final String KEY_ASKED   = "chat_display_mode_asked";

    public static final String MODE_IMMERSIVE = "immersive";
    public static final String MODE_NORMAL    = "normal";

    /** Default mode is IMMERSIVE — matches the app's original always-full-screen chat behavior. */
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
