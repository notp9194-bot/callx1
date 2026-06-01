package com.callx.app.utils;

import com.google.firebase.remoteconfig.FirebaseRemoteConfig;

/**
 * StatusABTestHelper v26 — A/B testing via Firebase Remote Config.
 * Variants: reaction position, progress bar style, reply UI style.
 */
public final class StatusABTestHelper {
    private StatusABTestHelper() {}

    public static boolean showReactionOnRight() {
        return getBoolean("status_reaction_right", false);
    }
    public static boolean useCircularProgressBar() {
        return getBoolean("status_circular_progress", false);
    }
    public static boolean showInlineReplyBar() {
        return getBoolean("status_inline_reply", true);
    }
    public static boolean enableAICaption() {
        return getBoolean("status_ai_caption_enabled", false);
    }
    public static int getPreloadCount() {
        return (int) getLong("status_preload_count", 10);
    }
    public static boolean showHighlightsStrip() {
        return getBoolean("status_highlights_strip", true);
    }
    public static boolean enableGeoFence() {
        return getBoolean("status_geo_fence_enabled", false);
    }

    private static boolean getBoolean(String key, boolean def) {
        try { return FirebaseRemoteConfig.getInstance().getBoolean(key); }
        catch (Exception e) { return def; }
    }
    private static long getLong(String key, long def) {
        try { return FirebaseRemoteConfig.getInstance().getLong(key); }
        catch (Exception e) { return def; }
    }
}
