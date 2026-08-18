package com.callx.app.utils;

import android.graphics.Bitmap;
import android.util.LruCache;

/**
 * BlurHashPlaceholder — Fast in-memory cache of decoded BlurHash bitmaps.
 *
 * BlurHash decode is cheap (~1 ms for a 32x32 output) but still takes time.
 * This cache ensures the same hash is only decoded once per process lifetime,
 * so every scroll-back shows the placeholder instantly from memory.
 *
 * Usage:
 *   Bitmap placeholder = BlurHashPlaceholder.get(hash, 32, 32);
 *   if (placeholder != null) cv.setMediaBitmap(placeholder);
 */
public final class BlurHashPlaceholder {

    private BlurHashPlaceholder() {}

    // ~50 decoded bitmaps at 32x32 ARGB_8888 = 50 * 4KB ≈ 200KB — trivial.
    private static final LruCache<String, Bitmap> sCache = new LruCache<>(50);

    /**
     * Returns a decoded BlurHash bitmap (width × height), decoding it if not
     * already cached. Returns null if the hash is null/malformed.
     *
     * Call from the main thread — decode is fast (~1 ms) and cache-hits return
     * immediately. For larger output sizes (e.g. 128x128) prefer calling from
     * a background thread and posting the result to the UI.
     */
    public static Bitmap get(String hash, int width, int height) {
        if (hash == null || hash.isEmpty()) return null;
        String key = hash + "_" + width + "_" + height;
        Bitmap cached = sCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap decoded = BlurHash.decode(hash, width, height, 1.0f);
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
