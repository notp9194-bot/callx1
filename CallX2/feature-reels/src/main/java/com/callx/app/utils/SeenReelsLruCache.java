package com.callx.app.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SeenReelsLruCache
 *
 * Process-wide, session-local "have I just seen this reel" cache.
 *
 * WHY THIS EXISTS:
 * HomeFeedWatchTracker.recordView() already guards against double-counting a
 * view via its own in-memory viewCounted Set — but that Set lives on the
 * HomeFeedWatchTracker instance, which gets recreated (fresh, empty) every
 * time the Reels tab / fragment is recreated (nav away and back, config
 * change, etc). Without this cache, scrolling back onto a reel you already
 * watched 30 seconds ago triggers a full Firebase read
 * (reelViews/{reelId}/{uid}) all over again, purely to re-confirm something
 * this session already knows.
 *
 * This is intentionally NOT the permanent seen record — that already exists
 * server-side via reelWatchHistory/{uid}/{reelId} (written by
 * HomeFeedWatchTracker.markWatchHistory) and is what /reels/rank reads on
 * the server to rank feed candidates. This class is just a cheap first-line
 * check to skip redundant network round-trips within one app session; it is
 * NEVER treated as authoritative and is fine to lose on process death.
 *
 * Bounded via LinkedHashMap in access-order mode acting as a true LRU: once
 * MAX_ENTRIES is hit, the least-recently-touched reelId is evicted first.
 * All methods are synchronized — HomeFeedWatchTracker calls can arrive from
 * the main thread while a background prefetcher may also touch this.
 */
public final class SeenReelsLruCache {

    private static final int MAX_ENTRIES = 400;

    private static final SeenReelsLruCache INSTANCE = new SeenReelsLruCache();

    /** Value is unused (Boolean.TRUE) — this is really an LRU set, backed by
     *  LinkedHashMap because Java has no built-in LinkedHashSet with an
     *  access-order + eviction hook. */
    private final LinkedHashMap<String, Boolean> map;

    private SeenReelsLruCache() {
        map = new LinkedHashMap<String, Boolean>(MAX_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    }

    public static SeenReelsLruCache getInstance() {
        return INSTANCE;
    }

    /** True if this reelId was marked seen earlier this session. */
    public synchronized boolean isSeen(String reelId) {
        if (reelId == null) return false;
        return map.containsKey(reelId);
    }

    /** Marks a reel as seen this session; touches it to the MRU end. */
    public synchronized void markSeen(String reelId) {
        if (reelId == null) return;
        map.put(reelId, Boolean.TRUE);
    }

    /** Clears the cache — e.g. on logout, so the next user's session starts clean. */
    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }
}
