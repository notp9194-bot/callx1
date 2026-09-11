package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

/**
 * ChatAvatarBinder — the chat list's (ChatListAdapter / ChatsFragment /
 * GroupMemberAdapter) own thin wrapper around the shared
 * {@link AvatarBinderCore} pipeline in :core.
 *
 * bind()/cancel()/prefetch() used to each carry their own full copy of the
 * L2-check -> Glide-decode -> L2/L3-write-through logic (a second copy of
 * exactly what FollowAvatarBinder in feature-reels also carried) -- that
 * shared shape now lives once in AvatarBinderCore, and this class only
 * supplies what's genuinely chat-specific:
 *   - which cache instance    -- ChatAvatarL2Cache (its own TRIM_MEMORY_MODERATE
 *                                 lifecycle, independent of reels' cache -- see
 *                                 AvatarL2MemoryCache's class doc for why that
 *                                 per-module independence matters)
 *   - which tier(s)           -- TIER (list rows) / TIER_INLINE (canvas-drawn
 *                                 reel-share header)
 *   - bind knobs              -- circle-crop in software (chat rows aren't all
 *                                 CircleImageView), HARDWARE-eligible decode
 *                                 format, dashboard-stats recording
 *
 * bindBitmap() (the canvas-target variant, for MessageBubbleCanvasView which
 * isn't an ImageView Glide can .into()) stays chat-specific since
 * AvatarBinderCore's bind() is ImageView-only -- it still shares the same
 * ChatAvatarL2Cache/L3 entries as bind() above for an identical photo/tier.
 */
public final class ChatAvatarBinder {

    private ChatAvatarBinder() {}

    /** Chat list row avatar (~50dp, item_chat row) -- SMALL(48) under-resolves it, so this rounds up to MEDIUM(64). */
    private static final AvatarSizeTier TIER = AvatarSizeTier.forViewSizeDp(50);

    /** Small inline avatars drawn straight onto a canvas (reel-share card
     *  header, 24dp) -- TINY tier, same bucket every other ~24-32dp avatar
     *  in the app shares (see AvatarSizeTier class doc on cross-screen
     *  cache reuse). */
    private static final AvatarSizeTier TIER_INLINE = AvatarSizeTier.forViewSizeDp(24);

    /**
     * v90-equivalent avatar decode format -- same API-level HARDWARE-bitmap
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

    /** Read-only view over whatever list a screen is scrolling. Extends
     *  AvatarBinderCore's own interface (rather than re-declaring the same
     *  3 methods) so every existing anonymous implementation across
     *  ChatListAdapter/GroupMemberAdapter/etc. keeps compiling unchanged
     *  while also satisfying AvatarBinderCore.prefetch()'s signature. */
    public interface AvatarSource extends com.callx.app.cache.AvatarBinderCore.AvatarSource {}

    /** This module's own L2/L3 cache pair, handed to AvatarBinderCore per
     *  call instead of AvatarBinderCore owning/sharing an instance -- keeps
     *  chat's cache lifecycle fully independent of reels'/any other
     *  module's, same as before this class delegated its plumbing. */
    private static final com.callx.app.cache.AvatarBinderCore.CacheProvider CACHE =
            new com.callx.app.cache.AvatarBinderCore.CacheProvider() {
                @Override public AvatarL2MemoryCache l2(Context ctx) { return ChatAvatarL2Cache.get(ctx); }
                @Override public AvatarL3DiskCache l3(Context ctx) { return ChatAvatarL2Cache.l3(ctx); }
            };

    /** Server-side responsive, version-tagged URL for one row -- thumbUrl-equivalent input, same as the reel owner avatar. */
    public static String url(Context ctx, String photo, long avatarVersion) {
        return com.callx.app.cache.AvatarBinderCore.url(ctx, photo, avatarVersion, TIER);
    }

    /**
     * FIX (advance avatar optimization -- reused from FollowAvatarBinder /
     * this class's own ImageView bind()): canvas-drawn avatars (e.g. the
     * chat reel-share card header, rendered by MessageBubbleCanvasView --
     * NOT an ImageView, so Glide can't .into() it directly) used to go
     * through a flat, un-tiered glide().asBitmap().load(rawUrl)
     * into a plain process-wide LruCache -- no responsive/version-tagged
     * URL (AvatarUrlBuilder#buildResponsive), no L2/L3 tier reuse with the
     * REST of chat's avatars, and none of the CDN/cache-tier analytics
     * every other avatar surface feeds into.
     *
     * This brings the exact same pipeline bind() above already gives
     * ImageView targets to a raw-Bitmap callback instead, so canvas
     * consumers get identical L2 memory fast-path, L2+L3 write-through on
     * a fresh decode, and AvatarCacheAnalytics recording -- sharing the
     * SAME ChatAvatarL2Cache entries an ImageView-bound avatar for the
     * same photo/tier would have populated (e.g. the legacy non-canvas
     * reel-share ViewHolder path, which now also calls bind() with
     * TIER_INLINE -- see MessagePagingAdapter).
     */
    public interface BitmapCallback {
        void onBitmap(Bitmap bitmap);
    }

    public static void bindBitmap(Context ctx, String photo, long avatarVersion, BitmapCallback callback) {
        if (photo == null || photo.isEmpty()) return;
        String url = AvatarUrlBuilder.buildResponsive(ctx, photo, TIER_INLINE, avatarVersion);
        if (url == null) return;

        Bitmap l2Hit = ChatAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null && !l2Hit.isRecycled()) {
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            callback.onBitmap(l2Hit);
            return;
        }

        int px = AvatarUrlBuilder.tierPx(ctx, TIER_INLINE);
        Glide.with(ctx)
            .asBitmap()
            .load(url)
            .apply(new RequestOptions()
                    .override(px, px)
                    .format(AVATAR_FORMAT)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE))
            .circleCrop()
            .listener(new com.bumptech.glide.request.RequestListener<Bitmap>() {
                @Override
                public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                                             com.bumptech.glide.request.target.Target<Bitmap> target, boolean isFirstResource) {
                    return false;
                }
                @Override
                public boolean onResourceReady(Bitmap resource, Object model,
                                                com.bumptech.glide.request.target.Target<Bitmap> target,
                                                com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                    AvatarCacheAnalytics.getInstance(ctx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    ChatAvatarL2Cache.get(ctx).put(url, resource);
                    ChatAvatarL2Cache.l3(ctx).put(url, resource);
                    return false; // let Glide still deliver the bitmap into the target below
                }
            })
            .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                        @androidx.annotation.Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                    callback.onBitmap(resource);
                }
                @Override
                public void onLoadCleared(@androidx.annotation.Nullable android.graphics.drawable.Drawable placeholder) {}
            });
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
        bind(ctx, iv, photo, avatarVersion, placeholderRes, TIER);
    }

    /** Same as {@link #bind(Context, ImageView, String, long, int)} but for
     *  a caller-specified tier -- e.g. TIER_INLINE for the ~24dp reel-share
     *  avatar, so it shares L2/L3 cache entries with bindBitmap()'s canvas
     *  path for the same photo instead of decoding/caching a second,
     *  differently-sized copy under the chat-list row's MEDIUM tier. */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes, AvatarSizeTier tier) {
        com.callx.app.cache.AvatarBinderCore.bind(ctx, iv, photo, avatarVersion,
                CACHE, new com.callx.app.cache.AvatarBinderCore.BindOptions(
                        tier, AVATAR_FORMAT, /*circleCrop=*/true, /*dontAnimate=*/true,
                        /*recordDashboardStats=*/true, placeholderRes));
    }

    /**
     * FIX (lifecycle-aware cancel): call from ChatListAdapter's
     * onViewRecycled(). Stops an in-flight request for a row that just
     * scrolled off screen instead of letting it keep competing for
     * bandwidth/decode time against whatever's now actually visible.
     */
    public static void cancel(Context ctx, ImageView iv) {
        com.callx.app.cache.AvatarBinderCore.cancel(ctx, iv);
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling past the
     * chat list -> skip prefetch entirely (would be wasted work -- the user
     * blows past a row before its avatar even finishes decoding); slow/
     * deliberate scroll -> warm several rows ahead. Uses
     * DiskCacheStrategy.DATA -- raw bytes cached, NOT the full
     * decoded bitmap -- for rows that might still get flung past without
     * ever binding; the full RESOURCE decode only happens in bind()
     * once a row genuinely becomes visible, so this never pays CPU decode
     * cost speculatively, only the (cheap, disk-cached) network fetch.
     */
    public static void prefetch(Context context, AvatarSource source, int fromIndex, float velocityPxPerMs) {
        com.callx.app.cache.AvatarBinderCore.prefetch(context, source, fromIndex, velocityPxPerMs, TIER, CACHE);
    }
}
