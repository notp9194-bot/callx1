package com.callx.app.cache;

import android.content.Context;
import android.widget.ImageView;

import com.bumptech.glide.load.DecodeFormat;

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
 * FIX (avatar-pipeline parity — code dedup): bind()/cancel() used to carry
 * their own full copy of the L2-check -> Glide-decode -> L2/L3-write-through
 * logic — the exact same shape ChatAvatarBinder used to hand-roll before it
 * was extracted into {@link AvatarBinderCore}. Now delegates to that one
 * shared implementation instead of a second (by-then third-generation)
 * copy: same HIGH-priority bind, same "skip re-request if already loaded"
 * ImageView tag check, same CacheDashboardStats + AvatarCacheAnalytics
 * recording, same L2/L3 write-through. This class only supplies what's
 * genuinely group-specific: which cache instance ({@link ChatAvatarL2Cache}
 * — a group icon and a chat-row avatar are both "small circular image this
 * module shows a lot of", no reason to split them into a separate
 * instance), which tier per call site, and the decode format.
 *
 * Groups don't carry an avatarVersion counter the way user profiles do
 * (see AvatarUrlBuilder's 4-arg build()/buildResponsive() overloads), so
 * this always passes avatarVersion=0 into {@link AvatarBinderCore} — a
 * genuine icon change already produces a brand-new Cloudinary URL at
 * upload time, which is cache-bust enough on its own, and
 * {@code AvatarUrlBuilder#appendVersion} no-ops for version <= 0, so this
 * is byte-for-byte the same URL the old un-versioned overload produced.
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

    /** This module's own L2/L3 cache pair, handed to AvatarBinderCore per
     *  call — same {@link ChatAvatarL2Cache} instance ChatAvatarBinder uses,
     *  since a group icon and a chat-row avatar share the same module's
     *  trim-independence boundary. */
    private static final com.callx.app.cache.AvatarBinderCore.CacheProvider CACHE =
            new com.callx.app.cache.AvatarBinderCore.CacheProvider() {
                @Override public AvatarL2MemoryCache l2(Context ctx) { return ChatAvatarL2Cache.get(ctx); }
                @Override public AvatarL3DiskCache l3(Context ctx) { return ChatAvatarL2Cache.l3(ctx); }
            };

    /** Server-side responsive, tier-bucketed URL for a group icon. */
    public static String url(Context ctx, String iconUrl, AvatarSizeTier tier) {
        if (iconUrl == null || iconUrl.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, iconUrl, tier);
    }

    /**
     * Bind a group icon into an ImageView. Routes through
     * {@link AvatarBinderCore#bind} — L2 memory fast-path first (instant
     * paint, survives TRIM_MEMORY_MODERATE), otherwise a real Glide decode
     * at the tier's density-bucketed size, written back into L2 (+ L3 disk)
     * on success so the next bind of this exact icon (toolbar → header, or
     * a warm restart) is instant.
     */
    public static void bind(Context ctx, ImageView iv, String iconUrl, AvatarSizeTier tier, int placeholderRes) {
        com.callx.app.cache.AvatarBinderCore.bind(ctx, iv, iconUrl, /*avatarVersion=*/0L,
                CACHE, new com.callx.app.cache.AvatarBinderCore.BindOptions(
                        tier, AVATAR_FORMAT, /*circleCrop=*/true, /*dontAnimate=*/true,
                        /*recordDashboardStats=*/true, placeholderRes));
    }

    /** Call from onDestroy()/onViewRecycled() to stop an in-flight request. */
    public static void cancel(Context ctx, ImageView iv) {
        com.callx.app.cache.AvatarBinderCore.cancel(ctx, iv);
    }
}
