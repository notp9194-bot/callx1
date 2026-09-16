package com.callx.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.util.LruCache;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ThumbHashCacheEntity;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ThumbHashPlaceholder — Fast two-tier cache of decoded ThumbHash bitmaps.
 * Direct swap-in for BlurHashPlaceholder: same cache-key/size shape, just
 * backed by ThumbHash.decode() instead of BlurHash.decode().
 *
 * L1 = in-memory LruCache(50), same as before — get() stays fully
 * synchronous and safe to call from the main thread, unchanged.
 *
 * Decoded bitmaps are RGB_565 for opaque hashes (advance #2 — half the
 * memory of ARGB_8888, faster blur pass) and ARGB_8888 only for hashes
 * with real alpha (stickers/transparent PNGs) — see ThumbHash.decode().
 *
 * L2 = Room-backed disk cache (advance #1) — every fresh decode is
 * write-through persisted to disk on a background thread, and
 * warmUpFromDisk() (call once, e.g. from CallxApp.onCreate()) reloads the
 * most-recently-used rows straight into the L1 LruCache in the
 * background, so a cold app restart/process-death still gets instant
 * placeholders without re-running ThumbHash.decode() once RecyclerView
 * rows start binding — all off the main thread.
 *
 * Usage:
 *   Bitmap placeholder = ThumbHashPlaceholder.get(hash, 32, 32);
 *   if (placeholder != null) cv.setMediaBitmap(placeholder);
 */
public final class ThumbHashPlaceholder {

    private ThumbHashPlaceholder() {}

    private static final String TAG = "ThumbHashPlaceholder";

    // ~50 decoded bitmaps at 32x32 — RGB_565 (opaque hashes, the common
    // case) ≈ 2KB each, ARGB_8888 (alpha hashes) ≈ 4KB each — trivial
    // either way, ≤200KB worst case.
    private static final int CACHE_SIZE = 50;
    private static final LruCache<String, Bitmap> sCache = new LruCache<>(CACHE_SIZE);

    // Disk L2 hygiene: rows untouched for a month are dropped so the table
    // doesn't grow forever across months of app use.
    private static final long PRUNE_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static volatile Context sAppContext;

    /**
     * Call once, early (e.g. CallxApp.onCreate()), to enable the disk L2
     * tier and kick off the background warm-up of the L1 LruCache from the
     * most-recently-cached rows on disk. Safe to skip — get() keeps working
     * memory-only (identical to before this change) if this is never called.
     */
    public static void warmUpFromDisk(Context ctx) {
        if (ctx == null) return;
        sAppContext = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(sAppContext);
                List<ThumbHashCacheEntity> rows = db.thumbHashCacheDao().getRecent(CACHE_SIZE);
                for (ThumbHashCacheEntity e : rows) {
                    if (e.pixels == null || e.width <= 0 || e.height <= 0) continue;
                    try {
                        Bitmap.Config cfg = parseConfig(e.configName);
                        Bitmap bmp = Bitmap.createBitmap(e.width, e.height, cfg);
                        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(e.pixels));
                        sCache.put(e.cacheKey, bmp);
                    } catch (Exception rowIgnored) {
                        // Corrupt/mismatched row (e.g. pixel bytes don't match
                        // this config's byte-per-pixel size) — skip it, rest
                        // of warm-up continues; get() will just re-decode.
                    }
                }
                db.thumbHashCacheDao().pruneOlderThan(System.currentTimeMillis() - PRUNE_AGE_MS);
            } catch (Exception e) {
                // Disk cache is purely an optimization — memory cache + decode
                // fallback in get() below still works fine even if this fails.
                Log.w(TAG, "warmUpFromDisk failed, continuing memory-only", e);
            }
        });
    }

    /**
     * Returns a decoded ThumbHash bitmap (width × height), decoding it if not
     * already cached. Returns null if the hash is null/malformed/empty —
     * including old BlurHash-format strings left over from before this
     * migration, so callers fall back gracefully instead of crashing.
     *
     * Call from the main thread — an L1 hit returns immediately, and a miss
     * only ever does the same synchronous ThumbHash.decode() as before
     * (write-through persistence to the L2 disk cache happens afterwards on
     * a background thread and never delays the returned bitmap).
     */
    public static Bitmap get(String hash, int width, int height) {
        if (hash == null || hash.isEmpty()) return null;
        String key = hash + "_" + width + "_" + height;
        Bitmap cached = sCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap decoded = ThumbHash.decode(hash, width, height);
        if (decoded != null) {
            sCache.put(key, decoded);
            persistAsync(key, width, height, decoded);
        }
        return decoded;
    }

    /** Fire-and-forget write-through to the L2 disk cache. No-op until
     *  warmUpFromDisk(Context) has been called at least once. Captures
     *  whichever config the decoded bitmap actually is (RGB_565 for opaque
     *  hashes, ARGB_8888 for hashes with real alpha — see ThumbHash.decode)
     *  so warm-up can reconstruct it correctly later. */
    private static void persistAsync(String key, int width, int height, Bitmap bmp) {
        Context ctx = sAppContext;
        if (ctx == null) return;
        Bitmap.Config cfg = bmp.getConfig();
        IO.execute(() -> {
            try {
                ByteBuffer buf = ByteBuffer.allocate(bmp.getByteCount());
                bmp.copyPixelsToBuffer(buf);
                ThumbHashCacheEntity e = new ThumbHashCacheEntity();
                e.cacheKey = key;
                e.width = width;
                e.height = height;
                e.pixels = buf.array();
                e.configName = cfg != null ? cfg.name() : "ARGB_8888";
                AppDatabase.getInstance(ctx).thumbHashCacheDao().insert(e);
            } catch (Exception ex) {
                // Non-critical — memory cache still has it for this session.
                Log.w(TAG, "disk persist failed for a placeholder, continuing", ex);
            }
        });
    }

    private static Bitmap.Config parseConfig(String name) {
        if (name != null) {
            try {
                return Bitmap.Config.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // Unknown/corrupt value — fall through to the safe default.
            }
        }
        return Bitmap.Config.ARGB_8888;
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
