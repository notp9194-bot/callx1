package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

/**
 * CommunityAvatarBinder — same deep pipeline every other avatar screen has
 * (shared {@link AvatarSizeTier} bucketing + responsive CDN URL + L2/L3
 * write-through, all through {@link ChatAvatarL2Cache} since this is the
 * same feature-chat module), now covering the Community module's OWN
 * icons: the community icon itself (CommunityActivity toolbar + join-gate
 * header), a post author's avatar (CommunityPostAdapter), and a member row
 * avatar (CommunityMemberAdapter) — CommunityGroupAdapter's linked-GROUP
 * icon stays on {@link GroupAvatarBinder} since that's a group's own icon,
 * just surfaced inside a community list.
 *
 * Before this every one of those was a flat {@code Glide.load(url)} — the
 * community icon/gate icon had no override() at all (full-resolution
 * decode), and the post/member avatars used a hand-rolled dp*density
 * override with no tier bucketing, so the exact same user's photo used as
 * both a post author and a member-list row decoded and cached TWICE.
 *
 * Two shapes, matching how the two call sites already work:
 *  • bindIcon()   — plain ImageView target (community/gate icon).
 *  • bindBitmap() — CustomTarget<Bitmap> target (post/member rows are
 *                   Canvas views, same as CommunityMemberAvatarStackView —
 *                   they need a raw Bitmap, not a Drawable/ImageView).
 */
public final class CommunityAvatarBinder {

    private CommunityAvatarBinder() {}

    /** CommunityActivity toolbar icon (iv_community_icon, 36dp). */
    public static final AvatarSizeTier TIER_TOOLBAR = AvatarSizeTier.forViewSizeDp(36);
    /** CommunityActivity join-gate header icon (iv_gate_icon, 88dp). */
    public static final AvatarSizeTier TIER_GATE = AvatarSizeTier.forViewSizeDp(88);
    /** CommunityPostAdapter author avatar (CommunityPostCanvasView.avatarPx = 40dp). */
    public static final AvatarSizeTier TIER_POST_AUTHOR = AvatarSizeTier.forViewSizeDp(40);
    /** CommunityMemberAdapter row avatar (44dp). */
    public static final AvatarSizeTier TIER_MEMBER = AvatarSizeTier.forViewSizeDp(44);

    public static final DecodeFormat AVATAR_FORMAT =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? DecodeFormat.PREFER_ARGB_8888
                    : DecodeFormat.PREFER_RGB_565;

    /** Server-side responsive, tier-bucketed URL. Communities/posts/members don't carry an avatarVersion counter (see GroupAvatarBinder doc), so no version param here either. */
    public static String url(Context ctx, String rawUrl, AvatarSizeTier tier) {
        if (rawUrl == null || rawUrl.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, rawUrl, tier);
    }

    /** Delivers a resolved Bitmap (or null on clear/failure) to a Canvas-based row. */
    public interface BitmapCallback {
        void onBitmap(@Nullable Bitmap bitmap);
    }

    /**
     * ImageView bind — CommunityActivity's toolbar icon and join-gate
     * header icon. L2 fast-path first, else a tier-sized circleCrop decode
     * written back into L2+L3 on success.
     */
    public static void bindIcon(Context ctx, ImageView iv, String rawUrl, AvatarSizeTier tier, int placeholderRes) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            if (placeholderRes != 0) iv.setImageResource(placeholderRes);
            return;
        }
        String url = url(ctx, rawUrl, tier);
        Bitmap l2Hit = ChatAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }
        RequestOptions opts = RequestOptions.circleCropTransform()
                .format(AVATAR_FORMAT)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE);
        com.bumptech.glide.RequestBuilder<Drawable> req = Glide.with(ctx).load(url).dontAnimate().apply(opts);
        if (placeholderRes != 0) req = req.placeholder(placeholderRes).error(placeholderRes);
        req.listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                    return false;
                }
                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                                DataSource dataSource, boolean isFirstResource) {
                    AvatarCacheAnalytics.getInstance(ctx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    if (resource instanceof BitmapDrawable) {
                        Bitmap bmp = ((BitmapDrawable) resource).getBitmap();
                        ChatAvatarL2Cache.get(ctx).put(url, bmp);
                        ChatAvatarL2Cache.l3(ctx).put(url, bmp);
                    }
                    return false;
                }
            })
            .into(iv);
    }

    /** Call from onViewRecycled()/onDestroy() for an ImageView bound via {@link #bindIcon}. */
    public static void cancelIcon(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
    }

    /**
     * Canvas-row bind (post author / member avatar). Returns the in-flight
     * {@link Target} so the caller can store+cancel it in onViewRecycled(),
     * exactly like the raw CustomTarget these adapters used to build by
     * hand — or {@code null} when nothing is in flight (empty url, or an
     * L2 hit already delivered synchronously).
     */
    public static Target<Bitmap> bindBitmap(Context ctx, String rawUrl, AvatarSizeTier tier, BitmapCallback callback) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            callback.onBitmap(null);
            return null;
        }
        String url = url(ctx, rawUrl, tier);
        Bitmap l2Hit = ChatAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            callback.onBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return null;
        }
        CustomTarget<Bitmap> target = new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                ChatAvatarL2Cache.get(ctx).put(url, resource);
                ChatAvatarL2Cache.l3(ctx).put(url, resource);
                callback.onBitmap(resource);
            }
            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
                callback.onBitmap(null);
            }
        };
        Glide.with(ctx).asBitmap()
            .load(url)
            .apply(RequestOptions.circleCropTransform()
                    .format(AVATAR_FORMAT)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE))
            .listener(new RequestListener<Bitmap>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Bitmap> t, boolean isFirstResource) {
                    return false;
                }
                @Override
                public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> t,
                                                DataSource dataSource, boolean isFirstResource) {
                    AvatarCacheAnalytics.getInstance(ctx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    return false;
                }
            })
            .into(target);
        return target;
    }

    /** Call from onViewRecycled() for a Target returned by {@link #bindBitmap}. Safe to call with null. */
    public static void cancelBitmap(Context ctx, Target<Bitmap> target) {
        if (target == null) return;
        try { Glide.with(ctx).clear(target); } catch (Exception ignored) {}
    }
}
