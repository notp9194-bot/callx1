package com.callx.app.cache;

import android.util.LruCache;

/**
 * ReelCardMetaCache — process-wide cache for the reel status card header (owner / duration / exists).
 *
 * Before: these lived in StatusViewerActivity instance fields → thrown away every time the viewer closed, so the
 * same reel re-fetched owner + duration (3-4 Firebase reads) on every open and the header popped in late.
 * Now: static LRU with a TTL, so a reel seen once renders its header instantly (also offline) for the rest of the
 * process. TTL keeps username changes / deleted reels from going stale forever; failures get a short TTL.
 */
public final class ReelCardMetaCache {
    private ReelCardMetaCache() {}

    private static final int MAX_ENTRIES = 256;
    public static final long TTL_DEFAULT_MS  = 10L * 60_000L;   // owner / exists
    public static final long TTL_SHORT_MS    = 60_000L;         // unresolved / failed lookups
    public static final long TTL_DURATION_MS = 60L * 60_000L;   // a reel's length never changes

    /** reelId → {uid, name, username, avatar} */
    public static final Store<String[]> OWNER = new Store<>(TTL_DEFAULT_MS);
    /** reelId → does reels/{id} exist */
    public static final Store<Boolean> EXISTS = new Store<>(TTL_DEFAULT_MS);
    /** reelId → duration in seconds */
    public static final Store<Integer> DURATION = new Store<>(TTL_DURATION_MS);

    private static final class Entry<V> {
        final V value; final long expiresAt;
        Entry(V v, long e) { value = v; expiresAt = e; }
    }

    /** Small TTL + LRU map (thread-safe: LruCache is synchronized internally). */
    public static final class Store<V> {
        private final LruCache<String, Entry<V>> lru = new LruCache<>(MAX_ENTRIES);
        private final long defaultTtlMs;
        Store(long ttl) { defaultTtlMs = ttl; }

        public V get(String key) {
            if (key == null) return null;
            final Entry<V> e = lru.get(key);
            if (e == null) return null;
            if (System.currentTimeMillis() > e.expiresAt) { lru.remove(key); return null; }
            return e.value;
        }
        public boolean containsKey(String key) { return get(key) != null; }
        public void put(String key, V value) { put(key, value, defaultTtlMs); }
        public void put(String key, V value, long ttlMs) {
            if (key == null || value == null) return;
            lru.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMs));
        }
        public void clear() { lru.evictAll(); }
    }
}
