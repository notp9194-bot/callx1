package com.callx.app.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;

import com.callx.app.models.ReelModel;

import java.util.List;

/**
 * ReelModelCache — process-wide LRU cache of full {@link ReelModel} objects,
 * keyed by reelId.
 *
 * WHY THIS EXISTS (perf):
 * Before this, tapping a reel from the home feed / trending row / continue-
 * watching row opened {@code SingleReelPlayerActivity}, which re-fetched
 * EVERY reel in that row all over again from Firebase via
 * {@code loadByReelIds()} — one {@code addListenerForSingleValueEvent} per
 * reelId — even though HomeFragment already had the exact same
 * {@link ReelModel} objects sitting in {@code currentFeedPosts} a moment
 * earlier. For a feed row of 20-40 posts that's 20-40 avoidable network
 * round-trips standing between the tap and the first frame — the opposite
 * of Instagram's instant "you already had this data, just show it" open.
 *
 * HOW IT'S USED:
 *  1. HomeFragment primes this cache with the full row/feed list right
 *     before launching {@code SingleReelPlayerActivity} (see
 *     {@code openReelWithContext()}).
 *  2. {@code SingleReelPlayerActivity#loadByReelIds()} checks this cache
 *     first for every id. Only ids that miss fall back to a Firebase read —
 *     and whatever IS fetched gets written back here, so a second visit
 *     (e.g. backing out and re-opening, or another row containing the same
 *     reel) is instant too.
 *  3. This is a display-data cache only (captions, urls, sticker/text
 *     overlay JSON, etc.) — it intentionally does NOT cache live/mutable
 *     counters (likes, comments, follow state); that stays on
 *     {@link ReelMetadataCache}, which already exists for exactly that and
 *     is refreshed by live Firebase listeners regardless of this cache.
 *
 * LruCache bounds memory: ReelModel is a fairly large flat POJO (~130
 * fields incl. sticker/text-overlay JSON blobs), so the entry count is kept
 * well below ReelMetadataCache's — enough to cover a few feed screens'
 * worth of scroll-back, not the whole session.
 */
public class ReelModelCache {

    private static final int MAX_ENTRIES = 150;

    private static volatile ReelModelCache instance;

    public static ReelModelCache getInstance() {
        if (instance == null) {
            synchronized (ReelModelCache.class) {
                if (instance == null) instance = new ReelModelCache(MAX_ENTRIES);
            }
        }
        return instance;
    }

    private final LruCache<String, ReelModel> cache;
    private volatile long totalHits = 0;
    private volatile long totalMisses = 0;

    public ReelModelCache(int maxEntries) {
        this.cache = new LruCache<>(maxEntries);
    }

    public void put(@NonNull ReelModel reel) {
        if (reel == null || reel.reelId == null || reel.reelId.isEmpty()) return;
        cache.put(reel.reelId, reel);
    }

    /** Bulk-primes the cache — e.g. an entire feed/row right before opening
     *  the fullscreen player, so every reel in it is a guaranteed cache hit. */
    public void putAll(@Nullable List<ReelModel> reels) {
        if (reels == null) return;
        for (ReelModel r : reels) put(r);
    }

    @Nullable
    public ReelModel get(@NonNull String reelId) {
        ReelModel r = cache.get(reelId);
        if (r != null) totalHits++;
        else totalMisses++;
        return r;
    }

    public void remove(@NonNull String reelId) {
        cache.remove(reelId);
    }

    public void clear() {
        cache.evictAll();
    }

    public int size() {
        return cache.size();
    }

    public double getHitRate() {
        long total = totalHits + totalMisses;
        return total == 0 ? 0 : (double) totalHits / total;
    }

    @Override public String toString() {
        return String.format("ReelModelCache{size=%d/%d, hitRate=%.2f%%}",
            size(), MAX_ENTRIES, getHitRate() * 100);
    }
}
