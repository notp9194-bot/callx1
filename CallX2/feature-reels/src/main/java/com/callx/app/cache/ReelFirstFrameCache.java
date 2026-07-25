package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.util.LruCache;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelFirstFrameCache — PERF advance #4: "first-frame pre-render".
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
 * This class does the same: MediaMetadataRetriever.getFrameAtTime(0) on a
 * background thread, decoded at a small target size (matches the ivThumb
 * override(480,853) size already used for Glide thumbs — no point caching
 * bigger), result kept in a small in-memory LRU. ReelPlayerController
 * swaps ivThumb's bitmap to this decoded frame (when ready) instead of the
 * Glide-loaded static thumbnail, so there's no visible jump when playback
 * actually starts.
 *
 * Deliberately conservative about when it runs: only attempts a decode
 * when the video is already substantially cached locally (checked via
 * ReelCacheManager) — MediaMetadataRetriever.setDataSource() on a cold
 * remote URL can itself take a real network round trip, which would
 * defeat the purpose and burn data/battery for a reel that may never be
 * watched.
 */
public final class ReelFirstFrameCache {

    private static final String TAG            = "FirstFrameCache";
    private static final int    MAX_ENTRIES     = 6;   // small — these are decode-once, short-lived hints
    private static final int    TARGET_WIDTH_PX = 480; // matches ReelPlayerController's ivThumb override()
    // Same threshold ReelPlayerController.MIN_CACHED_BYTES uses for
    // "is this reel's video substantially local already" checks.
    private static final long   MIN_CACHED_BYTES_FOR_DECODE = 400_000L; // ~400KB

    private static volatile ReelFirstFrameCache instance;

    private final Context appCtx;
    private final LruCache<String, Bitmap> cache = new LruCache<>(MAX_ENTRIES);
    private final Set<String> inFlight = new HashSet<>();
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

    /** Non-blocking cache lookup — returns immediately, null if not (yet) decoded. */
    public Bitmap getCached(String url) {
        if (url == null) return null;
        return cache.get(url);
    }

    /**
     * Kicks off a background decode of the first frame if not already
     * cached/in-flight, and not skippable per the cold-URL guard above.
     * Safe to call speculatively (e.g. from prewarmPlayer()) — it's a
     * no-op if the reel is never actually swiped to.
     */
    public void decodeFirstFrameAsync(String videoUrl, Callback callback) {
        if (videoUrl == null || videoUrl.isEmpty()) return;

        Bitmap existing = cache.get(videoUrl);
        if (existing != null) {
            if (callback != null) callback.onFrameReady(existing);
            return;
        }

        synchronized (inFlight) {
            if (inFlight.contains(videoUrl)) return;
            inFlight.add(videoUrl);
        }

        // Only worth attempting if the video is already substantially local
        // — otherwise MediaMetadataRetriever would itself trigger a fresh
        // network fetch, which defeats the point of a "pre-render".
        if (ReelCacheManager.getCachedBytes(videoUrl) < MIN_CACHED_BYTES_FOR_DECODE) {
            synchronized (inFlight) { inFlight.remove(videoUrl); }
            return;
        }

        executor.submit(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(videoUrl, new java.util.HashMap<>());
                Bitmap raw = retriever.getFrameAtTime(0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (raw != null) {
                    frame = downscale(raw, TARGET_WIDTH_PX);
                    if (frame != raw) raw.recycle();
                }
            } catch (Exception e) {
                Log.w(TAG, "decodeFirstFrameAsync failed: " + e.getMessage());
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
                synchronized (inFlight) { inFlight.remove(videoUrl); }
            }

            if (frame != null) {
                cache.put(videoUrl, frame);
                if (callback != null) {
                    final Bitmap f = frame;
                    new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(() -> callback.onFrameReady(f));
                }
            }
        });
    }

    private static Bitmap downscale(Bitmap src, int targetWidth) {
        if (src.getWidth() <= targetWidth) return src;
        float scale = (float) targetWidth / src.getWidth();
        int targetHeight = Math.round(src.getHeight() * scale);
        return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true);
    }

    public void clear() {
        cache.evictAll();
    }
}
