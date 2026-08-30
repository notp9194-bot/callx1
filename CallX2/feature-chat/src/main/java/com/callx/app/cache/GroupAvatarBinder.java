package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

/**
 * GroupAvatarBinder — the group ICON itself (GroupChatActivity toolbar,
 * GroupInfoActivity header), as opposed to ChatAvatarBinder which only ever
 * covers per-user rows (chat list rows / group MEMBER list rows).
 *
 * Before this, both call sites were a flat
 * {@code Glide.load(groupPhoto/iconUrl).override(720, 720)} — a fixed
 * 720x720 decode for a 32dp toolbar circle and a 96dp header circle alike,
 * no CDN transform/format param, no L2/L3 reuse, and no cache-bust when a
 * group's icon is changed mid-session on another device.
 *
 * Mirrors ChatAvatarBinder's shape (same module, same
 * {@link ChatAvatarL2Cache} instance — a group icon and a chat-row avatar
 * are both "small circular image this module shows a lot of", no reason to
 * split them into a separate L2/L3 instance and fragment the module's own
 * trim-independence boundary further).
 *
 * Groups don't carry an avatarVersion counter the way user profiles do
 * (see AvatarUrlBuilder's 4-arg build()/buildResponsive() overloads), so
 * this always uses the un-versioned overload — a genuine icon change
 * already produces a brand-new Cloudinary URL at upload time, which is
 * cache-bust enough on its own.
 */
public final class GroupAvatarBinder {

    private GroupAvatarBinder() {}

    /** GroupChatActivity toolbar (iv_partner_avatar, 32dp). */
    public static final AvatarSizeTier TIER_TOOLBAR = AvatarSizeTier.forViewSizeDp(32);
    /** GroupInfoActivity header (iv_group_icon, 96dp). */
    public static final AvatarSizeTier TIER_HEADER = AvatarSizeTier.forViewSizeDp(96);
    /** List-row icon (CommunityGroupAdapter's linked-group row, iv_group_icon, 50dp). */
    public static final AvatarSizeTier TIER_LIST_ROW = AvatarSizeTier.forViewSizeDp(50);

    public static final DecodeFormat AVATAR_FORMAT =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? DecodeFormat.PREFER_ARGB_8888
                    : DecodeFormat.PREFER_RGB_565;

    /** Server-side responsive, tier-bucketed URL for a group icon. */
    public static String url(Context ctx, String iconUrl, AvatarSizeTier tier) {
        if (iconUrl == null || iconUrl.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, iconUrl, tier);
    }

    /**
     * Bind a group icon into an ImageView. L2 memory fast-path first
     * (instant paint, survives TRIM_MEMORY_MODERATE); otherwise a real
     * Glide decode at the tier's density-bucketed size, written back into
     * L2 (+ L3 disk) on success so the next bind of this exact icon
     * (toolbar → header, or a warm restart) is instant.
     */
    public static void bind(Context ctx, ImageView iv, String iconUrl, AvatarSizeTier tier, int placeholderRes) {
        if (iconUrl == null || iconUrl.isEmpty()) {
            iv.setImageResource(placeholderRes);
            return;
        }
        String url = url(ctx, iconUrl, tier);
        Bitmap l2Hit = ChatAvatarL2Cache.get(ctx).get(url);
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
            .listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                    return false;
                }
                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                                com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
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

    /** Call from onDestroy()/onViewRecycled() to stop an in-flight request. */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
    }
}
