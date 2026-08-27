package com.callx.app.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;

/**
 * ReelMetadataCache — Thread-safe LRU cache for a reel's social metadata
 * (like/comment/share/repost counts + this viewer's liked/saved/following/
 * reposted flags), mirroring HomeFeedMetadataCache's cache-hit-instant
 * pattern for the Reels tab (ViewPager2 + ReelPlayerFragment).
 *
 * GAP THIS FIXES:
 * ReelSocialController#startFirebaseListeners() attaches fresh
 * addValueEventListener calls every time a reel becomes visible (see
 * ReelPlayerFragment's applyVisibleState) and removeFirebaseListeners()
 * detaches them the moment it's swiped off. So swiping back to an
 * already-seen reel re-fetches everything from Firebase from scratch —
 * every count/flag briefly shows default/zero state until the listeners
 * resolve again. Home's feed rows avoid this exact flash via
 * HomeFeedMetadataCache + HomeFeedNetworkBatcher; the Reels feed had no
 * equivalent.
 *
 * HOW IT'S USED:
 *  1. ReelSocialController#startFirebaseListeners() checks this cache
 *     first and paints the last-known snapshot immediately (no spinner /
 *     no zero-count flash) *before* attaching the real-time listeners.
 *  2. The live listeners then attach as before and correct anything that
 *     changed since — this cache never replaces live data, it only masks
 *     the round-trip latency on revisits.
 *  3. Every listener update re-syncs its field(s) into this cache so the
 *     *next* revisit (swipe back again) is instant too.
 *
 * LruCache bounds memory: entries are small flat POJOs (no captions/media),
 * so 512 reels' worth costs well under 1MB.
 */
public class ReelMetadataCache {

    public static class Snapshot {
        public String reelId;
        public int likeCount = 0;
        public int commentCount = 0;
        public int sharesCount = 0;
        public int repostCount = 0;
        public int viewCount = 0;
        public boolean isLiked = false;
        public boolean isSaved = false;
        public boolean isFollowing = false;
        public boolean followCheckLoaded = false;
        public boolean isReposted = false;
        public long fetchedAt = 0;

        public Snapshot() {}

        public Snapshot(String reelId) {
            this.reelId = reelId;
        }

        @Override public String toString() {
            return "Snapshot{" + reelId + " likes=" + likeCount + " isLiked=" + isLiked + '}';
        }
    }

    private static final int MAX_ENTRIES = 512;

    private static volatile ReelMetadataCache instance;

    public static ReelMetadataCache getInstance() {
        if (instance == null) {
            synchronized (ReelMetadataCache.class) {
                if (instance == null) instance = new ReelMetadataCache(MAX_ENTRIES);
            }
        }
        return instance;
    }

    private final LruCache<String, Snapshot> cache;
    private volatile long totalHits = 0;
    private volatile long totalMisses = 0;

    public ReelMetadataCache(int maxEntries) {
        this.cache = new LruCache<String, Snapshot>(maxEntries) {
            @Override
            protected int sizeOf(String key, Snapshot value) {
                // Small flat POJO (a handful of ints/booleans) — no caption
                // or media payload like HomeFeedMetadataCache carries.
                return 200;
            }
        };
    }

    public void put(@NonNull String reelId, @NonNull Snapshot snapshot) {
        snapshot.reelId = reelId;
        snapshot.fetchedAt = System.currentTimeMillis();
        cache.put(reelId, snapshot);
    }

    @Nullable
    public Snapshot get(@NonNull String reelId) {
        Snapshot s = cache.get(reelId);
        if (s != null) totalHits++;
        else totalMisses++;
        return s;
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

    public int maxSize() {
        return cache.maxSize();
    }

    public long getTotalHits() {
        return totalHits;
    }

    public long getTotalMisses() {
        return totalMisses;
    }

    public double getHitRate() {
        long total = totalHits + totalMisses;
        return total == 0 ? 0 : (double) totalHits / total;
    }

    @Override public String toString() {
        return String.format("ReelMetadataCache{size=%d, maxSize=%d, hitRate=%.2f}",
            size(), maxSize(), getHitRate() * 100);
    }
}
