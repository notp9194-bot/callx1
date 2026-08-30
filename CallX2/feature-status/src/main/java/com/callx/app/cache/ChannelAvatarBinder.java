package com.callx.app.cache;

import android.content.Context;
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

import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

/**
 * ChannelAvatarBinder — the CHANNEL's own icon (ChannelViewerActivity header,
 * ChannelEditActivity/CreateChannelActivity edit preview, ChannelJoinActivity
 * preview, ChannelSectionAdapter's followed/suggested rows, ExploreChannelsActivity
 * list, and the @channel mention dropdown in ChannelPostComposerActivity) —
 * as opposed to a channel MEMBER's own avatar (ChannelAdminActivity's admin
 * rows, ChannelFollowersActivity, ChannelReactionsDetailActivity,
 * ChannelReplyActivity), which are per-person photos and stay on whichever
 * generic user-avatar path they already used; this binder is scoped to the
 * one photo url a channel itself carries (ChannelEntity#iconUrl).
 *
 * NOTE: ChannelAdminActivity has no channel-icon header of its own to fix —
 * its toolbar is text-only ("Admins — <name>"); its only avatar bind is the
 * per-admin row photo, which is a member avatar, not the channel icon.
 *
 * Before this, every call site here was a flat
 * {@code Glide.load(iconUrl).circleCrop()} with either no override() at all
 * (ChannelViewerActivity/EditActivity/JoinActivity — full-resolution decode
 * for a 38–90dp circle) or a fixed .override(96, 96) regardless of the
 * row's actual size (ChannelSectionAdapter) — no CDN transform/format
 * param, no L2/L3 reuse, and the exact same channel's icon shown in the
 * viewer header AND a section list row decoded/cached twice.
 *
 * Reuses {@link StatusAvatarL2Cache} (same feature-status module the
 * channel package lives in) rather than a new L2/L3 instance — same
 * "one instance per MODULE, not per screen" reasoning as ChatAvatarL2Cache
 * covering both chat rows and community icons.
 *
 * Channels don't carry an avatarVersion counter (see GroupAvatarBinder's
 * identical note for groups), so this always uses the un-versioned
 * {@link AvatarUrlBuilder#buildResponsive(Context, String, AvatarSizeTier)}
 * overload — a genuine icon change already produces a brand-new Cloudinary
 * URL at upload time.
 */
public final class ChannelAvatarBinder {

    private ChannelAvatarBinder() {}

    /** ChannelViewerActivity toolbar (iv_channel_viewer_icon, 38dp). */
    public static final AvatarSizeTier TIER_VIEWER = AvatarSizeTier.forViewSizeDp(38);
    /** ChannelEditActivity / CreateChannelActivity edit preview (iv_create_channel_icon, 90dp). */
    public static final AvatarSizeTier TIER_EDIT = AvatarSizeTier.forViewSizeDp(90);
    /** ChannelJoinActivity preview (iv_join_channel_icon, 88dp). */
    public static final AvatarSizeTier TIER_JOIN = AvatarSizeTier.forViewSizeDp(88);
    /** ChannelSectionAdapter followed/suggested rows (iv_channel_icon, 50dp). */
    public static final AvatarSizeTier TIER_SECTION_ROW = AvatarSizeTier.forViewSizeDp(50);
    /** ExploreChannelsActivity list row (iv_explore_channel_icon, 48dp). */
    public static final AvatarSizeTier TIER_EXPLORE_ROW = AvatarSizeTier.forViewSizeDp(48);
    /** ChannelPostComposerActivity @channel mention dropdown (iv_mention_icon, 30dp). */
    public static final AvatarSizeTier TIER_MENTION = AvatarSizeTier.forViewSizeDp(30);

    public static final DecodeFormat AVATAR_FORMAT =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? DecodeFormat.PREFER_ARGB_8888
                    : DecodeFormat.PREFER_RGB_565;

    /** Server-side responsive, tier-bucketed URL for a channel icon. */
    public static String url(Context ctx, String iconUrl, AvatarSizeTier tier) {
        if (iconUrl == null || iconUrl.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, iconUrl, tier);
    }

    /**
     * Bind a channel icon into an ImageView. L2 memory fast-path first
     * (instant paint, survives TRIM_MEMORY_MODERATE); otherwise a real
     * Glide decode at the tier's density-bucketed size, written back into
     * L2 (+ L3 disk) on success so the next bind of this exact icon —
     * viewer header → edit screen, section row → explore row, warm restart —
     * is instant.
     */
    public static void bind(Context ctx, ImageView iv, String iconUrl, AvatarSizeTier tier, int placeholderRes) {
        if (iconUrl == null || iconUrl.isEmpty()) {
            if (placeholderRes != 0) iv.setImageResource(placeholderRes);
            return;
        }
        String url = url(ctx, iconUrl, tier);
        Bitmap l2Hit = StatusAvatarL2Cache.get(ctx).get(url);
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
                        StatusAvatarL2Cache.get(ctx).put(url, bmp);
                        StatusAvatarL2Cache.l3(ctx).put(url, bmp);
                    }
                    return false;
                }
            })
            .into(iv);
    }

    /** Call from onDestroy()/onViewRecycled() to stop an in-flight request. */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
    }
}
