package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Lightweight, persistent log that lets the "Cache Status" UI (3-dot menu →
 * Cache Status) explain WHY a previously-watched reel is no longer in the
 * disk cache, instead of just silently re-downloading it.
 *
 * Two things are tracked:
 *  1. The last time the shared SimpleCache was trimmed by
 *     onTrimMemory()/UnifiedVideoCacheManager.trimMemory() — a real memory
 *     pressure event (MODERATE/COMPLETE), e.g. swipe-closing the app from
 *     the reels tab.
 *  2. Which reelIds were watched far enough (>=90%) that they were very
 *     likely fully cached at some point — so if they're missing later and
 *     no recent trim happened, the most likely explanation is the LRU
 *     cache-size-limit evictor (LeastRecentlyUsedCacheEvictor) quietly
 *     making room for newer reels.
 *
 * This is a best-effort explanation, not a byte-exact audit trail — ExoPlayer's
 * SimpleCache doesn't expose per-eviction callbacks cheaply, so we infer the
 * reason from these two signals rather than trying to log every single
 * removeResource() call.
 */
public final class ReelCacheEvictionLog {

    private static final String PREFS       = "reel_cache_eviction_log";
    private static final String KEY_TRIM_AT = "last_trim_at";
    private static final String KEY_TRIM_WHY = "last_trim_reason";
    private static final String WATCHED_PREFIX = "watched_";

    private ReelCacheEvictionLog() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Call from CallxApp.onTrimMemory() whenever a MODERATE/COMPLETE trim actually runs. */
    public static void recordTrim(Context ctx, String reason) {
        try {
            prefs(ctx).edit()
                .putLong(KEY_TRIM_AT, System.currentTimeMillis())
                .putString(KEY_TRIM_WHY, reason)
                .apply();
        } catch (Exception ignored) {}
    }

    /** Call once a reel has been watched >=90% — likely fully downloaded into cache by then. */
    public static void markWatched(Context ctx, String reelId) {
        if (reelId == null) return;
        try {
            prefs(ctx).edit()
                .putLong(WATCHED_PREFIX + reelId, System.currentTimeMillis())
                .apply();
        } catch (Exception ignored) {}
    }

    public static boolean wasEverWatchedEnoughToCache(Context ctx, String reelId) {
        if (reelId == null) return false;
        try {
            return prefs(ctx).contains(WATCHED_PREFIX + reelId);
        } catch (Exception e) {
            return false;
        }
    }

    /** Milliseconds since the last real memory-pressure trim, or -1 if none recorded. */
    public static long msSinceLastTrim(Context ctx) {
        try {
            long at = prefs(ctx).getLong(KEY_TRIM_AT, -1);
            return at < 0 ? -1 : (System.currentTimeMillis() - at);
        } catch (Exception e) {
            return -1;
        }
    }

    public static String lastTrimReason(Context ctx) {
        try {
            return prefs(ctx).getString(KEY_TRIM_WHY, null);
        } catch (Exception e) {
            return null;
        }
    }
}
