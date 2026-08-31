package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

/**
 * ChatAvatarBinder — brings the SAME deep avatar pipeline reels already has
 * (see FollowAvatarBinder in feature-reels) to the chat list
 * (ChatListAdapter / ChatsFragment).
 *
 * Before this, ChatListAdapter's avatar bind was a flat
 * {@code Glide.load(u.thumbUrl)} — a raw un-tiered dp size (see the old
 * getAvatarSizePx: flat 50dp * density, no bucketing), no CDN transform/
 * format param, no L2/L3 reuse, no velocity-aware prefetch, and a
 * disk-only-decode-deferred gate only existed for the single "next row"
 * (preloadAdjacentAvatar), not a real scroll-speed-aware window.
 *
 * This class is the chat list's single choke point for all of that,
 * exactly mirroring FollowAvatarBinder's shape so avatar behavior is
 * consistent across every list screen in the app:
 *
 *  • url()      — AvatarUrlBuilder#buildResponsive: shared AvatarSizeTier
 *                 bucket + density-bucketed dpr_ param + WebP/AVIF format
 *                 param (bestFormatParam) + ?v=<avatarVersion> cache-bust,
 *                 all server-side (see AvatarUrlBuilder class doc).
 *  • bind()     — L2 memory fast-path (ChatAvatarL2Cache, survives
 *                 TRIM_MEMORY_MODERATE) before falling back to a real Glide
 *                 decode; decode result is written back into L2 (+ L3 disk)
 *                 so the next bind of this exact URL is instant.
 *  • cancel()   — call from onViewRecycled(); stops an in-flight request for
 *                 a row that just scrolled off screen.
 *  • prefetch() — velocity-based depth (same thresholds as AvatarPrefetcher/
 *                 FollowAvatarBinder — fast fling skips entirely, slow
 *                 scroll warms several rows ahead), using
 *                 DiskCacheStrategy.DATA (raw bytes only, decode deferred to
 *                 a real bind()) so a row that gets flung past without ever
 *                 actually binding never pays a speculative CPU decode.
 *
 * ETag/Last-Modified conditional requests are NOT re-implemented here —
 * same as FollowAvatarBinder, every Glide request app-wide (this screen
 * included) already gets that for free via CallxGlideModule routing through
 * AvatarHttpCache's shared OkHttpClient.
 */
public final class ChatAvatarBinder {

    private ChatAvatarBinder() {}

    /** Chat list row avatar (~50dp, item_chat row) — SMALL(48) under-resolves it, so this rounds up to MEDIUM(64). */
    private static final AvatarSizeTier TIER = AvatarSizeTier.forViewSizeDp(50);

    // Same thresholds/depths as AvatarPrefetcher/FollowAvatarBinder — kept in
    // sync deliberately so "fast fling" and "slow scroll" mean the same
    // thing across every avatar list in the app.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — flinging past rows
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — deliberate scroll
    private static final int DEPTH_DEFAULT = 1;
    private static final int DEPTH_SLOW    = 4;
    private static final int DEPTH_FAST    = 0;

    /**
     * v90-equivalent avatar decode format — same API-level HARDWARE-bitmap
     * gate ChatListAdapter's original bind used (see that class's removed
     * AVATAR_FORMAT doc): PREFER_ARGB_8888 on API 26+ lets Glide promote the
     * decoded+circleCropped bitmap to Bitmap.Config.HARDWARE (zero-copy
     * GPU compositing); PREFER_RGB_565 below that, where HARDWARE bitmaps
     * don't exist.
     */
    public static final DecodeFormat AVATAR_FORMAT =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? DecodeFormat.PREFER_ARGB_8888
                    : DecodeFormat.PREFER_RGB_565;

    /** Read-only view over whatever list a screen is scrolling — same shape as FollowAvatarBinder.AvatarSource. */
    public interface AvatarSource {
        String photo(int index);
        long avatarVersion(int index);
        int size();
    }

    /** Server-side responsive, version-tagged URL for one row — thumbUrl-equivalent input, same as the reel owner avatar. */
    public static String url(Context ctx, String photo, long avatarVersion) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, TIER, avatarVersion);
    }

    /**
     * Bind a VISIBLE row's avatar. Checks L2 memory first (instant paint,
     * survives MODERATE trim); otherwise a full RESOURCE-cached Glide
     * decode, analytics-wrapped so this row's hits feed the same
     * L2/L3/Glide/CDN split every other avatar screen records into. A
     * successful decode is written back into L2 (+ L3 disk) so the very
     * next bind of this exact URL (re-scroll, warm restart) is instant.
     */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            return;
        }
        String url = url(ctx, photo, avatarVersion);
        Bitmap l2Hit = ChatAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            CacheDashboardStats dashboard = CacheDashboardStats.getInstance(ctx);
            dashboard.recordMemoryHit("avatar:" + url);
            dashboard.recordMemoryEntry("avatar:" + url, l2Hit.getByteCount());
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }
        Glide.with(ctx)
            .load(url)
            .dontAnimate()
            .apply(RequestOptions.circleCropTransform()
                    .format(AVATAR_FORMAT)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE))
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                @Override
                public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                                             com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                             boolean isFirstResource) {
                    CacheDashboardStats.getInstance(ctx).recordMemoryMiss("avatar:" + url);
                    CacheDashboardStats.getInstance(ctx).recordDiskMiss("avatar:" + url);
                    return false; // let Glide still apply the error placeholder
                }
                @Override
                public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                    CacheDashboardStats.getInstance(ctx)
                            .recordGlideResult("avatar:" + url, dataSource, resource);
                    // CDN/cache split monitoring — same analytics every other
                    // avatar screen feeds into (see FollowAvatarBinder /
                    // ReelUiController). NOTE: a single Glide request only
                    // keeps its LAST-set listener(), so this is folded into
                    // one listener alongside the L2/L3 write-through below
                    // instead of chaining two .listener() calls.
                    AvatarCacheAnalytics.getInstance(ctx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    // FIX (L2/L3 write-through): same "feed L2 + L3 so a
                    // future bind/warm-restart skips Glide entirely" pattern
                    // ReelUiController#loadOwnerAvatarNow already uses — the
                    // instanceof guard naturally skips anything Glide didn't
                    // hand back as a plain BitmapDrawable.
                    if (resource instanceof android.graphics.drawable.BitmapDrawable) {
                        Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) resource).getBitmap();
                        ChatAvatarL2Cache.get(ctx).put(url, bmp);
                        ChatAvatarL2Cache.l3(ctx).put(url, bmp);
                    }
                    return false; // let Glide still deliver the drawable into the ImageView
                }
            })
            .into(iv);
    }

    /**
     * FIX (lifecycle-aware cancel): call from ChatListAdapter's
     * onViewRecycled(). Stops an in-flight request for a row that just
     * scrolled off screen instead of letting it keep competing for
     * bandwidth/decode time against whatever's now actually visible.
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling past the
     * chat list → skip prefetch entirely (would be wasted work — the user
     * blows past a row before its avatar even finishes decoding); slow/
     * deliberate scroll → warm several rows ahead. Uses
     * {@link DiskCacheStrategy#DATA} — raw bytes cached, NOT the full
     * decoded bitmap — for rows that might still get flung past without
     * ever binding; the full RESOURCE decode only happens in {@link #bind}
     * once a row genuinely becomes visible, so this never pays CPU decode
     * cost speculatively, only the (cheap, disk-cached) network fetch.
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
            String url = url(appCtx, photo, source.avatarVersion(i));
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
