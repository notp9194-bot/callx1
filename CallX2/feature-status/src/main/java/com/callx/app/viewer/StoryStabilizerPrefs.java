package com.callx.app.viewer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Viewer-side ON/OFF preference for the Story level-stabilizer
 * (StorySpinViewController). Some viewers find the counter-rotation
 * distracting — this is a simple, persisted, app-wide switch, toggled from
 * the stabilizer icon in StatusViewerActivity. Independent of the
 * per-story `StatusItem.stabilizerEnabled` creator flag: BOTH must allow
 * it for a given story to actually stabilize (see StatusViewerActivity).
 */
public final class StoryStabilizerPrefs {

    private static final String PREFS_NAME = "story_stabilizer_prefs";
    private static final String KEY_ENABLED = "enabled";

    private StoryStabilizerPrefs() { }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true); // default ON
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
