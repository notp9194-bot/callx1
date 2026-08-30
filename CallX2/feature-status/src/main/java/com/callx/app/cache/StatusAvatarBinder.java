package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
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
 * StatusAvatarBinder — brings the SAME deep avatar pipeline Reels
 * (AvatarPrefetcher / ReelUiController#loadOwnerAvatarNow) and Chat
 * (ChatAvatarBinder) already have to the Status tray/list
 * (StatusListAdapter's My-Status row, contact rows, and card carousel).
 *
 * Before this, every avatar bind in StatusListAdapter was a flat
 * {@code Glide.load(ownerPhoto).override(480, 853)} — a video-tile-sized
 * override reused on a 46dp circular avatar view, no shared
 * {@link AvatarSizeTier} bucketing (so the same user's avatar produced a
 * different CDN URL/cache-key per screen), no density-aware sizing, no
 * WebP/AVIF transform param, no L2/L3 reuse, and no velocity-aware
 * prefetch — only an {@link AvatarCacheAnalytics#glideListener} for
 * CDN/cache-tier *monitoring*, with nothing underneath it to actually make
 * that split look good.
 *
 * This class is the Status list's single choke point for all of that,
 * mirroring ChatAvatarBinder's shape (chat) plus the gated-bind +
 * blur-up-thumbnail richness ReelUiController uses for the reel player's
 * owner avatar, so avatar behavior is consistent across every list screen
 * in the app:
 *
 *  • url()/thumbUrl()  — AvatarUrlBuilder#buildResponsive: shared tier
 *                        bucket + density-bucketed dpr_ param + WebP/AVIF
 *                        format param + ?v=&lt;avatarVersion&gt; cache-bust,
 *                        all resolved server-side.
 *  • bind()            — L2 memory fast-path (StatusAvatarL2Cache, survives
 *                        TRIM_MEMORY_MODERATE) → L3 disk fast-path (survives
 *                        process death) → a real Glide decode chained with a
 *                        TINY-tier .thumbnail() blur-up, decode result
 *                        written back into L2+L3 so the next bind of this
 *                        exact URL is instant.
 *  • bindGated()       — FIX (isVisible gate): call this instead of bind()
 *                        from onBindViewHolder. If the row is not yet
 *                        attached to the window (RecyclerView/GapWorker can
 *                        bind a couple of rows ahead of scroll before they're
 *                        actually on screen), issues a disk-cache-only
 *                        (DiskCacheStrategy.DATA + onlyRetrieveFromCache)
 *                        load instead of a real network-capable one — never
 *                        opens a network connection for a row nobody can see
 *                        yet, but still paints instantly if AvatarPrefetcher-
 *                        style prefetch() already warmed the bytes.
 *  • promote()         — call from onViewAttachedToWindow(): upgrades a
 *                        still-pending gated bind to the real HIGH-priority
 *                        network-capable load once the row is actually on
 *                        screen. No-op if bind() already resolved it.
 *  • cancel()          — call from onViewRecycled(): Glide.clear() cancels an
 *                        in-flight request for a row that just scrolled off
 *                        screen (the Java equivalent of cancelling a
 *                        coroutine Job on scope-exit — same fix
 *                        ReelUiController#onBecameInvisible documents).
 *  • prefetch()        — velocity-based depth (same thresholds as
 *                        AvatarPrefetcher/ChatAvatarBinder — fast fling skips
 *                        entirely, slow scroll warms several rows ahead),
 *                        chaining the TINY thumbnail tier so a prefetched
 *                        row's blur-up frame is also a cache hit by the time
 *                        the user actually scrolls to it.
 *
 * ETag/Last-Modified conditional requests are NOT re-implemented here — same
 * as ChatAvatarBinder, every Glide request app-wide already gets that for
 * free via CallxGlideModule routing through AvatarHttpCache's shared
 * OkHttpClient, and every URL here already carries AvatarUrlBuilder's
 * &v=&lt;avatarVersion&gt; on top, so a real avatar change still forces a
 * fresh fetch even on a CDN edge that would otherwise 304 the old bytes.
 */
public final class StatusAvatarBinder {

    private StatusAvatarBinder() {}

    /** Status tray avatar (46dp item_status/item_my_status row, 39dp carousel card) — both round up to shared SMALL(48) tier. */
    private static final AvatarSizeTier TIER = AvatarSizeTier.forViewSizeDp(46);
    /** Tiny blur-up tier chained via .thumbnail() — must match the tier prefetch() warms. */
    private static final AvatarSizeTier THUMBNAIL_TIER = AvatarSizeTier.TINY;

    // Same thresholds/depths as AvatarPrefetcher/ChatAvatarBinder — kept in
    // sync deliberately so "fast fling" and "slow scroll" mean the same
    // thing across every avatar list in the app.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — flinging past rows
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — deliberate scroll
    private static final int DEPTH_DEFAULT = 1;
    private static final int DEPTH_SLOW    = 4;
    private static final int DEPTH_FAST    = 0;

    /** Read-only view over whatever list a screen is scrolling — same shape as ChatAvatarBinder.AvatarSource. */
    public interface AvatarSource {
        String photo(int index);
        long avatarVersion(int index);
        int size();
    }

    public static String url(Context ctx, String photo, long avatarVersion) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, TIER, avatarVersion);
    }

    private static String thumbUrl(Context ctx, String photo, long avatarVersion) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, THUMBNAIL_TIER, avatarVersion);
    }

    /** Unconditional real bind — used once a row is known to be visible. See {@link #bindGated} for the offscreen-aware entry point. */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        bind(ctx, iv, photo, avatarVersion, placeholderRes, Priority.IMMEDIATE);
    }

    private static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes, Priority priority) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            iv.setTag(null);
            return;
        }
        String url = url(ctx, photo, avatarVersion);
        iv.setTag(url); // FIX (isVisible gate): tags the exact bind this view currently wants, so promote()/a later bind() can tell a stale in-flight request apart from the current one

        // FIX (onTrimMemory / L2 cache): survives TRIM_MEMORY_MODERATE — see
        // AvatarL2MemoryCache/StatusAvatarL2Cache. Checked before touching
        // Glide at all, same as ReelUiController#loadOwnerAvatarNow.
        Bitmap l2Hit = StatusAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }

        // FIX (L3 disk tier): covers process death, which L2 (in-process
        // memory) can't. Fired in parallel with the Glide request below —
        // if the async disk read lands first AND this exact bind is still
        // current (tag still matches), paint it immediately; otherwise the
        // stale hit is dropped so it can never flicker over a newer image.
        StatusAvatarL2Cache.l3(ctx).getAsync(url, l3Bmp -> {
            if (l3Bmp == null) return;
            if (!url.equals(iv.getTag())) return; // rebound to a different row/photo since
            iv.setImageBitmap(l3Bmp);
            StatusAvatarL2Cache.get(ctx).put(url, l3Bmp); // warm L2 too
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L3_DISK);
        });

        String thumb = thumbUrl(ctx, photo, avatarVersion);
        RequestOptions opts = new RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565) // opaque avatar, no alpha needed
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // PERF: cache resized variant on disk — re-scroll won't re-download
                .priority(priority)
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .circleCrop();

        Glide.with(ctx)
            .load(url)
            .apply(opts)
            // FIX (thumbnail blur-up): TINY tier chained ahead of the real
            // decode — exactly what prefetch() below warms, so a prefetched
            // row shows an instant blur-up frame instead of a bare
            // placeholder while the SMALL-tier decode finishes.
            .thumbnail(Glide.with(ctx)
                    .load(thumb)
                    .apply(new RequestOptions()
                            .format(DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .priority(priority)
                            .circleCrop()))
            .listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                    return false; // let Glide still apply the error placeholder
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                    AvatarCacheAnalytics.getInstance(ctx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    // FIX (L2/L3 write-through): a future bind of this exact
                    // URL (re-scroll, warm restart) skips Glide entirely.
                    // circleCrop() forces a fresh bitmap so this is always a
                    // plain BitmapDrawable, never a hardware/animated one.
                    if (resource instanceof android.graphics.drawable.BitmapDrawable) {
                        Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) resource).getBitmap();
                        StatusAvatarL2Cache.get(ctx).put(url, bmp);
                        StatusAvatarL2Cache.l3(ctx).put(url, bmp);
                    }
                    return false; // let Glide still deliver the drawable into the ImageView
                }
            })
            .into(iv);
    }

    /**
     * FIX (isVisible gate): call this from onBindViewHolder instead of
     * bind() directly. RecyclerView's GapWorker can bind a row a little
     * ahead of it actually reaching the viewport (layout prefetch) — for
     * THAT row, a real network-capable request is wasted work if the user
     * never scrolls the rest of the way. iv.isAttachedToWindow() is a cheap,
     * accurate enough proxy for "actually on screen right now" without
     * threading a separate visibility signal through every call site.
     */
    public static void bindGated(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        if (iv.isAttachedToWindow()) {
            bind(ctx, iv, photo, avatarVersion, placeholderRes, Priority.IMMEDIATE);
            return;
        }
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            iv.setTag(null);
            return;
        }
        String url = url(ctx, photo, avatarVersion);
        iv.setTag(url);
        Glide.with(ctx)
            .load(url)
            .apply(new RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.DATA) // disk-only tier — no client-side resize to cache, so DATA (raw bytes) is what a cache hit here needs
                .onlyRetrieveFromCache(true)                // never touches the network — a miss just falls through to the placeholder
                .priority(Priority.LOW)                     // PERF: this row is still offscreen — never contend with a visible row's IMMEDIATE request
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .circleCrop())
            .into(iv);
    }

    /**
     * Call from onViewAttachedToWindow(): upgrades a still-pending
     * {@link #bindGated} disk-only load to the real HIGH-priority
     * network-capable one now that the row is confirmed on screen. Cheap
     * no-op if this exact URL already resolved (a fresh bind() re-tags the
     * view, so a stale tag here is naturally impossible).
     */
    public static void promote(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        String expected = url(ctx, photo, avatarVersion);
        if (expected == null || !expected.equals(iv.getTag())) return; // already bound/rebound to something else
        bind(ctx, iv, photo, avatarVersion, placeholderRes, Priority.HIGH);
    }

    /**
     * FIX (Lifecycle-aware cancel — the Java equivalent of cancelling a
     * coroutine Job on scope-exit): call from onViewRecycled(). Stops an
     * in-flight request for a row that just scrolled off screen instead of
     * letting it keep competing for bandwidth/decode time against whatever
     * is now actually visible.
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
        iv.setTag(null);
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling past the
     * status list → skip prefetch entirely (wasted work — the user blows
     * past a row before its avatar even finishes decoding); slow/deliberate
     * scroll → warm several rows ahead. DiskCacheStrategy.DATA (raw bytes,
     * NOT the decoded bitmap) for both the main tier and the thumbnail
     * tier — the full RESOURCE decode only happens in {@link #bind} once a
     * row genuinely becomes visible, so this never pays speculative CPU
     * decode cost, only the (cheap, disk-cached) network fetch.
     */
    public static void prefetch(Context context, AvatarSource source, int fromIndex, float velocityPxPerMs) {
        if (context == null || source == null) return;
        int depth = depthForVelocity(velocityPxPerMs);
        if (depth == 0) return;
        Context appCtx = context.getApplicationContext();
        int size = source.size();
        for (int i = Math.max(0, fromIndex); i < fromIndex + depth && i < size; i++) {
            String photo = source.photo(i);
            if (photo == null || photo.isEmpty()) continue;
            long version = source.avatarVersion(i);
            String mainUrl = url(appCtx, photo, version);
            String thumb = thumbUrl(appCtx, photo, version);
            Glide.with(appCtx)
                .load(mainUrl)
                .thumbnail(Glide.with(appCtx)
                        .load(thumb)
                        .diskCacheStrategy(DiskCacheStrategy.DATA)
                        .priority(Priority.LOW))
                .diskCacheStrategy(DiskCacheStrategy.DATA) // bytes only — decode deferred to a real bind()
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
