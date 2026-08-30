package com.callx.app.profile;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import com.callx.app.cache.AvatarCacheAnalytics;
import com.callx.app.cache.ReelsAvatarL2Cache;
import com.callx.app.utils.AvatarSizeTier;

/**
 * ProfilePreviewAvatarBinder — brings the avatar pipeline to
 * ReelProfileSetupActivity / ReelEditProfileActivity's own-avatar +
 * banner previews.
 *
 * These two screens were the last "plain Glide.with().load()" holdouts:
 * both loaded {@code thumbUrl}/{@code photoUrl}/{@code bannerUrl} with a
 * flat {@code .override(480, 853)} regardless of what was actually being
 * bound into — that size is roughly a full-screen 9:16 frame, but
 * {@code iv_reel_setup_avatar}/{@code iv_reel_edit_avatar} is an 80dp
 * circle. Every avatar decode here was requesting ~853px of height for an
 * ~80dp target, the exact class of oversized-decode bug already fixed
 * elsewhere for the Home feed strips (see STRIP_THUMB_DECODE_PX).
 *
 * bindAvatar() fixes that by routing through the shared
 * {@link AvatarSizeTier} bucketing (LARGE — smallest tier ≥ 80dp) and
 * reuses reels' {@link ReelsAvatarL2Cache}: this same photo is very
 * likely already sitting in that cache from being viewed elsewhere as
 * this user's own reel-owner avatar, so a cache hit here means an
 * instant paint with zero network/decode; either way, a fresh decode is
 * written back so subsequent visits to setup/edit/anywhere else avatar
 * is shown are instant too.
 *
 * bindBanner() is NOT tier-bucketed — a profile banner is a unique
 * full-width cover photo, not a small reused avatar tile, so there is no
 * cross-screen reuse to gain from a shared cache. The fix there is
 * simpler: stop hardcoding 480x853 and instead decode at the view's
 * actual pixel footprint (screen width × the banner's fixed 140dp
 * height), still RGB_565 + RESOURCE-cached.
 */
public final class ProfilePreviewAvatarBinder {

    private ProfilePreviewAvatarBinder() {}

    private static final AvatarSizeTier AVATAR_TIER = AvatarSizeTier.LARGE; // covers the 80dp preview circle
    private static final int BANNER_HEIGHT_DP = 140; // matches iv_reel_setup_banner / iv_reel_edit_banner height

    /** Own-avatar preview (setup + edit screens). photo is a fresh Cloudinary URL straight from upload/Firebase — no avatarVersion tracked here, so version is omitted (safe no-op, see AvatarUrlBuilder#appendVersion). */
    public static void bindAvatar(Context ctx, ImageView iv, String photoUrl) {
        if (photoUrl == null || photoUrl.isEmpty()) return;

        Bitmap l2Hit = ReelsAvatarL2Cache.get(ctx).get(photoUrl);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }

        int px = com.callx.app.utils.AvatarUrlBuilder.tierPx(ctx, AVATAR_TIER);
        Glide.with(ctx)
            .load(photoUrl)
            .apply(new RequestOptions()
                .override(px, px)
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE))
            .listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                    return false;
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                    AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    if (resource instanceof BitmapDrawable) {
                        Bitmap bmp = ((BitmapDrawable) resource).getBitmap();
                        ReelsAvatarL2Cache.get(ctx).put(photoUrl, bmp);
                        ReelsAvatarL2Cache.l3(ctx).put(photoUrl, bmp);
                    }
                    return false;
                }
            })
            .into(iv);
    }

    /** Banner preview — decoded at its real on-screen pixel size instead of a flat 480x853 guess. Not tiered/cached — unique cover image, no reuse to gain. */
    public static void bindBanner(Context ctx, ImageView iv, String bannerUrl) {
        if (bannerUrl == null || bannerUrl.isEmpty()) return;

        Resources res = ctx.getResources();
        int widthPx  = res.getDisplayMetrics().widthPixels;
        int heightPx = Math.round(BANNER_HEIGHT_DP * res.getDisplayMetrics().density);

        Glide.with(ctx)
            .load(bannerUrl)
            .apply(new RequestOptions()
                .override(widthPx, heightPx)
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE))
            .into(iv);
    }

    /** Lifecycle-aware cancel — call from onDestroy() so an in-flight avatar/banner decode doesn't keep running (and can't crash trying to update views) after the screen is gone. */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
    }
}
