package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import android.widget.ImageView;

/**
 * ReelThumbBitmapCache — PERF advance: "bitmap memory cache for thumbnails (LRU)".
 *
 * ReelThumbnailPreloader already tells Glide to warm its own RAM+disk cache
 * for the next few thumbnails (see that class' javadoc), which avoids a
 * network fetch on swipe. It does NOT avoid Glide re-decoding the bytes back
 * into a Bitmap + re-running its transformation pipeline (centerCrop etc.)
 * every single time an ImageView asks for that URL again — which happens a
 * lot in this feed: ReelPlayerController re-shows ivThumb on every
 * loop/replay-from-scratch, on ABR codec fallback retries, and whenever a
 * ViewPager2 fragment gets rebuilt (e.g. after a config change with
 * offscreenPageLimit recycling). Each of those is a full Glide request
 * (main-thread bookkeeping + a decode-thread bitmap decode + transform) for
 * pixels this process already had in memory moments earlier.
 *
 * This class sits in front of Glide as a small explicit
 * {@code LruCache<String, Bitmap>} keyed by thumbUrl, sized to a fraction of
 * the app's max heap (not a fixed entry count — thumb bitmaps are decoded at
 * a fixed 480x853 target size already, so sizing by bytes keeps memory
 * bounded regardless of density). {@link #loadInto} is the drop-in
 * replacement for the {@code Glide.with(ctx).load(url)...into(iv)} call
 * sites in ReelPlayerController: a cache hit sets the bitmap synchronously
 * on the main thread (no decode-thread hop, no placeholder flash); a miss
 * falls through to Glide exactly as before, with a listener that populates
 * this cache once Glide finishes so the *next* time is a hit.
 *
 * ReelThumbnailPreloader also decodes eagerly (via {@link #prefetch}) so the
 * cache is often already warm by the time the user actually swipes to a
 * reel, not just after the first time it's shown.
 */
public final class ReelThumbBitmapCache {

    private static final String TAG = "ThumbBitmapCache";

    /** Same target size ReelPlayerController/ReelThumbnailPreloader already decode at. */
    public static final int TARGET_W = 480;
    public static final int TARGET_H = 853;

    private static volatile ReelThumbBitmapCache instance;

    private final LruCache<String, Bitmap> cache;
    private final RequestOptions options;

    private ReelThumbBitmapCache() {
        // Cap the cache at 1/8th of the app's max heap, same rule of thumb
        // Glide's own default BitmapPool sizing uses — generous enough to
        // hold the whole positional prewarm window (N-1..N+3, ~5 bitmaps at
        // 480x853x4 bytes ≈ 1.6MB each ≈ 8MB) without competing with
        // ExoPlayer's own decode buffers for RAM.
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024) / 8;
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        options = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .transform(new CenterCrop())
            .override(TARGET_W, TARGET_H);
    }

    public static ReelThumbBitmapCache get() {
        if (instance == null) {
            synchronized (ReelThumbBitmapCache.class) {
                if (instance == null) instance = new ReelThumbBitmapCache();
            }
        }
        return instance;
    }

    /** Synchronous, non-blocking lookup — null if not (yet) decoded/cached. */
    @Nullable
    public Bitmap getCached(String url) {
        if (url == null) return null;
        return cache.get(url);
    }

    /**
     * Drop-in replacement for {@code Glide.with(ctx).load(url).into(iv)} at
     * thumbnail bind sites. Cache hit → bitmap applied immediately on the
     * calling (main) thread, zero Glide overhead. Cache miss → delegates to
     * Glide as before, and remembers the decoded bitmap for next time.
     */
    public void loadInto(Context ctx, ImageView target, @Nullable String url) {
        if (url == null || url.isEmpty()) return;

        Bitmap hit = cache.get(url);
        if (hit != null && !hit.isRecycled()) {
            target.setImageBitmap(hit);
            return;
        }

        Glide.with(ctx)
            .asBitmap()
            .load(url)
            .apply(options)
            .into(new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(androidx.annotation.NonNull Bitmap resource,
                                             @Nullable Transition<? super Bitmap> transition) {
                    cache.put(url, resource);
                    target.setImageBitmap(resource);
                }

                @Override
                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                    // No-op — the ImageView is being recycled/rebound elsewhere.
                }
            });
    }

    /**
     * Speculative decode-and-cache with no ImageView attached — used by
     * ReelThumbnailPreloader so the bitmap is already sitting in this cache
     * (not just Glide's own cache) by the time the reel is actually swiped to.
     */
    public void prefetch(Context ctx, @Nullable String url) {
        if (url == null || url.isEmpty() || cache.get(url) != null) return;

        Glide.with(ctx.getApplicationContext())
            .asBitmap()
            .load(url)
            .apply(options)
            .into(new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(androidx.annotation.NonNull Bitmap resource,
                                             @Nullable Transition<? super Bitmap> transition) {
                    cache.put(url, resource);
                }

                @Override
                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) { }
            });
    }

    public void clear() {
        cache.evictAll();
        Log.d(TAG, "cache cleared");
    }
}
