package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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
 */
public final class AvatarBinderCore {

    private AvatarBinderCore() {}

    // Same thresholds/depths every avatar list in the app has shared since
    // AvatarPrefetcher — kept here once instead of re-declared per binder.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — flinging past rows
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — deliberate scroll
    private static final int DEPTH_DEFAULT = 1;
    private static final int DEPTH_SLOW    = 4;
    private static final int DEPTH_FAST    = 0;

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

    /**
     * Velocity-based prefetch + disk-only gate: fast fling past this list
     * skips prefetch entirely (wasted work); slow/deliberate scroll warms
     * several rows ahead via DiskCacheStrategy.DATA (raw bytes only — the
     * full RESOURCE decode only happens in {@link #bind} once a row
     * genuinely becomes visible).
     */
    public static void prefetch(Context context, AvatarSource source, int fromIndex,
                                 float velocityPxPerMs, AvatarSizeTier tier) {
        if (context == null || source == null) return;
        int depth = depthForVelocity(velocityPxPerMs);
        if (depth == 0) return;
        Context appCtx = context.getApplicationContext();
        int size = source.size();
        for (int i = Math.max(0, fromIndex); i < fromIndex + depth && i < size; i++) {
            String photo = source.photo(i);
            if (photo == null || photo.isEmpty()) continue;
            String url = url(appCtx, photo, source.avatarVersion(i), tier);
            Glide.with(appCtx)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.DATA) // bytes only — decode deferred to a real bind
                .priority(Priority.LOW)                    // never competes with a visible row's own request
                .preload();
        }
    }

    private static int depthForVelocity(float v) {
        if (v <= 0f) return DEPTH_DEFAULT;
        if (v >= FAST_FLING_THRESHOLD) return DEPTH_FAST;
        if (v <= SLOW_SCROLL_THRESHOLD) return DEPTH_SLOW;
        return DEPTH_DEFAULT;
    }
}
