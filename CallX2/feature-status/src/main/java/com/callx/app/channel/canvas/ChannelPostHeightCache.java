package com.callx.app.channel.canvas;

import android.util.LruCache;

/**
 * ChannelPostHeightCache — process-level LRU cache: postId → measured height px.
 *
 * WHY THIS EXISTS
 * ───────────────
 * onMeasure() on ChannelPostCanvasView is much faster than a View-tree layout pass,
 * but it still runs StaticLayout height queries, renderer geometry arithmetic, and
 * section y-offset computations. For a post that scrolled into view, was measured,
 * and is now scrolled back (past the scrap-cache boundary), we already know its
 * exact pixel height. Returning that cached value from onMeasure() immediately —
 * without re-running any geometry — is the fastest possible measure pass.
 *
 * WHEN IT HELPS
 * ─────────────
 * setItemViewCacheSize(20) keeps 20 recently-scrolled VHs in the scrap cache —
 * those never get rebound at all. This cache kicks in for posts BEYOND those 20:
 * the holder goes to the recycled pool, gets a new post bound, and for that new
 * post a full geometry pass is needed (cache miss). But when the user scrolls back
 * far enough that an already-cached post falls to a recycled holder, we hit the
 * cache and skip all geometry work.
 *
 * WIDTH INVALIDATION
 * ──────────────────
 * Heights are valid only for a specific container width. If the device rotates,
 * call invalidateAll(). Each put() records the width; get() returns -1 on mismatch.
 *
 * THREAD SAFETY
 * ─────────────
 * android.util.LruCache is synchronized internally — safe to put/get from any thread.
 */
public final class ChannelPostHeightCache {

    private static final int MAX_ENTRIES = 400;

    // Singleton — shared across all ChannelViewerActivity instances in this process.
    private static volatile ChannelPostHeightCache sInstance;

    public static ChannelPostHeightCache get() {
        if (sInstance == null) {
            synchronized (ChannelPostHeightCache.class) {
                if (sInstance == null) sInstance = new ChannelPostHeightCache();
            }
        }
        return sInstance;
    }

    private final LruCache<String, Integer> cache = new LruCache<>(MAX_ENTRIES);
    private volatile int lastKnownWidth = -1;

    private ChannelPostHeightCache() {}

    /**
     * Returns cached height for the given postId measured at containerWidth,
     * or -1 on miss or width mismatch.
     */
    public int get(String postId, int containerWidth) {
        if (postId == null || containerWidth != lastKnownWidth) return -1;
        Integer h = cache.get(postId);
        return h != null ? h : -1;
    }

    /**
     * Stores the measured height. Call this after onMeasure() computes a fresh height.
     * Passing heightPx ≤ 0 is a no-op.
     */
    public void put(String postId, int containerWidth, int heightPx) {
        if (postId == null || heightPx <= 0) return;
        lastKnownWidth = containerWidth;
        cache.put(postId, heightPx);
    }

    /**
     * Force-evict a specific post. Call when a post's content changes (pin toggle,
     * edit, deletion) so stale heights are not served.
     */
    public void invalidate(String postId) {
        if (postId != null) cache.remove(postId);
    }

    /**
     * Evict all entries (e.g. on orientation change or theme switch).
     */
    public void invalidateAll() {
        cache.evictAll();
        lastKnownWidth = -1;
    }
}
