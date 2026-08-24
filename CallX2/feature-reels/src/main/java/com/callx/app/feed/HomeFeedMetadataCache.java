package com.callx.app.feed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;

/**
 * HomeFeedMetadataCache — Thread-safe LRU cache for post metadata that rarely
 * fits in-memory on first fetch. Stores:
 *  • likeCount, commentCount, repostCount (accurate as of last Firebase fetch)
 *  • isFollowing (whether user follows this post's creator)
 *  • isLiked, isSaved (user's interaction flags)
 *  • captionPreview (first 150 chars for rendering faster)
 *  • fetchedAt (timestamp for TTL expiry if desired)
 *
 * LruCache bounds memory: keeps ~1024 posts worth of metadata (~5KB per post
 * = ~5MB max, tunable). When a new post is cached and cache is full, LRU
 * evicts the least-recently-used entry.
 *
 * Typical flow:
 *  1. HomeFragment starts scrolling, hits postIndex 10
 *  2. addFeedPostCard() → onCardBoundAtIndex() → prefetchPostMetadata()
 *  3. networkBatcher collects read requests, fires a batch
 *  4. Firebase callback → metadataCache.put(reelId, metadata)
 *  5. PostRowHolder's next bind (or immediate if already on screen) reads
 *     cache → instant display of counts, no spinner
 */
public class HomeFeedMetadataCache {

    public static class PostMetadata {
        public String reelId;
        public int likeCount = 0;
        public int commentCount = 0;
        public int repostCount = 0;
        public boolean isFollowing = false;
        public boolean isLiked = false;
        public boolean isSaved = false;
        public String captionPreview = "";
        public long fetchedAt = 0;

        public PostMetadata() {}

        public PostMetadata(String reelId) {
            this.reelId = reelId;
        }

        @Override public String toString() {
            return "PostMetadata{" + reelId + " likes=" + likeCount + '}';
        }
    }

    public interface MetadataCallback {
        void onMetadata(@NonNull PostMetadata metadata);
    }

    private final LruCache<String, PostMetadata> cache;
    private volatile long totalHits = 0;
    private volatile long totalMisses = 0;

    public HomeFeedMetadataCache(int maxEntries) {
        this.cache = new LruCache<String, PostMetadata>(maxEntries) {
            @Override
            protected int sizeOf(String key, PostMetadata value) {
                // ~5KB per entry (estimated): 500 bytes overhead + caption
                return 5120;
            }
        };
    }

    public void put(@NonNull String reelId, @NonNull PostMetadata metadata) {
        metadata.reelId = reelId;
        metadata.fetchedAt = System.currentTimeMillis();
        cache.put(reelId, metadata);
    }

    @Nullable
    public PostMetadata get(@NonNull String reelId) {
        PostMetadata m = cache.get(reelId);
        if (m != null) totalHits++;
        else totalMisses++;
        return m;
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

    // Debugging snapshot
    @Override public String toString() {
        return String.format("HomeFeedMetadataCache{size=%d, maxSize=%d, hitRate=%.2f}",
            size(), maxSize(), getHitRate() * 100);
    }
}
