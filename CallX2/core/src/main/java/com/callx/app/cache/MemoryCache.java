package com.callx.app.cache;

import android.util.LruCache;

import java.util.ArrayList;
import java.util.List;

/**
 * Tier-1: Smart RAM cache with dynamic sizing.
 *
 * FIX #3 (HIGH): sizeOf() unit mismatch corrected.
 *   Old code: LruCache maxSize = maxMemory/8 (KB), sizeOf = list.size()*2 (no unit)
 *   → Units were inconsistent. LruCache thought each entry was "2 slots"
 *     but the total budget was in KB → actual RAM could far exceed the limit → OOM.
 *   Fix: LruCache maxSize is now in ITEMS (not KB), with a hard cap of 2000 items.
 *     sizeOf() returns the actual item count of the list (or 1 for single objects).
 *     This is simpler, correct, and consistent.
 *
 * Additional improvement: volatile double-checked locking on singleton.
 */
public class MemoryCache {

    // Max items in cache. For a 3GB device, 2000 MessageEntity objects ≈ 8-16MB
    // which is well within the safe 1/8 heap budget for most devices.
    private static final int MAX_ITEMS = 2000;

    private static volatile MemoryCache sInstance; // FIX: volatile for DCL safety

    private final LruCache<String, Object> mCache;

    private MemoryCache() {
        mCache = new LruCache<String, Object>(MAX_ITEMS) {
            @Override
            protected int sizeOf(String key, Object value) {
                // FIX #3: consistent units — return item count, max size is item count
                if (value instanceof List) {
                    int size = ((List<?>) value).size();
                    return Math.max(1, size); // minimum 1 so empty lists still count
                }
                return 1; // single objects (UserEntity, etc.) count as 1
            }
        };
    }

    public static MemoryCache getInstance() {
        if (sInstance == null) {
            synchronized (MemoryCache.class) {
                if (sInstance == null) sInstance = new MemoryCache();
            }
        }
        return sInstance;
    }

    public void put(String key, Object value) {
        if (key != null && value != null) {
            mCache.put(key, value);
        }
    }

    public Object get(String key) {
        return mCache.get(key);
    }

    public void remove(String key) {
        mCache.remove(key);
    }

    public void evictAll() {
        mCache.evictAll();
    }

    public int hitCount()  { return mCache.hitCount(); }
    public int missCount() { return mCache.missCount(); }

    /**
     * Keep only the specified keys — evict everything else.
     * Used by CacheManager.evictLowPriority() under memory pressure.
     * LruCache operations are thread-safe internally.
     */
    public void keepOnly(List<String> priorityKeys) {
        java.util.Map<String, Object> snapshot = mCache.snapshot();
        for (String key : new ArrayList<>(snapshot.keySet())) {
            if (!priorityKeys.contains(key)) {
                mCache.remove(key);
            }
        }
    }

    /** Current number of items stored. */
    public int size() {
        return mCache.size();
    }

    /** Maximum item capacity. */
    public int maxSize() {
        return mCache.maxSize();
    }
}
