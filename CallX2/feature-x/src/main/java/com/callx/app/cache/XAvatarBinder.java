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
 * XAvatarBinder — brings the SAME deep avatar pipeline reels/chat/calls/
 * status/profile/search/YouTube already have (see CallAvatarBinder in
 * feature-calls, YouTubeAvatarBinder in feature-youtube) to the X module:
 * the home feed (XTweetAdapter), notifications (XNotificationAdapter), the
 * DM list (XMessagePreviewAdapter) and DM conversation header
 * (XDMConversationActivity), the tweet detail screen
 * (XTweetDetailActivity), the profile sheet (XProfileSheet), explore's
 * "who to follow" cards and user search rows (XExploreFragment,
 * XSearchActivity), and the blocked/muted user lists
 * (XBlockedUsersActivity).
 *
 * Before this, every one of those screens called a flat
 * {@code Glide.load(avatarUrl).circleCrop().override(96, 96)} (or no
 * override at all) directly — un-tiered, no CDN transform/format param, no
 * L2/L3 reuse across screens (the SAME author's avatar decoded fresh in the
 * feed AND again in notifications AND again in the DM list AND again in the
 * profile sheet), no velocity-aware prefetch for the scrolling feed/
 * notifications/DM lists, and no lifecycle-aware cancel on row recycle.
 *
 * This class is the X module's single choke point for all of that, exactly
 * mirroring CallAvatarBinder/ChatAvatarBinder/YouTubeAvatarBinder's shape so
 * avatar behavior is consistent across every list/screen in the app:
 *
 *  • url()      — AvatarUrlBuilder#buildResponsive: shared AvatarSizeTier
 *                 bucket + density-bucketed dpr_ param + WebP/AVIF format
 *                 param (bestFormatParam) + ?v=<avatarVersion> cache-bust,
 *                 all server-side (see AvatarUrlBuilder class doc).
 *  • bind()     — L2 memory fast-path (XAvatarL2Cache, survives
 *                 TRIM_MEMORY_MODERATE) before falling back to a real Glide
 *                 decode; decode result is written back into L2 (+ L3 disk)
 *                 so the next bind of this exact URL — same row
 *                 re-scrolled, or the SAME author opened in the profile
 *                 sheet right after — is instant.
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
 * same as every other *AvatarBinder, every Glide request app-wide (this
 * module included) already gets that for free via CallxGlideModule routing
 * through AvatarHttpCache's shared OkHttpClient.
 *
 * None of XTweet/XUser/XNotification/XMessagePreviewAdapter.ConversationPreview
 * (the models backing this module) carry an avatarVersion field today — call
 * sites that don't have one simply pass 0, which AvatarUrlBuilder treats as
 * "omit the cache-busting param" (see appendVersion's version<=0 no-op), so
 * this pipeline is safe to adopt without a model/schema change; a real
 * avatarVersion can be threaded through later the same way ChatAvatarBinder's
 * callers do.
 *
 * NOTE: non-avatar imagery in this module — tweet/DM media thumbnails
 * (XTweetAdapter's media grid, XDMAdapter's ivMedia, link-preview images,
 * XComposeActivity's picked-image preview) and the profile banner
 * (XEditProfileActivity, XProfileSheet's ivBanner) — is deliberately left on
 * plain Glide, same as every other module's binder leaving non-avatar media
 * alone: those aren't small repeated circular avatars shared across screens,
 * so tiering/versioning them through THIS pipeline would be the wrong cache
 * key story. XEditProfileActivity's OWN avatar preview (self-upload,
 * edited in place) and XActivity's nav-drawer self-avatar (loadMyAvatar) are
 * left on plain Glide too, for the same reason CallAvatarBinder-family
 * binders don't intercept self-profile previews — those are the signed-in
 * user's own avatar, not a shared downloaded avatar reused across screens.
 */
public final class XAvatarBinder {

    private XAvatarBinder() {}

    /** Feed/notification/DM-list/search-row avatar (36–44dp) → SMALL(48) tier. */
    public static final AvatarSizeTier LIST_TIER = AvatarSizeTier.forViewSizeDp(44);

    /** Explore "who to follow" card avatar (56dp) → MEDIUM(64) tier. */
    public static final AvatarSizeTier SUGGESTION_TIER = AvatarSizeTier.forViewSizeDp(56);

    /** Profile sheet header avatar (68dp) → LARGE(96) tier. */
    public static final AvatarSizeTier HEADER_TIER = AvatarSizeTier.forViewSizeDp(68);

    // Same thresholds/depths as AvatarPrefetcher/CallAvatarBinder/ChatAvatarBinder/
    // YouTubeAvatarBinder — kept in sync deliberately so "fast fling" and "slow
    // scroll" mean the same thing across every avatar list in the app.
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

    /** Read-only view over whatever list a screen is scrolling — same shape as CallAvatarBinder.AvatarSource. */
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
     * URL (re-scroll, warm restart, or the same author opened elsewhere in
     * the module) is instant.
     */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion,
                             AvatarSizeTier tier, int placeholderRes) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            return;
        }
        String url = url(ctx, photo, avatarVersion, tier);
        Bitmap l2Hit = XAvatarL2Cache.get(ctx).get(url);
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
                        XAvatarL2Cache.get(ctx).put(url, bmp);
                        XAvatarL2Cache.l3(ctx).put(url, bmp);
                    }
                    return false; // let Glide still deliver the drawable into the ImageView
                }
            })
            .into(iv);
    }

    /** Convenience overload for the common LIST_TIER row case. */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        bind(ctx, iv, photo, avatarVersion, LIST_TIER, placeholderRes);
    }

    /**
     * FIX (lifecycle-aware cancel): call from an adapter's onViewRecycled().
     * Stops an in-flight request for a row that just scrolled off screen
     * instead of letting it keep competing for bandwidth/decode time
     * against whatever's now actually visible.
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling past a
     * list → skip prefetch entirely (would be wasted work — the user blows
     * past a row before its avatar even finishes decoding); slow/
     * deliberate scroll → warm several rows ahead. Uses
     * {@link DiskCacheStrategy#DATA} — raw bytes cached, NOT the full
     * decoded bitmap — for rows that might still get flung past without
     * ever binding; the full RESOURCE decode only happens in {@link #bind}
     * once a row genuinely becomes visible, so this never pays CPU decode
     * cost speculatively, only the (cheap, disk-cached) network fetch.
     */
    public static void prefetch(Context context, AvatarSource source, int fromIndex, float velocityPxPerMs) {
        prefetch(context, source, fromIndex, velocityPxPerMs, LIST_TIER);
    }

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
