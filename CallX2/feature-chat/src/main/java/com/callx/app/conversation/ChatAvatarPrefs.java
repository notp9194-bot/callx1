package com.callx.app.conversation;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * v810: "Show avatars" chat display setting (avatar beside chat bubbles, 1:1 + group, both sides).
 * Default ON. When OFF the adapter never reserves the avatar column, so bubbles get the full width.
 *
 * Read path is a plain static boolean (no SharedPreferences hit per bind); it is loaded once
 * from disk by {@link #ensureLoaded(Context)} and kept in sync by {@link #set(Context, boolean)}.
 */
public final class ChatAvatarPrefs {
    private static final String PREFS = "chat_display_prefs";
    private static final String KEY_SHOW_AVATARS = "show_chat_avatars";

    private static volatile boolean loaded = false;
    private static volatile boolean showAvatars = true;

    private ChatAvatarPrefs() {}

    /** Cheap (static read). Call {@link #ensureLoaded(Context)} once before the first bind. */
    public static boolean isShowAvatars() { return showAvatars; }

    public static void ensureLoaded(@NonNull Context ctx) {
        if (loaded) return;
        synchronized (ChatAvatarPrefs.class) {
            if (loaded) return;
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            showAvatars = sp.getBoolean(KEY_SHOW_AVATARS, true);
            loaded = true;
        }
    }

    public static void set(@NonNull Context ctx, boolean show) {
        ensureLoaded(ctx);
        showAvatars = show;
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SHOW_AVATARS, show).apply();
    }
}
