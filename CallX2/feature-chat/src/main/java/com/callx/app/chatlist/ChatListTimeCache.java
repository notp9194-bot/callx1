package com.callx.app.chatlist;

import android.util.LruCache;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ChatListTimeCache — v85 ultra-perf timestamp formatting cache.
 *
 * WHY THIS EXISTS
 * ───────────────
 * In the chat list, every visible row calls SimpleDateFormat.format() on every
 * bind to convert a lastMessageAt epoch → "7:30 PM" display string.
 *
 * SimpleDateFormat.format() is expensive for two reasons:
 *   1. It is internally synchronized (Java's Date/Calendar machinery uses a
 *      shared Calendar instance under the lock), so on a fast scroll that binds
 *      20 rows simultaneously it becomes a serialisation point.
 *   2. It runs non-trivial string parsing/calendar arithmetic every call.
 *
 * On a 60 FPS device this adds measurable jank — Telegram avoids it by caching
 * formatted time strings and only reformatting when the underlying minute changes.
 *
 * HOW IT WORKS
 * ────────────
 * Key = epochMs / 60_000L  (round down to the current minute).
 * Value = formatted time string, e.g. "07:30 AM".
 *
 * Because the key is minute-granular, two contacts whose last message landed in
 * the same minute share one cache entry. Cache size of 500 entries covers the
 * last ~8 hours of distinct minutes which is more than enough for any chat list.
 *
 * The single shared SimpleDateFormat instance is only accessed inside a
 * synchronized block, but that block is hit at most once per unique minute —
 * cache hits (the common case during scrolling) never acquire the lock.
 *
 * USAGE
 *   // in adapter onBind:
 *   String label = (when != null && when > 0)
 *           ? ChatListTimeCache.getFormatted(when) : "";
 */
public final class ChatListTimeCache {

    private static final int CACHE_SIZE = 500;

    // LruCache is thread-safe for get/put
    private static final LruCache<Long, String> sCache = new LruCache<>(CACHE_SIZE);

    // Single shared fmt — only locked inside compute(), not on cache hits
    private static final SimpleDateFormat sFmt =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());

    private ChatListTimeCache() {}

    /**
     * Returns a formatted time string for the given epoch milliseconds.
     * Results are cached by minute — O(1) on a cache hit, no allocation.
     *
     * Thread-safe. Call from any thread.
     *
     * @param epochMs  lastMessageAt value from the User/Group model
     * @return         formatted string like "07:30 AM", never null
     */
    public static String getFormatted(long epochMs) {
        long minuteKey = epochMs / 60_000L;
        String cached = sCache.get(minuteKey);
        if (cached != null) return cached;

        // Cache miss — format once and store. Synchronize only this rare path.
        String result;
        synchronized (sFmt) {
            result = sFmt.format(new Date(epochMs));
        }
        sCache.put(minuteKey, result);
        return result;
    }

    /** Clears the cache (call on locale/timezone change if you handle that). */
    public static void invalidate() {
        sCache.evictAll();
    }
}
