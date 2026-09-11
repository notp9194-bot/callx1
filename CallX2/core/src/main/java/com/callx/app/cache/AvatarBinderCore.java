package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AvatarBinderCore — the ONE place the bind()/cancel()/prefetch() Glide
 * plumbing lives for every per-module avatar binder in the app
 * (ChatAvatarBinder in feature-chat, FollowAvatarBinder in feature-reels,
 * and any future feature module's own binder).
 *
 * Why this exists: ChatAvatarBinder and FollowAvatarBinder started as two
 * near-identical copies of the same pipeline (same AvatarSizeTier/
 * AvatarUrlBuilder usage, same velocity-based prefetch thresholds/depths,
 * same L2-check-then-Glide-decode-then-L2/L3-write-through shape) — every
 * fix to one (e.g. the L2/L3 write-through fix) had to be manually
 * re-applied to the other, and a third feature module wanting the same
 * pipeline would have meant a THIRD copy. This class holds that shared
 * shape exactly once.
 *
 * What's deliberately NOT here — and stays per-module: the actual L2/
 * L3 cache INSTANCES (ChatAvatarL2Cache / ReelsAvatarL2Cache). Each
 * module still registers its own AvatarL2MemoryCache/AvatarL3DiskCache
 * with the application context, independently trimmed on
 * TRIM_MEMORY_MODERATE — see AvatarL2MemoryCache's class doc for why that
 * per-module independence matters. Callers pass their own cache via
 * {@link CacheProvider} instead of this class owning a shared instance,
 * so this extraction adds zero cross-module coupling: feature-chat and
 * feature-reels both depend on :core (already true), neither depends on
 * the other.
 *
 * v3 — advanced pass: {@link #bind} now requests Priority.HIGH explicitly
 * (a visible row must never queue behind this module's own prefetch);
 * {@link #prefetch} now dedupes overlapping-window duplicate preload()
 * calls per module and actively CANCELS its own in-flight LOW-priority
 * prefetches on a fast fling instead of only skipping new ones — see
 * {@link #sPrefetchInFlight}'s doc for why bind() doesn't need the same
 * dedup treatment.
 *
 * v4 — {@link #depthForVelocity} is now a continuous inverse curve instead
 * of 3 fixed buckets (adaptive prefetch depth); {@link #prefetch} also
 * gates entirely on {@link #isNetworkPrefetchAllowed} first — no
 * speculative prefetch on a metered connection, regardless of scroll speed.
 *
 * v5 — {@link #prefetch} now also warms a small BACKWARD window on a
 * detected scroll-direction reversal (predictive prefetch, keyed off each
 * module's own fromIndex trend — no caller signature change needed, see
 * {@link #sLastFromIndex}'s doc); {@link #depthForVelocity}'s ceiling is now
 * nudged by a bounded multiplier that {@link #maybeAutoTune} periodically
 * adjusts from AvatarCacheAnalytics's rolling CDN-network hit ratio instead
 * of staying a fixed hand-tuned constant forever.
 */
public final class AvatarBinderCore {

    private AvatarBinderCore() {}

    // Same thresholds every avatar list in the app has shared since
    // AvatarPrefetcher — kept here once instead of re-declared per binder.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — flinging past rows, hard cutoff (depth 0)
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — reference point the curve is calibrated against
    private static final int DEPTH_DEFAULT = 1;
    private static final int DEPTH_MAX     = 4;  // same ceiling the old DEPTH_SLOW bucket used
    private static final int DEPTH_FAST    = 0;
    // FIX (#4 — adaptive prefetch): k such that depthForVelocity(SLOW_SCROLL_THRESHOLD)
    // still equals the old fixed DEPTH_SLOW value (4) — same "deliberate scroll"
    // behavior as before, now the START of a smooth curve instead of one flat step.
    private static final float ADAPTIVE_K = DEPTH_MAX * SLOW_SCROLL_THRESHOLD;

    /**
     * Per-module (CacheProvider-scoped) map of URL -> in-flight LOW-priority
     * prefetch Target, so a fast-fling can actively CANCEL whatever this
     * module's list already queued instead of merely skipping new ones (see
     * {@link #prefetch}), and an overlapping prefetch window on rapid
     * re-scroll never mints a second Glide preload() for a URL that's
     * already being fetched.
     *
     * Why this doesn't also apply to {@link #bind}: Glide's own Engine
     * already coalesces two identical in-flight loads (same url + same
     * RequestOptions) that land on different targets into a single decode
     * job — two rows binding the same url+tier before either resolves
     * already share one network fetch/decode for free. That coalescing
     * doesn't cover prefetch()'s preload() calls the same way in practice
     * (each one mints its own throwaway Target up front), which is what
     * this map exists to dedupe instead.
     *
     * IdentityHashMap keyed by the module's own static final CacheProvider
     * instance — same per-module independence AvatarL2MemoryCache/L3 already
     * have; chat's fast-fling never touches reels' in-flight prefetches.
     */
    private static final Map<CacheProvider, Map<String, Target<Drawable>>> sPrefetchInFlight =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private static Map<String, Target<Drawable>> prefetchMap(CacheProvider cache) {
        synchronized (sPrefetchInFlight) {
            Map<String, Target<Drawable>> m = sPrefetchInFlight.get(cache);
            if (m == null) {
                m = new ConcurrentHashMap<>();
                sPrefetchInFlight.put(cache, m);
            }
            return m;
        }
    }

    /**
     * Per-module last-seen {@code fromIndex}, for FIX #9 (predictive /
     * reverse-scroll prefetch). Every existing call site already computes
     * fromIndex as "just past the currently visible window" in whichever
     * direction it's scrolling (e.g. {@code lastVisible + 1}) — so it rises
     * while scrolling forward and falls while scrolling back. Comparing
     * each call's fromIndex against this module's own previous value
     * detects a direction reversal without any caller passing an explicit
     * direction or touching its call site at all.
     */
    private static final Map<CacheProvider, Integer> sLastFromIndex =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * FIX (#13 — auto-tuning): bounded multiplier on {@link #depthForVelocity}'s
     * depth ceiling, nudged by {@link AvatarCacheAnalytics}'s rolling
     * "didn't need a real CDN round-trip" hit ratio instead of the ceiling
     * staying one hand-picked constant forever. Deliberately re-evaluated
     * only every {@link #AUTO_TUNE_EVAL_INTERVAL} prefetch() calls (not per
     * call) and clamped to a narrow [0.5x, 1.5x] band — this is a courtesy
     * nudge on top of an already-reasonable baseline, not a free-running
     * controller that could drift the prefetch window somewhere extreme
     * and hard to reproduce/debug.
     */
    private static volatile float sDepthMultiplier = 1.0f;
    private static final float MIN_DEPTH_MULTIPLIER = 0.5f;
    private static final float MAX_DEPTH_MULTIPLIER = 1.5f;
    private static final int AUTO_TUNE_EVAL_INTERVAL = 200;
    private static final AtomicInteger sPrefetchCallCount = new AtomicInteger();

    /** Re-evaluated every {@link #AUTO_TUNE_EVAL_INTERVAL} calls, app-wide
     *  (AvatarCacheAnalytics is itself a single app-wide instance — see its
     *  class doc — so this tunes one shared multiplier off the aggregate
     *  picture across every module, not a per-module split). */
    private static void maybeAutoTune(Context appCtx) {
        if (sPrefetchCallCount.incrementAndGet() % AUTO_TUNE_EVAL_INTERVAL != 0) return;
        AvatarCacheAnalytics analytics = AvatarCacheAnalytics.getInstance(appCtx);
        if (analytics.getTotalLoads() < 50) return; // not enough samples to act on yet
        float hitRatio = analytics.hitRatio(); // fraction that did NOT need a real CDN round-trip
        float old = sDepthMultiplier;
        if (hitRatio < 0.6f) {
            // Too many real network round-trips — prefetch isn't warming
            // rows far enough ahead of when they're actually bound; widen
            // the ceiling a bit.
            sDepthMultiplier = Math.min(MAX_DEPTH_MULTIPLIER, sDepthMultiplier + 0.1f);
        } else if (hitRatio > 0.92f) {
            // Cache tiers are already doing almost all the work — a wide
            // prefetch window here is mostly paying bytes/battery for rows
            // that would've been an L2/L3 hit at bind-time anyway; trim
            // back toward baseline.
            sDepthMultiplier = Math.max(MIN_DEPTH_MULTIPLIER, sDepthMultiplier - 0.1f);
        }
        if (sDepthMultiplier != old) {
            Log.d("AvatarBinderCore", "auto-tune: hitRatio=" + hitRatio
                    + " depthMultiplier " + old + " -> " + sDepthMultiplier);
        }
    }

    /** Read-only view over whatever list a screen is scrolling — callers'
     *  own AvatarSource interfaces (ChatAvatarBinder.AvatarSource,
     *  FollowAvatarBinder.AvatarSource) simply extend this one, so existing
     *  call sites and anonymous implementations need no changes. */
    public interface AvatarSource {
        String photo(int index);
        long avatarVersion(int index);
        int size();
    }

    /** A module's own L2/L3 cache pair, supplied per-call so this class
     *  never owns or shares a cache instance across modules. */
    public interface CacheProvider {
        AvatarL2MemoryCache l2(Context ctx);
        AvatarL3DiskCache l3(Context ctx);
    }

    /** Per-caller knobs — the small behavioral differences that used to be
     *  hardcoded differently in each binder (e.g. chat's flat ImageView
     *  bind circle-crops in software since its rows aren't all
     *  CircleImageView; reels' rows already are, so it skips that
     *  redundant draw). */
    public static final class BindOptions {
        public final AvatarSizeTier tier;
        public final DecodeFormat format;
        public final boolean circleCrop;
        public final boolean dontAnimate;
        public final boolean recordDashboardStats;
        public final int placeholderRes;

        public BindOptions(AvatarSizeTier tier, DecodeFormat format, boolean circleCrop,
                            boolean dontAnimate, boolean recordDashboardStats, int placeholderRes) {
            this.tier = tier;
            this.format = format;
            this.circleCrop = circleCrop;
            this.dontAnimate = dontAnimate;
            this.recordDashboardStats = recordDashboardStats;
            this.placeholderRes = placeholderRes;
        }
    }

    /** Server-side responsive, version-tagged URL for one row — shared by
     *  every binder's own url()/bind()/prefetch(). */
    public static String url(Context ctx, String photo, long avatarVersion, AvatarSizeTier tier) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, tier, avatarVersion);
    }

    /**
     * Bind a VISIBLE row's avatar: L2 memory fast-path first (instant
     * paint, survives MODERATE trim), otherwise a full RESOURCE-cached
     * Glide decode at the caller's tier/format, analytics-wrapped, with
     * the decode written back into L2 (+ L3 disk) so the next bind of this
     * exact URL is instant.
     */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion,
                             CacheProvider cache, BindOptions opts) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(opts.placeholderRes);
            iv.setTag(com.callx.app.core.R.id.tag_avatar_url, null);
            return;
        }
        String url = url(ctx, photo, avatarVersion, opts.tier);

        // PERF: skip everything below if this exact URL is already what's
        // loaded/loading into this row — a recycled row rebinding to the
        // same person (e.g. a status-only refresh) shouldn't re-issue an
        // identical Glide request.
        if (url != null && url.equals(iv.getTag(com.callx.app.core.R.id.tag_avatar_url))) return;
        iv.setTag(com.callx.app.core.R.id.tag_avatar_url, url);

        Bitmap l2Hit = cache.l2(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            if (opts.recordDashboardStats) {
                CacheDashboardStats dashboard = CacheDashboardStats.getInstance(ctx);
                dashboard.recordMemoryHit("avatar:" + url);
                dashboard.recordMemoryEntry("avatar:" + url, l2Hit.getByteCount());
            }
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }

        int px = AvatarUrlBuilder.tierPx(ctx, opts.tier);
        RequestOptions reqOpts = (opts.circleCrop ? RequestOptions.circleCropTransform() : new RequestOptions())
                .override(px, px)
                .format(opts.format)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE);

        com.bumptech.glide.RequestBuilder<Drawable> req = Glide.with(ctx).load(url);
        if (opts.dontAnimate) req = req.dontAnimate();
        req.apply(reqOpts)
            // FIX (#2 — prioritization): a visible row is the thing the
            // user is looking at right now, so it must never sit queued
            // behind this same module's own LOW-priority prefetch() calls
            // (see prefetch() below) under Glide's request-queue
            // contention. Explicit HIGH here (prefetch is explicit LOW)
            // makes that ordering guaranteed instead of accidental.
            .priority(Priority.HIGH)
            .placeholder(opts.placeholderRes)
            .error(opts.placeholderRes)
            .listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                    if (opts.recordDashboardStats) {
                        CacheDashboardStats.getInstance(ctx).recordMemoryMiss("avatar:" + url);
                        CacheDashboardStats.getInstance(ctx).recordDiskMiss("avatar:" + url);
                    }
                    return false; // let Glide still apply the error placeholder
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                    if (opts.recordDashboardStats) {
                        CacheDashboardStats.getInstance(ctx).recordGlideResult("avatar:" + url, dataSource, resource);
                    }
                    AvatarCacheAnalytics.getInstance(ctx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    if (resource instanceof BitmapDrawable) {
                        Bitmap bmp = ((BitmapDrawable) resource).getBitmap();
                        cache.l2(ctx).put(url, bmp);
                        cache.l3(ctx).put(url, bmp);
                    }
                    return false; // let Glide still deliver the drawable into the ImageView
                }
            })
            .into(iv);
    }

    /**
     * Cancels an in-flight request for a row that just scrolled off screen
     * — call from the adapter's onViewRecycled().
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
        iv.setTag(com.callx.app.core.R.id.tag_avatar_url, null);
    }

    /** Shared by the fast-fling AND the network-gate branches below — both
     *  mean the same thing: whatever this module already queued is now
     *  pure waste, cancel and forget it. */
    private static void cancelAllInFlight(Context appCtx, Map<String, Target<Drawable>> inFlight) {
        synchronized (inFlight) {
            for (Target<Drawable> t : inFlight.values()) {
                try { Glide.with(appCtx).clear(t); } catch (Exception ignored) {}
            }
            inFlight.clear();
        }
    }

    /**
     * FIX (#3 — network-aware prefetch): speculative prefetch is only worth
     * doing on a connection the user isn't paying per-byte for — gated on
     * NOT_METERED (covers WiFi, most unlimited plans marked unmetered by
     * the OS/carrier) rather than a raw WiFi-only check, so it also allows
     * an explicitly unmetered cellular plan through. Fails OPEN (returns
     * true) on anything unexpected — a missing ConnectivityManager, no
     * active-network read, a permission/OEM quirk — because this is a
     * LOW-priority courtesy prefetch either way; the worst case of failing
     * open is a wasted few KB, the worst case of failing closed is silently
     * never prefetching again on some device/OEM combination.
     */
    private static boolean isNetworkPrefetchAllowed(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network net = cm.getActiveNetwork();
            if (net == null) return false; // no active network — nothing to prefetch over
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return true;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Velocity-based prefetch + disk-only gate: fast fling past this list
     * skips prefetch entirely AND cancels whatever this module's list
     * already had queued (FIX #11 — priority cancellation: those were LOW
     * priority and aimed at rows the user has already blown past, so
     * they're now pure waste sitting in Glide's queue); slow/deliberate
     * scroll warms several rows ahead via DiskCacheStrategy.DATA (raw bytes
     * only — the full RESOURCE decode only happens in {@link #bind} once a
     * row genuinely becomes visible).
     *
     * FIX #1 (request dedup): a URL already sitting in L2 needs nothing
     * prefetched, and a URL this module already has an in-flight preload()
     * for (from an earlier, overlapping scroll window) is skipped instead
     * of minting a second redundant Glide target for it.
     *
     * FIX #3 (network-aware): on a metered connection (no WiFi, no
     * unmetered cellular plan), prefetch is skipped and cancelled entirely
     * — same as a fast fling — no matter how slow/deliberate the scroll is.
     *
     * FIX #4 (adaptive depth): see {@link #depthForVelocity} — depth now
     * scales continuously with velocity instead of 3 fixed buckets.
     *
     * FIX #9 (predictive / reverse-scroll prefetch): a detected direction
     * reversal (see {@link #sLastFromIndex}) also warms a small window just
     * BEHIND the new fromIndex — the rows now coming back into view in the
     * direction the user just resumed scrolling toward — not only ahead of
     * it as before.
     *
     * FIX #13 (auto-tuning): {@link #maybeAutoTune} periodically nudges
     * {@link #depthForVelocity}'s ceiling from real cache-hit telemetry.
     */
    public static void prefetch(Context context, AvatarSource source, int fromIndex,
                                 float velocityPxPerMs, AvatarSizeTier tier, CacheProvider cache) {
        if (context == null || source == null) return;
        Context appCtx = context.getApplicationContext();
        Map<String, Target<Drawable>> inFlight = prefetchMap(cache);

        maybeAutoTune(appCtx);

        // Track the direction trend regardless of whether this particular
        // call ends up prefetching anything below, so the NEXT call's
        // reversal check stays accurate to the real scroll position.
        Integer previousFromIndex = sLastFromIndex.put(cache, fromIndex);

        if (!isNetworkPrefetchAllowed(appCtx)) {
            cancelAllInFlight(appCtx, inFlight);
            return;
        }

        int depth = depthForVelocity(velocityPxPerMs);
        if (depth == 0) {
            cancelAllInFlight(appCtx, inFlight);
            return;
        }

        int size = source.size();
        if (previousFromIndex != null && fromIndex < previousFromIndex) {
            // Reversed — warm a small window just behind fromIndex too,
            // half the forward depth (this is a secondary/speculative
            // window on top of the primary forward one, kept smaller on
            // purpose so a reversal never doubles the total prefetch cost).
            int backDepth = Math.max(1, depth / 2);
            prefetchRange(appCtx, source, Math.max(0, fromIndex - backDepth), fromIndex, size, tier, cache, inFlight);
        }
        prefetchRange(appCtx, source, Math.max(0, fromIndex), fromIndex + depth, size, tier, cache, inFlight);
    }

    /** Issues LOW-priority, bytes-only preload() calls for [start, end) —
     *  shared by prefetch()'s forward window and its FIX #9 backward window. */
    private static void prefetchRange(Context appCtx, AvatarSource source, int start, int end,
                                       int size, AvatarSizeTier tier, CacheProvider cache,
                                       Map<String, Target<Drawable>> inFlight) {
        for (int i = Math.max(0, start); i < end && i < size; i++) {
            String photo = source.photo(i);
            if (photo == null || photo.isEmpty()) continue;
            String url = url(appCtx, photo, source.avatarVersion(i), tier);
            if (url == null) continue;
            if (cache.l2(appCtx).get(url) != null) continue; // already decoded, nothing to prefetch
            if (inFlight.containsKey(url)) continue;          // already being fetched from an earlier window

            Target<Drawable> target = Glide.with(appCtx)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.DATA) // bytes only — decode deferred to a real bind
                .priority(Priority.LOW)                    // never competes with a visible row's own request
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> t, boolean isFirstResource) {
                        inFlight.remove(url);
                        return false;
                    }
                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> t, DataSource dataSource, boolean isFirstResource) {
                        inFlight.remove(url);
                        return false;
                    }
                })
                .preload();
            inFlight.put(url, target);
        }
    }

    private static int depthForVelocity(float v) {
        if (v <= 0f) return DEPTH_DEFAULT; // no scroll info yet (e.g. screen just opened)
        if (v >= FAST_FLING_THRESHOLD) return DEPTH_FAST; // hard cutoff — also prefetch()'s cancellation trigger

        // FIX (#4 — adaptive prefetch): was 3 fixed buckets (0 / 1 / 4) that
        // snapped depth between coarse steps as velocity crossed a
        // threshold. Now a continuous inverse curve — depth scales smoothly
        // down as scroll speed rises between SLOW_SCROLL_THRESHOLD and
        // FAST_FLING_THRESHOLD instead of jumping straight from 4 to 1 the
        // instant SLOW_SCROLL_THRESHOLD is crossed. Still lands on the same
        // endpoints the old buckets did (4 at/below SLOW_SCROLL_THRESHOLD,
        // trending to 1 near FAST_FLING_THRESHOLD).
        //
        // FIX (#13 — auto-tuning): sDepthMultiplier (bounded 0.5x-1.5x, see
        // maybeAutoTune) scales the whole curve, so a device/session with a
        // consistently low CDN-hit-ratio gradually gets a wider window and
        // vice versa, instead of this ceiling being one constant forever.
        int depth = Math.round((ADAPTIVE_K / v) * sDepthMultiplier);
        return Math.max(1, Math.min(depth, Math.round(DEPTH_MAX * MAX_DEPTH_MULTIPLIER)));
    }
}
