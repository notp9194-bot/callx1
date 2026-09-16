package com.callx.app.utils;

import android.graphics.Bitmap;
import android.util.LruCache;

/**
 * ThumbHashPlaceholder — Fast in-memory cache of decoded ThumbHash bitmaps.
 * Direct swap-in for BlurHashPlaceholder: same cache-key/size shape, just
 * backed by ThumbHash.decode() instead of BlurHash.decode().
 *
 * Usage:
 *   Bitmap placeholder = ThumbHashPlaceholder.get(hash, 32, 32);
 *   if (placeholder != null) cv.setMediaBitmap(placeholder);
 */
public final class ThumbHashPlaceholder {

    private ThumbHashPlaceholder() {}

    // ~50 decoded bitmaps at 32x32 ARGB_8888 = 50 * 4KB ≈ 200KB — trivial.
    private static final LruCache<String, Bitmap> sCache = new LruCache<>(50);

    /**
     * Returns a decoded ThumbHash bitmap (width × height), decoding it if not
     * already cached. Returns null if the hash is null/malformed/empty —
     * including old BlurHash-format strings left over from before this
     * migration, so callers fall back gracefully instead of crashing.
     *
     * Call from the main thread — decode is fast and cache-hits return
     * immediately. For larger output sizes prefer calling from a background
     * thread and posting the result to the UI.
     */
    public static Bitmap get(String hash, int width, int height) {
        if (hash == null || hash.isEmpty()) return null;
        String key = hash + "_" + width + "_" + height;
        Bitmap cached = sCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap decoded = ThumbHash.decode(hash, width, height);
        if (decoded != null) sCache.put(key, decoded);
        return decoded;
    }

    /** Pre-populate the cache (optional, call on a background thread). */
    public static void preload(String hash) {
        if (hash == null || hash.isEmpty()) return;
        get(hash, 32, 32);
    }

    public static void clear() {
        sCache.evictAll();
    }
}
