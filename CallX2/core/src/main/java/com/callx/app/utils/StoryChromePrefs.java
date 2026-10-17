package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The status VIEWER publishes the real measured height of its header chrome here; the status EDITOR's
 * safe-area guide reads it, so the guide follows the viewer's actual layout instead of a hardcoded dp.
 * Falls back to StoryFrameSpec defaults (returns 0) until the viewer has been opened once.
 */
public final class StoryChromePrefs {
    private static final String FILE = "story_chrome";
    private static final String KEY_TOP_PX = "top_px";
    private StoryChromePrefs() {}

    /** Called from the viewer after layout. Writes only when the value changed. */
    public static void publishTop(Context c, int px) {
        if (c == null || px <= 0) return;
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (sp.getInt(KEY_TOP_PX, 0) != px) sp.edit().putInt(KEY_TOP_PX, px).apply();
    }

    /** Measured header chrome (px) inside the story card, or 0 if unknown. */
    public static int topPx(Context c) {
        if (c == null) return 0;
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_TOP_PX, 0);
    }
}
