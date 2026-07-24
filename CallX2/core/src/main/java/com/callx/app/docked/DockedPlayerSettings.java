package com.callx.app.docked;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Feature flag for the chat-tab docked mini reel player (ReelChatDockedPlayer).
 *
 * The docked player is still being stabilized, so it defaults to OFF and is
 * only exposed as an opt-in toggle in UserReelsActivity's own-profile 3-dot
 * menu ("Docked Reel Player"). MainActivity checks {@link #isEnabled} before
 * ever starting a dock session when the user switches from Reels to Chats.
 */
public final class DockedPlayerSettings {

    private static final String PREFS_NAME = "docked_player_prefs";
    private static final String KEY_ENABLED = "docked_player_enabled";

    private DockedPlayerSettings() {}

    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        return context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false); // OFF by default during testing
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply();
    }
}
