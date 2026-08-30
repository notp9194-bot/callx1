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
 * CallAvatarBinder — brings the SAME deep avatar pipeline reels/chat/status/
 * profile/search already have (see ChatAvatarBinder in feature-chat) to the
 * Calls module: the call history list (CallHistoryAdapter), the contact
 * bottom sheet + online-users row + per-contact history sheet
 * (CallsFragment), the in-call screen (CallActivity), and the incoming
 * group-call screen (IncomingGroupCallActivity).
 *
 * Before this, every one of those screens called a flat
 * {@code Glide.load(photoUrl).override(96, 96)} directly — un-tiered (a
 * literal 96 regardless of the view's actual dp size or device density), no
 * CDN transform/format param, no L2/L3 reuse across screens (a partner's
 * avatar decoded fresh in the call list AND again in CallActivity AND again
 * in the bottom sheet), no velocity-aware prefetch for the scrolling call
 * list, and no lifecycle-aware cancel on row recycle.
 *
 * This class is the Calls module's single choke point for all of that,
 * exactly mirroring ChatAvatarBinder's shape so avatar behavior is
 * consistent across every list/screen in the app:
 *
 *  • url()      — AvatarUrlBuilder#buildResponsive: shared AvatarSizeTier
 *                 bucket + density-bucketed dpr_ param + WebP/AVIF format
 *                 param (bestFormatParam) + ?v=<avatarVersion> cache-bust,
 *                 all server-side (see AvatarUrlBuilder class doc).
 *  • bind()     — L2 memory fast-path (CallAvatarL2Cache, survives
 *                 TRIM_MEMORY_MODERATE) before falling back to a real Glide
 *                 decode; decode result is written back into L2 (+ L3 disk)
 *                 so the next bind of this exact URL — same row re-scrolled,
 *                 or the SAME partner opened in CallActivity right after —
 *                 is instant.
 *  • cancel()   — call from onViewRecycled(); stops an in-flight request for
 *                 a row that just scrolled off screen.
 *  • prefetch() — velocity-based depth (same thresholds as every other
 *                 AvatarPrefetcher/*AvatarBinder in the app — fast fling
 *                 skips entirely, slow scroll warms several rows ahead),
 *                 using DiskCacheStrategy.DATA (raw bytes only, decode
 *                 deferred to a real bind()) so a row that gets flung past
 *                 without ever actually binding never pays a speculative
 *                 CPU decode.
 *
 * ETag/Last-Modified conditional requests are NOT re-implemented here —
 * same as ChatAvatarBinder, every Glide request app-wide (this module
 * included) already gets that for free via CallxGlideModule routing through
 * AvatarHttpCache's shared OkHttpClient.
 *
 * CallLog (the model backing this module) has no avatarVersion field today
 * — call sites that don't have one simply pass 0, which AvatarUrlBuilder
 * treats as "omit the cache-busting param" (see appendVersion's version<=0
 * no-op), so this pipeline is safe to adopt without a model/schema change;
 * a real avatarVersion can be threaded through later the same way
 * ChatAvatarBinder's callers do.
 */
public final class CallAvatarBinder {

    private CallAvatarBinder() {}

    /** Call history row avatar (~48dp circleimageview) → SMALL(48) tier. */
    public static final AvatarSizeTier LIST_TIER = AvatarSizeTier.forViewSizeDp(48);

    /** Bottom sheet / online-users / history-sheet avatar (~64dp) → MEDIUM(64) tier. */
    public static final AvatarSizeTier SHEET_TIER = AvatarSizeTier.forViewSizeDp(64);

    /** In-call screen + incoming group-call avatar (96px override today) → LARGE(96) tier. */
    public static final AvatarSizeTier CALL_TIER = AvatarSizeTier.forViewSizeDp(96);

    // Same thresholds/depths as AvatarPrefetcher/ChatAvatarBinder/FollowAvatarBinder
    // — kept in sync deliberately so "fast fling" and "slow scroll" mean the
    // same thing across every avatar list in the app.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — flinging past rows
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — deliberate scroll
    private static final int DEPTH_DEFAULT = 1;
    private static final int DEPTH_SLOW    = 4;
    private static final int DEPTH_FAST    = 0;

    /** Same API-level HARDWARE-bitmap gate every other *AvatarBinder uses. */
    public static final DecodeFormat AVATAR_FORMAT =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? DecodeFormat.PREFER_ARGB_8888
                    : DecodeFormat.PREFER_RGB_565;

    /** Read-only view over whatever list a screen is scrolling — same shape as ChatAvatarBinder.AvatarSource. */
    public interface AvatarSource {
        String photo(int index);
        long avatarVersion(int index);
        int size();
    }

    /** Server-side responsive, version-tagged URL for one avatar. */
    public static String url(Context ctx, String photo, long avatarVersion, AvatarSizeTier tier) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, tier, avatarVersion);
    }

    /**
     * Bind a VISIBLE avatar. Checks L2 memory first (instant paint, survives
     * MODERATE trim); otherwise a full RESOURCE-cached Glide decode,
     * analytics-wrapped so this bind's hits feed the same L2/L3/Glide/CDN
     * split every other avatar screen records into. A successful decode is
     * written back into L2 (+ L3 disk) so the very next bind of this exact
     * URL (re-scroll, warm restart, or the same partner opened elsewhere in
     * the module) is instant.
     */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion,
                             AvatarSizeTier tier, int placeholderRes) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            return;
        }
        String url = url(ctx, photo, avatarVersion, tier);
        Bitmap l2Hit = CallAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
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
                    return false; // let Glide still apply the error placeholder
                }
                @Override
                public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                                                com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                    AvatarCacheAnalytics.getInstance(ctx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    if (resource instanceof android.graphics.drawable.BitmapDrawable) {
                        Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) resource).getBitmap();
                        CallAvatarL2Cache.get(ctx).put(url, bmp);
                        CallAvatarL2Cache.l3(ctx).put(url, bmp);
                    }
                    return false; // let Glide still deliver the drawable into the ImageView
                }
            })
            .into(iv);
    }

    /** Convenience overload for the common LIST_TIER call-history row case. */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        bind(ctx, iv, photo, avatarVersion, LIST_TIER, placeholderRes);
    }

    /**
     * FIX (lifecycle-aware cancel): call from CallHistoryAdapter's
     * onViewRecycled(). Stops an in-flight request for a row that just
     * scrolled off screen instead of letting it keep competing for
     * bandwidth/decode time against whatever's now actually visible.
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling past the
     * call list → skip prefetch entirely (would be wasted work — the user
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
            String url = url(appCtx, photo, source.avatarVersion(i), LIST_TIER);
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
