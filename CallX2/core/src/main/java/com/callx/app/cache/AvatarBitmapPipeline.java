package com.callx.app.cache;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import com.callx.app.utils.DeviceTier;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * AvatarBitmapPipeline — the ONE shared implementation of "give a canvas-drawn
 * view a circle-cropped avatar Bitmap" (v821, extracted from ChatAvatarBinder).
 * {@link AvatarBinderCore#bind} covers ImageView targets; canvas views can't be
 * {@code Glide.into()}'d, so ChatAvatarBinder / CommunityAvatarBinder /
 * MiscAvatarBinder each used to carry their own weaker copy of this. Now they
 * are thin wrappers over one instance each (Chat + Community share one — same
 * ChatAvatarL2Cache).
 *
 * Lookup order per bind: strong hot LRU -> module L2 (weak) -> ONE Glide load
 * per url shared by every caller asking for it.
 *
 *  - HOT CACHE: a small byte-bounded LruCache of strong refs, so scroll-back
 *    is a plain map lookup instead of depending on whether the weak L2 entry
 *    survived a GC. Trimmed on TRIM_MEMORY_MODERATE, emptied on COMPLETE.
 *  - OWNED COPIES: every bitmap this pipeline hands out (and caches) is a
 *    private copy of what Glide decoded, NOT Glide's own pooled instance. A
 *    Glide-owned bitmap is only safe while its Target is still reachable —
 *    once the target is GC'd Glide may release it to its BitmapPool and
 *    reuse the pixels for a different image while a canvas is still drawing
 *    it. A ~96px avatar copy costs ~36 KB and only happens on a fresh decode;
 *    in exchange the Glide target is cleared immediately (normal lifecycle)
 *    and the hot cache / L2 can hold the bitmap indefinitely, safely.
 *    The hot cache is therefore filled ONLY by this pipeline's own loads —
 *    never by promoting an L2 hit, because L2 is shared with ImageView binds
 *    whose bitmaps are Glide-pooled.
 *  - DEDUPE: N rows of the same url join one request instead of N.
 *  - CANCEL: a caller's {@link Cancelable} removes only ITS waiter; when the
 *    last waiter leaves, the not-yet-delivered Glide request is cancelled.
 *  - L3: not written here (see {@code writeL3}); Glide's RESOURCE disk cache
 *    already serves cold starts, and a WebP compress per fresh decode only
 *    pays off if something reads that L3 entry back.
 */
public final class AvatarBitmapPipeline {

    public interface Callback {
        void onBitmap(@Nullable Bitmap bitmap);
    }

    /** Handle for one pending bind. cancel() is idempotent and safe after delivery. */
    public interface Cancelable {
        void cancel();
    }

    public static final Cancelable NOOP = () -> { };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final AvatarBinderCore.CacheProvider cache;
    private final DecodeFormat format;
    private final boolean writeL3;
    private final int hotBytes;
    private final int hotBytesLowRam;

    private volatile HotCache hot;
    /** url -> its single in-flight load. ALWAYS touched under synchronized(inFlight). */
    private final HashMap<String, Pending> inFlight = new HashMap<>();

    /**
     * @param writeL3 true only if something actually reads this cache's L3
     *                (MiscAvatarBinder.bind does; the chat module's L3 is never read).
     */
    public AvatarBitmapPipeline(AvatarBinderCore.CacheProvider cache, DecodeFormat format,
                                boolean writeL3, int hotBytes, int hotBytesLowRam) {
        this.cache = cache;
        this.format = format;
        this.writeL3 = writeL3;
        this.hotBytes = hotBytes;
        this.hotBytesLowRam = hotBytesLowRam;
    }

    // ── hot cache ─────────────────────────────────────────────────────────
    private static final class HotCache implements ComponentCallbacks2 {
        final LruCache<String, Bitmap> lru;
        final int maxBytes;
        HotCache(int maxBytes) {
            this.maxBytes = maxBytes;
            this.lru = new LruCache<String, Bitmap>(maxBytes) {
                @Override protected int sizeOf(String k, Bitmap b) {
                    return Math.max(1, b.getAllocationByteCount());
                }
            };
        }
        @Override public void onTrimMemory(int level) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) lru.evictAll();
            else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) lru.trimToSize(maxBytes / 2);
        }
        @Override public void onLowMemory() { lru.evictAll(); }
        @Override public void onConfigurationChanged(Configuration c) { }
    }

    private HotCache hot(Context ctx) {
        HotCache h = hot;
        if (h == null) {
            synchronized (this) {
                h = hot;
                if (h == null) {
                    boolean low = DeviceTier.isLowRamDevice(ctx);
                    h = new HotCache(low ? hotBytesLowRam : hotBytes);
                    ctx.getApplicationContext().registerComponentCallbacks(h);
                    hot = h;
                }
            }
        }
        return h;
    }

    @Nullable
    private Bitmap hotGet(Context ctx, String url) {
        Bitmap b = hot(ctx).lru.get(url);
        return (b != null && !b.isRecycled()) ? b : null;
    }

    private void hotPut(Context ctx, String url, Bitmap b) {
        if (url == null || b == null || b.isRecycled()) return;
        hot(ctx).lru.put(url, b);
    }

    // ── public API ────────────────────────────────────────────────────────

    /**
     * Synchronous fast path: the decoded bitmap if it is already in the hot cache or module L2, else
     * null (caller then falls back to {@link #bind}). No callback/lambda allocation — the common case
     * on a scroll re-bind of a recycled view.
     */
    @Nullable
    public Bitmap peek(Context ctx, String url) {
        if (url == null) return null;
        Bitmap hit = hotGet(ctx, url);
        if (hit == null) {
            hit = cache.l2(ctx).get(url);
            if (hit == null || hit.isRecycled()) return null;
            // deliberately NOT promoted to hot: an L2 entry may be a Glide-pooled bitmap from an ImageView bind
        }
        AvatarCacheAnalytics.getInstance(ctx).recordL2Hit();
        return hit;
    }

    /**
     * Resolves {@code url} (already tier-bucketed by the caller; {@code px} is that tier's pixel size)
     * and delivers the bitmap to {@code callback} — synchronously on a hot/L2 hit, else on the main
     * thread when the shared load finishes. A failed load delivers nothing.
     */
    public Cancelable bind(Context ctx, String url, int px, Callback callback) {
        if (url == null || url.isEmpty()) return NOOP;

        Bitmap hit = peek(ctx, url);
        if (hit != null) { callback.onBitmap(hit); return NOOP; }

        final Context appCtx = ctx.getApplicationContext();
        final Waiter w = new Waiter(callback);
        final Pending pending;
        boolean start = false;
        synchronized (inFlight) {
            Pending existing = inFlight.get(url);
            if (existing == null) {
                existing = new Pending(url, appCtx);
                inFlight.put(url, existing);
                start = true;
            }
            pending = existing;
            pending.waiters.add(w);
            w.owner = pending;
        }
        if (start) startLoad(appCtx, pending, px);
        return w;
    }

    /**
     * The ONE place this pipeline's Glide request options live. Prefetchers (e.g. ChatAvatarBinder
     * .prefetchBatch) build from this on purpose: Glide's cache key covers url + override + transforms
     * + decode format + options, so a warm request only produces a hit for a later bind() if every
     * one of those is IDENTICAL.
     */
    public RequestBuilder<Bitmap> request(Context ctx, String url, int px) {
        return Glide.with(ctx)
            .asBitmap()
            .load(url)
            .apply(new RequestOptions()
                    .override(px, px)
                    .format(format)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE))
            .circleCrop();
    }

    // ── shared in-flight load ─────────────────────────────────────────────
    private static final class Pending {
        final String url;
        final Context glideCtx;                       // application context: not tied to one Activity
        final ArrayList<Waiter> waiters = new ArrayList<>(2);
        Target<Bitmap> target;
        boolean finished;
        boolean cancelled;      // finished because the last waiter left (NOT because it was delivered)
        Pending(String url, Context glideCtx) { this.url = url; this.glideCtx = glideCtx; }
    }

    private final class Waiter implements Cancelable {
        final Callback cb;
        Pending owner;
        Waiter(Callback cb) { this.cb = cb; }
        @Override public void cancel() { cancelWaiter(this); }
    }

    private void startLoad(final Context appCtx, final Pending pending, int px) {
        final String url = pending.url;
        Target<Bitmap> t = request(appCtx, url, px)
            .listener(new RequestListener<Bitmap>() {
                @Override
                public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                             Target<Bitmap> target, boolean isFirstResource) {
                    return false;
                }
                @Override
                public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target,
                                                DataSource dataSource, boolean isFirstResource) {
                    AvatarCacheAnalytics.getInstance(appCtx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    return false;
                }
            })
            .into(new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                    final Bitmap owned = ownedCopy(resource);
                    if (owned != null) {
                        // Cache even when every waiter already left: the decode is paid for, keep it.
                        cache.l2(appCtx).put(url, owned);
                        if (writeL3) cache.l3(appCtx).put(url, owned);
                        hotPut(appCtx, url, owned);
                        finish(pending, owned);
                        // Glide's instance is no longer needed -> normal lifecycle. Posted: never
                        // clear a target from inside its own onResourceReady callback.
                        final CustomTarget<Bitmap> self = this;
                        MAIN.post(() -> { try { Glide.with(appCtx).clear(self); } catch (Throwable ignored) {} });
                    } else {
                        finish(pending, resource);   // copy failed (OOM): deliver Glide's own, uncached, uncleared
                    }
                }
                @Override
                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    finish(pending, null);           // free the slot so the next bind can retry
                }
                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                    finish(pending, null);           // request cancelled (last waiter left / Glide torn down)
                }
            });
        boolean clearNow;
        synchronized (inFlight) {
            // Cancelled while into() was still running -> clear now.
            clearNow = pending.cancelled;
            if (!clearNow) pending.target = t;
        }
        if (clearNow) Glide.with(appCtx).clear(t);
    }

    /** Private, non-pooled, software copy of Glide's bitmap; null if it can't be made. */
    @Nullable
    private static Bitmap ownedCopy(Bitmap src) {
        try {
            Bitmap.Config cfg = src.getConfig();
            if (cfg == null || cfg == Bitmap.Config.HARDWARE) cfg = Bitmap.Config.ARGB_8888;
            return src.copy(cfg, false);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Delivers (bmp != null) or just drops (bmp == null) the shared load; idempotent. */
    private void finish(Pending pending, @Nullable Bitmap bmp) {
        final ArrayList<Waiter> toNotify;
        synchronized (inFlight) {
            if (pending.finished) return;
            pending.finished = true;
            if (inFlight.get(pending.url) == pending) inFlight.remove(pending.url);
            toNotify = new ArrayList<>(pending.waiters);
            pending.waiters.clear();
        }
        if (bmp == null) return;
        for (Waiter w : toNotify) w.cb.onBitmap(bmp);   // outside the lock: callbacks run arbitrary UI code
    }

    private void cancelWaiter(Waiter w) {
        Target<Bitmap> toClear;
        Context glideCtx;
        synchronized (inFlight) {
            final Pending p = w.owner;
            if (p == null || p.finished) return;      // already delivered / failed: nothing to cancel
            p.waiters.remove(w);
            w.owner = null;
            if (!p.waiters.isEmpty()) return;          // other rows still want this avatar
            p.finished = true;                         // last waiter left: cancel the load itself
            p.cancelled = true;
            if (inFlight.get(p.url) == p) inFlight.remove(p.url);
            toClear = p.target;                        // null => startLoad() clears it right after into()
            glideCtx = p.glideCtx;
        }
        if (toClear != null) Glide.with(glideCtx).clear(toClear);
    }
}
