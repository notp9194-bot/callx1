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

    // Advance #4: dedicated decode pool for getAsync() — separate from IO
    // (disk persist/warm-up) so a burst of fast-scroll cache-misses never
    // queues behind, or blocks, disk writes. Sized to half the cores (min
    // 2): ThumbHash.decode() on a 32x32 target is cheap CPU work, so a
    // small pool avoids over-subscribing during a heavy fling while still
    // parallelizing enough that N misses in one frame don't serialize.
    private static final ExecutorService DECODE_POOL = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

    private static final android.os.Handler MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static volatile Context sAppContext;

    /** Callback for {@link #getAsync}. Always invoked on the main thread. */
    public interface Callback {
        void onReady(Bitmap bitmap);
    }

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

    /**
     * Advance #4 — non-blocking counterpart of {@link #get}. Call this from
     * bind() instead of get() so a cold ThumbHash.decode() (cache miss)
     * never runs on the main thread and cannot contribute to a
     * RecyclerView scroll jank/frame-drop, exactly like the existing
     * resolveVideoBlurHashAsync/resolveFullMediaKeyAsync pattern elsewhere
     * in the adapter — check the holder's bind token inside the callback
     * before applying the result, since a fast fling can recycle/rebind
     * the row before the decode finishes.
     *
     * L1 cache hit (the overwhelming common case once warmUpFromDisk() has
     * run and/or the row has been bound before) is still resolved
     * synchronously and callback.onReady() is invoked immediately, inline,
     * on the calling thread — no executor hop, no extra frame of latency,
     * identical behavior to get() for a hit. Only a genuine miss is pushed
     * onto DECODE_POOL, with the result posted back via MAIN_HANDLER.
     */
    public static void getAsync(String hash, int width, int height, Callback callback) {
        if (hash == null || hash.isEmpty()) {
            callback.onReady(null);
            return;
        }
        String key = hash + "_" + width + "_" + height;
        Bitmap cached = sCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            callback.onReady(cached);
            return;
        }
        final String hashF = hash;
        DECODE_POOL.execute(() -> {
            // Re-check: another in-flight decode for the same key (e.g. two
            // rows sharing a hash) may have already populated L1 while this
            // task waited in the pool queue — avoids a redundant decode.
            Bitmap already = sCache.get(key);
            final Bitmap decoded = (already != null && !already.isRecycled())
                    ? already : ThumbHash.decode(hashF, width, height);
            if (decoded != null && (already == null || already.isRecycled())) {
                sCache.put(key, decoded);
                persistAsync(key, width, height, decoded);
            }
            MAIN_HANDLER.post(() -> callback.onReady(decoded));
        });
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
