package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelFirstFrameCache — PERF advance #4: "first-frame pre-render", v2.
 *
 * Even with the player fully prewarmed (see prewarmPlayer()/ExoPlayerPool),
 * there's a final micro-gap on swipe between the static thumbnail
 * disappearing and the first decoded video frame actually landing on
 * screen — ExoPlayer reports STATE_READY slightly before the first frame
 * is rendered. Instagram hides this by decoding the video's very first
 * frame into a bitmap ahead of time and drawing THAT as the "thumbnail"
 * instead of a separately-uploaded static image — so the transition into
 * real playback is pixel-identical, not just fast.
 *
 * v2 advances over the original pass:
 *  ✅ Decodes from a LOCAL extracted file (ReelCacheManager.extractCachedVideoToFile)
 *     instead of handing MediaMetadataRetriever the raw network URL. The
 *     bytes are already sitting in ExoPlayer's SimpleCache on disk — reading
 *     them straight from there is a plain file read, not a second HTTP
 *     connection to the CDN. Faster, doesn't compete with playback for
 *     network/socket resources, and works even if the CDN throttles
 *     concurrent connections per client.
 *  ✅ Rotation-safe: reads METADATA_KEY_VIDEO_ROTATION and manually rotates
 *     the decoded bitmap. getFrameAtTime() auto-applies rotation on most
 *     OS versions/vendors but not reliably on all of them — a portrait
 *     duet/remix recorded with a 90°/270° rotation tag could otherwise
 *     decode sideways while ExoPlayer (which always respects the tag)
 *     renders it upright, producing exactly the kind of visible "jump"
 *     this whole cache exists to prevent.
 *  ✅ Disk-persisted (small JPEGs, ~30-60KB each) — survives app restarts.
 *     A fresh cold start still benefits immediately for any reel whose
 *     video bytes are still in ReelCacheManager's on-disk SimpleCache from
 *     a previous session, instead of every session starting from zero.
 *  ✅ Lower byte-gate (150KB vs the old 400KB) — safe to lower now that the
 *     decode reads from a local file extract rather than triggering network
 *     I/O directly, so there's no bandwidth/battery cost being risked by
 *     trying earlier.
 *
 * ReelPlayerController / HomeFragment swap ivThumb/thumbView's bitmap to
 * whatever this returns (when ready) instead of the Glide-loaded static
 * thumbnail, so there's no visible jump when playback actually starts.
 */
public final class ReelFirstFrameCache {

    private static final String TAG              = "FirstFrameCache";
    private static final int    MAX_MEM_ENTRIES   = 12;  // in-memory hot set
    private static final int    TARGET_WIDTH_PX   = 480; // matches ReelPlayerController's ivThumb override()
    // Lowered vs v1 (was 400KB) — decode now reads a local file extract, not
    // the network, so there's no bandwidth cost to attempting earlier. Just
    // needs enough bytes to contain the first keyframe/GOP.
    private static final long   MIN_CACHED_BYTES_FOR_DECODE = 150_000L; // ~150KB
    private static final int    JPEG_QUALITY      = 82;

    private static volatile ReelFirstFrameCache instance;

    private final Context appCtx;
    private final LruCache<String, Bitmap> memCache = new LruCache<>(MAX_MEM_ENTRIES);
    // ★ Perceptual-hash cache — parallel to memCache, keyed the same way.
    // Lets revealCardThumbnailAfterFirstFrame()/revealThumbnailAfterFirstFrame()
    // pick a similarity-adaptive crossfade duration without re-hashing the
    // same decoded frame on every reveal.
    private final LruCache<String, Long> hashCache = new LruCache<>(MAX_MEM_ENTRIES);
    private final Set<String> inFlight = new HashSet<>();
    /** PERF advance — "bitmap downsample + reuse": inBitmap-backed buffer
     *  pool for the disk-decode fallback in {@link #getCached(String)}. See
     *  {@link ReusableThumbBitmapPool} class doc for the reuse-safety
     *  argument (only fed by {@link #releaseIfEvicted}). */
    private final ReusableThumbBitmapPool reusePool = new ReusableThumbBitmapPool(3);
    /** Identity set of every bitmap THIS cache has ever produced (disk decode
     *  or fresh MediaMetadataRetriever decode) — {@link #releaseIfEvicted}
     *  consults this before ever handing a bitmap to {@link #reusePool}, so
     *  a bitmap this cache never created (e.g. one still owned by Glide,
     *  which manages its own bitmap pool) can never accidentally be reused/
     *  recycled out from under it. Identity (==), not equals(), since two
     *  different decodes could coincidentally be pixel-equal. */
    private final Set<Bitmap> ownedBitmaps =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "first-frame-decode");
        t.setDaemon(true);
        return t;
    });

    private ReelFirstFrameCache(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    public static ReelFirstFrameCache get(Context ctx) {
        if (instance == null) {
            synchronized (ReelFirstFrameCache.class) {
                if (instance == null) instance = new ReelFirstFrameCache(ctx);
            }
        }
        return instance;
    }

    public interface Callback {
        void onFrameReady(Bitmap bitmap);
    }

    /**
     * Non-blocking cache lookup — returns immediately, null if not (yet)
     * decoded. Checks the in-memory hot set first, then falls back to a
     * synchronous disk read of the persisted JPEG (cheap — these are small,
     * pre-downscaled files, typically <60KB). Keyed the same way
     * decodeFirstFrameAsync() was called — pass the same cacheKey
     * (typically reel.reelId).
     */
    public Bitmap getCached(String cacheKey) {
        if (cacheKey == null) return null;
        Bitmap mem = memCache.get(cacheKey);
        if (mem != null) return mem;

        File diskFile = diskFileFor(cacheKey);
        if (diskFile.exists()) {
            try {
                Bitmap fromDisk = decodeDiskFileWithReuse(diskFile.getAbsolutePath());
                if (fromDisk != null) {
                    ownedBitmaps.add(fromDisk);
                    memCache.put(cacheKey, fromDisk);
                    return fromDisk;
                }
            } catch (Exception e) {
                Log.w(TAG, "getCached: disk decode failed for " + cacheKey + ": " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Decodes a persisted first-frame JPEG, reusing a pooled mutable buffer
     * via {@code BitmapFactory.Options.inBitmap} when one of the right
     * dimensions is available — these files are all downscaled to the same
     * TARGET_WIDTH_PX-derived shape per reel aspect ratio, so repeat decodes
     * of a same-shaped reel (the common case: portrait 9:16 content) hit the
     * pool instead of allocating fresh. Falls back to a plain decode (no
     * reuse) on ANY failure — inBitmap rejects a mismatched buffer with an
     * IllegalArgumentException, which is expected whenever the pool only has
     * a different-shaped buffer on hand, not a bug.
     */
    private Bitmap decodeDiskFileWithReuse(String path) {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(path, bounds);

        Bitmap candidate = reusePool.obtain(bounds.outWidth, bounds.outHeight);
        if (candidate != null) {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inBitmap = candidate;
            opts.inMutable = true;
            try {
                Bitmap decoded = android.graphics.BitmapFactory.decodeFile(path, opts);
                if (decoded != null) return decoded;
            } catch (IllegalArgumentException e) {
                // Reused buffer didn't fit after all (config/allocation-size
                // mismatch) — release it back and fall through to a plain
                // decode below rather than fail the whole lookup.
                Log.d(TAG, "decodeDiskFileWithReuse: inBitmap rejected, plain-decoding instead");
            }
            reusePool.release(candidate);
        }
        return android.graphics.BitmapFactory.decodeFile(path);
    }

    /**
     * Returns a decoded frame's buffer to the reuse pool once it's certain
     * to never be handed out again — call ONLY from the point a card's
     * ViewHolder is being recycled for a different post (HomeFragment's
     * FeedAdapter.onViewRecycled / ReelPlayerController's equivalent), never
     * speculatively. The identity check against the live memCache entry is
     * what makes this safe: if {@code bitmap} is still the current cached
     * object for {@code cacheKey}, some OTHER card could still hand out /
     * be displaying that exact object (e.g. the same reel appears twice in
     * a "suggested" strip), so it's left alone; only a bitmap that has
     * already fallen out of memCache — meaning this recycled View held the
     * last reference to it — is safe to reuse.
     */
    public void releaseIfEvicted(String cacheKey, Bitmap bitmap) {
        if (cacheKey == null || bitmap == null || bitmap.isRecycled()) return;
        // Never touch a bitmap this cache didn't create itself — e.g. the
        // Glide-loaded static thumbnail shown before a first-frame decode
        // lands. Glide owns that bitmap's lifecycle (its own bitmap pool),
        // so reusing/recycling it here would corrupt Glide's own cache.
        if (!ownedBitmaps.contains(bitmap)) return;
        if (memCache.get(cacheKey) == bitmap) return; // still the live entry — not safe to reuse
        ownedBitmaps.remove(bitmap);
        reusePool.release(bitmap);
    }

    /**
     * ★ Perceptual-hash lookup for the decoded first frame — used to decide
     * how short the reveal crossfade can safely be (see PerceptualHashUtil).
     * Lazily computes + caches the hash from whatever getCached() returns
     * (mem or disk) the first time it's asked for, so a disk-only hit after
     * an app restart still works even though hashCache itself doesn't
     * survive process death. Returns null only if the frame itself isn't
     * decoded/cached yet — callers should treat that as "unknown similarity",
     * not "no similarity".
     */
    public Long getFrameHash(String cacheKey) {
        if (cacheKey == null) return null;
        Long cached = hashCache.get(cacheKey);
        if (cached != null) return cached;
        Bitmap bmp = getCached(cacheKey);
        if (bmp == null) return null;
        long hash = PerceptualHashUtil.dHash(bmp);
        hashCache.put(cacheKey, hash);
        return hash;
    }

    /**
     * Kicks off a background decode of the first frame if not already
     * cached/in-flight, and not skippable per the cold-URL guard above.
     * Safe to call speculatively (e.g. from prewarmPlayer()) — it's a
     * no-op if the reel is never actually swiped to.
     *
     * Cached under videoUrl directly — kept for callers that don't have a
     * stabler key handy. Prefer the (cacheKey, mediaUrl) overload below when
     * the same reel can resolve to different playback URLs (different
     * quality picks, HLS vs progressive) across calls — otherwise a decode
     * done ahead of time under the old URL is a cache miss under the new one.
     */
    public void decodeFirstFrameAsync(String videoUrl, Callback callback) {
        decodeFirstFrameAsync(videoUrl, videoUrl, callback);
    }

    /**
     * Same as above, but cached under a caller-supplied stable key
     * (typically reel.reelId) instead of the exact playback URL, so a
     * speculative decode kicked off under one quality/manifest URL is still
     * a hit later even if playback ends up picking a different URL for the
     * same reel.
     */
    public void decodeFirstFrameAsync(String cacheKey, String mediaUrl, Callback callback) {
        if (cacheKey == null || cacheKey.isEmpty() || mediaUrl == null || mediaUrl.isEmpty()) return;

        Bitmap existing = getCached(cacheKey);
        if (existing != null) {
            if (callback != null) callback.onFrameReady(existing);
            return;
        }

        synchronized (inFlight) {
            if (inFlight.contains(cacheKey)) return;
            inFlight.add(cacheKey);
        }

        executor.submit(() -> {
            Bitmap frame = null;
            try {
                // Prefer reading straight off disk (ExoPlayer's SimpleCache,
                // already backing this exact URL) over touching the network
                // at all. extractCachedVideoToFile() sums the cached spans —
                // returns null below MIN_CACHED_BYTES_FOR_DECODE worth, so
                // this naturally also serves as the "substantially local"
                // gate the old version applied separately.
                if (!ReelCacheManager.isInitialized()
                        || ReelCacheManager.getCachedBytes(mediaUrl) < MIN_CACHED_BYTES_FOR_DECODE) {
                    return;
                }
                String localPath = ReelCacheManager.extractCachedVideoToFile(appCtx, mediaUrl, cacheKey);
                if (localPath == null) return;

                frame = decodeFirstFrame(localPath);
            } catch (Exception e) {
                Log.w(TAG, "decodeFirstFrameAsync failed: " + e.getMessage());
            } finally {
                synchronized (inFlight) { inFlight.remove(cacheKey); }
            }

            if (frame != null) {
                final Bitmap f = frame;
                ownedBitmaps.add(f);
                memCache.put(cacheKey, f);
                // ★ hash it now, on this same background thread — a few
                // microseconds here saves doing it later on the main thread
                // inside revealThumbnailAfterFirstFrame()'s hot path
                hashCache.put(cacheKey, PerceptualHashUtil.dHash(f));
                persistToDisk(cacheKey, f);
                if (callback != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(() -> callback.onFrameReady(f));
                }
            }
        });
    }

    /** Decodes + rotation-corrects + downscales frame 0 from a local file path. */
    private static Bitmap decodeFirstFrame(String localFilePath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(localFilePath);
            Bitmap raw = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (raw == null) return null;

            int rotationDeg = 0;
            try {
                String rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rot != null) rotationDeg = Integer.parseInt(rot);
            } catch (Exception ignored) {}

            Bitmap oriented = raw;
            if (rotationDeg != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotationDeg);
                Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                if (rotated != raw) {
                    raw.recycle();
                    oriented = rotated;
                }
            }

            Bitmap scaled = downscale(oriented, TARGET_WIDTH_PX);
            if (scaled != oriented) oriented.recycle();
            return scaled;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private File diskFileFor(String cacheKey) {
        return new File(appCtx.getCacheDir(), "first_frame_" + safeFileName(cacheKey) + ".jpg");
    }

    private static String safeFileName(String key) {
        return key.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private void persistToDisk(String cacheKey, Bitmap bitmap) {
        File out = diskFileFor(cacheKey);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
        } catch (Exception e) {
            Log.w(TAG, "persistToDisk failed for " + cacheKey + ": " + e.getMessage());
        }
    }

    private static Bitmap downscale(Bitmap src, int targetWidth) {
        if (src.getWidth() <= targetWidth) return src;
        float scale = (float) targetWidth / src.getWidth();
        int targetHeight = Math.round(src.getHeight() * scale);
        return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true);
    }

    /**
     * FIX #MEM — trimMemory hook, mirrors ReelCacheManager.trimMemory().
     * Call from the same OS onTrimMemory() signal. Only releases the
     * in-memory hot set; disk-persisted frames are untouched (cheap to
     * re-read on demand, and useful across the low-memory event itself).
     */
    public void trimMemory() {
        memCache.evictAll();
        reusePool.trimMemory();
    }

    public void clear() {
        memCache.evictAll();
        hashCache.evictAll();
        reusePool.trimMemory();
    }
}
