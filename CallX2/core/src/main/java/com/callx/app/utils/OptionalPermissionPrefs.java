package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * v391 WHATSAPP-LEVEL FIX — "app feels slow/broken on cold open" (reported
 * right after the v390 Chats-tab ViewModel-cache fix landed).
 *
 * ROOT CAUSE: MainActivity.onCreate()'s old requestPermissions() ran
 * UNCONDITIONALLY on every single cold start, before the ViewPager/Chats
 * tab was even set up. Two of its three checks don't show a lightweight
 * permission dialog — they call startActivity() straight into a SYSTEM
 * SETTINGS screen (ACTION_MANAGE_OVERLAY_PERMISSION,
 * ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) whenever that optional
 * permission isn't already granted. Neither permission is required for the
 * Chats tab (overlay is only for the small-window/chat-heads feature —
 * already requested contextually elsewhere, e.g. PrivacyDirectDialog,
 * SmallWindowManager, ChatActivity, NotificationActionReceiver; full-screen-
 * intent is only for the incoming-call UI on Android 14+). Most users never
 * grant either optional permission, so canDrawOverlays()/canUseFullScreenIntent()
 * stayed false FOREVER — meaning EVERY cold start yanked the user straight
 * to a system Settings screen instead of ever showing the Chats tab they
 * just tried to open. That double-navigation (app → Settings → back) is
 * exactly what read as "screen bahut slow open hoti hai" on a fresh
 * process start (first-ever open or after the OS killed the app) — the
 * Chats tab itself was never the bottleneck; it never even got a chance to
 * draw first.
 *
 * FIX: gate each of these two Settings redirects behind an "already asked"
 * flag — same one-time-prompt idiom already used for
 * ReelDisplayModePrefs.hasBeenAsked() — so a user who ignores/declines the
 * optional permission is asked once, ever, not on every single relaunch.
 * MainActivity's call site also moved this off the critical startup path
 * (see MainActivity#requestPermissions doc) so it can never again delay the
 * very first frame even on the one time it does fire.
 */
public final class OptionalPermissionPrefs {

    private OptionalPermissionPrefs() {}

    private static final String PREFS_NAME = "callx_prefs";
    private static final String KEY_OVERLAY_ASKED           = "optional_perm_overlay_asked";
    private static final String KEY_FULL_SCREEN_INTENT_ASKED = "optional_perm_fsi_asked";

    public static boolean hasAskedOverlay(Context context) {
        if (context == null) return true; // fail-safe: never re-prompt if context missing
        return prefs(context).getBoolean(KEY_OVERLAY_ASKED, false);
    }

    public static void markAskedOverlay(Context context) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_OVERLAY_ASKED, true).apply();
    }

    public static boolean hasAskedFullScreenIntent(Context context) {
        if (context == null) return true;
        return prefs(context).getBoolean(KEY_FULL_SCREEN_INTENT_ASKED, false);
    }

    public static void markAskedFullScreenIntent(Context context) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_FULL_SCREEN_INTENT_ASKED, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
