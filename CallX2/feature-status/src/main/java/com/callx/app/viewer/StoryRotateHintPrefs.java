package com.callx.app.viewer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Viewer-side, once-ever flag for the "Tilt your phone" onboarding hint
 * (StoryRotateHintView) shown the very first time this viewer opens a story
 * that has the level-stabilizer active — mirrors Instagram's one-time
 * rotate-hint the first time you land on a feature that reacts to device
 * tilt. Deliberately app-wide and permanent (not per-story, not reset on
 * reinstall-detection or anything clever): once shown, never shown again on
 * this device/account.
 */
public final class StoryRotateHintPrefs {

    private static final String PREFS_NAME = "story_rotate_hint_prefs";
    private static final String KEY_SEEN = "seen";

    // In-memory, write-through cache of the persisted flag. Once populated
    // (by the first real hasSeenHint() call, or immediately by markSeen()),
    // every later call in this process — and this hint is checked on every
    // single startSpinViewIfAllowed() invocation, i.e. every story swipe —
    // is a plain volatile field read instead of going through
    // SharedPreferences' HashMap lookup + Boolean boxing each time.
    private static volatile Boolean seenCache = null;

    private StoryRotateHintPrefs() { }

    public static boolean hasSeenHint(Context ctx) {
        Boolean cached = seenCache;
        if (cached != null) return cached;
        boolean seen = prefs(ctx).getBoolean(KEY_SEEN, false); // default: not seen yet
        seenCache = seen;
        return seen;
    }

    public static void markSeen(Context ctx) {
        seenCache = true; // write-through: subsequent hasSeenHint() calls need no prefs read at all
        prefs(ctx).edit().putBoolean(KEY_SEEN, true).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
