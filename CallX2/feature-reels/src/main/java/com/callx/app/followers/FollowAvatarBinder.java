package com.callx.app.followers;

import android.content.Context;
import android.widget.ImageView;

import com.bumptech.glide.load.DecodeFormat;

import com.callx.app.cache.AvatarL2MemoryCache;
import com.callx.app.cache.AvatarL3DiskCache;
import com.callx.app.cache.ReelsAvatarL2Cache;
import com.callx.app.utils.AvatarSizeTier;

/**
 * FollowAvatarBinder -- brings reels' full avatar pipeline to the unified
 * Followers/Following/Mutual/Suggested screen (FollowConnectionsActivity,
 * which replaced the old FollowersListActivity/FollowingListActivity/
 * MutualFollowersActivity trio). Those old activities' UserListAdapter DID
 * wire this class up correctly; the consolidation into
 * FollowConnectionsActivity regressed it back to a flat, untiered
 * Glide.load(photo) with no L2/L3 reuse, no velocity-aware prefetch,
 * and nothing cancelling a request once its row scrolled off screen -- this
 * class (and FollowConnectionsActivity's wiring of it) restores parity.
 *
 * v2 -- this class is now a thin wrapper around the shared
 * {@link com.callx.app.cache.AvatarBinderCore} pipeline in :core, which
 * ChatAvatarBinder (feature-chat) also delegates to. The bind()/cancel()/
 * prefetch() Glide plumbing used to be duplicated near-verbatim between
 * this class and ChatAvatarBinder; it now lives exactly once. What stays
 * HERE, deliberately: which cache instance to use -- {@link ReelsAvatarL2Cache}
 * -- and this screen's own tier/format/crop knobs. Reels' L2/L3 cache
 * lifecycle (independent TRIM_MEMORY_MODERATE survival from chat's own
 * cache) is completely unaffected by this -- AvatarBinderCore never owns or
 * shares a cache instance across modules, it's handed one per call.
 *
 * NOTE: no circleCropTransform() in bind() -- the row's ImageView is a real
 * CircleImageView, which already clips to a circle at draw time; applying
 * circleCrop() on top would allocate + draw a second, redundant bitmap on
 * every decode for no visual difference.
 */
public final class FollowAvatarBinder {

    private FollowAvatarBinder() {}

    /** Follow-list row avatar (~48dp) -- same coarse tier bucket the reel owner-avatar strip uses one size down from. */
    private static final AvatarSizeTier TIER = AvatarSizeTier.SMALL;

    /** Read-only view over whatever list a screen is scrolling, so prefetch() doesn't need to know about UserItem/ReelModel/etc. */
    public interface AvatarSource extends com.callx.app.cache.AvatarBinderCore.AvatarSource {}

    /** This module's own L2/L3 cache pair, handed to AvatarBinderCore per
     *  call -- keeps reels' cache lifecycle fully independent of chat's/any
     *  other module's, same as before this class delegated its plumbing. */
    private static final com.callx.app.cache.AvatarBinderCore.CacheProvider CACHE =
            new com.callx.app.cache.AvatarBinderCore.CacheProvider() {
                @Override public AvatarL2MemoryCache l2(Context ctx) { return ReelsAvatarL2Cache.get(ctx); }
                @Override public AvatarL3DiskCache l3(Context ctx) { return ReelsAvatarL2Cache.l3(ctx); }
            };

    /** Server-side responsive, version-tagged URL for one row -- same buildResponsive() upgrade as the reel owner avatar. */
    public static String url(Context ctx, String photo, long avatarVersion) {
        return com.callx.app.cache.AvatarBinderCore.url(ctx, photo, avatarVersion, TIER);
    }

    /**
     * Bind a VISIBLE row's avatar. Checks L2 memory first (instant paint,
     * survives MODERATE trim); otherwise a full RESOURCE-cached Glide
     * decode at the shared SMALL tier's exact pixel size (density-aware --
     * no more decoding whatever raw dimensions the CDN happens to hand
     * back), analytics-wrapped so this row's hits feed the same
     * L2/L3/Glide/CDN split every other screen records into. A successful
     * decode is written back into L2 (+ L3 disk) so the next bind of this
     * exact URL (re-scroll, tab rebuild, warm restart) is instant.
     */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        com.callx.app.cache.AvatarBinderCore.bind(ctx, iv, photo, avatarVersion,
                CACHE, new com.callx.app.cache.AvatarBinderCore.BindOptions(
                        TIER, DecodeFormat.PREFER_RGB_565, /*circleCrop=*/false, /*dontAnimate=*/false,
                        /*recordDashboardStats=*/false, placeholderRes));
    }

    /**
     * FIX (lifecycle-aware cancel): call from the adapter's
     * onViewRecycled(). Stops an in-flight request for a row that just
     * scrolled off screen instead of letting it keep competing for
     * bandwidth/decode time against whatever's now actually visible.
     */
    public static void cancel(Context ctx, ImageView iv) {
        com.callx.app.cache.AvatarBinderCore.cancel(ctx, iv);
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling past this
     * list -> skip prefetch entirely (would be wasted work, same reasoning
     * as AvatarPrefetcher for reels); slow/deliberate scroll -> warm several
     * rows ahead. Uses DiskCacheStrategy.DATA -- raw bytes cached, NOT the
     * full decoded bitmap -- for rows that might still get flung past
     * without ever actually binding; the full RESOURCE decode only happens
     * in bind() once a row genuinely becomes visible, so this never pays
     * CPU decode cost speculatively, only the (cheap, disk-cached) network
     * fetch.
     */
    public static void prefetch(Context context, AvatarSource source, int fromIndex, float velocityPxPerMs) {
        com.callx.app.cache.AvatarBinderCore.prefetch(context, source, fromIndex, velocityPxPerMs, TIER, CACHE);
    }
}
