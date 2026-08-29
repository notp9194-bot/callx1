package com.callx.app.cache;

import android.content.Context;

import com.bumptech.glide.Glide;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

import java.util.List;

/**
 * AvatarPrefetcher — Instagram-style owner-avatar pre-warming.
 *
 * ReelVideoPreloader already byte-preloads the next N reels' video (and
 * ReelThumbnailPreloader the cover frame), but nothing warmed the owner
 * avatar shown in the reel UI (ivOwnerAvatar) — it only started loading
 * once ReelUiController bound the now-visible reel, costing a fresh
 * network round-trip + decode right on the frame the user lands on it.
 *
 * This mirrors ReelVideoPreloader's job for avatars specifically: called
 * from the same "look ahead" pass, it asks Glide to fetch + decode the
 * upcoming reel's owner avatar into cache with .preload() (no ImageView
 * target, memory+disk cache only) at the SAME AvatarSizeTier that
 * ReelUiController's owner-avatar bind will request — so the URL matches
 * exactly and the visible bind is a cache hit instead of a cache miss.
 *
 * FIX (velocity-based depth): a flat "always prefetch exactly 1 ahead"
 * either wastes work during a fast fling (user blows past the reel before
 * the avatar bitmap even finishes decoding — pure wasted network+CPU) or
 * under-prefetches during a slow deliberate scroll (where we could easily
 * warm 3-4 reels ahead for free). Depth now scales with the SAME
 * scrollVelocityPxPerMs signal ReelsFragment already tracks for the video
 * predictive preloader (see ReelsFragment#lastScrollVelocity), so this
 * reuses an existing measurement instead of adding a second one.
 *
 * FIX (thumbnail blur-up): prefetch now warms BOTH a tiny low-res
 * thumbnail tier and the real bind tier, and chains them with
 * RequestBuilder#thumbnail() exactly like ReelUiController#loadOwnerAvatarNow
 * does at bind time — so once the user lands on the reel, the tiny blur-up
 * frame is already a cache hit (shows instantly) while the full-res tier
 * resolves, instead of a blank placeholder until the full decode lands.
 */
public final class AvatarPrefetcher {

    private AvatarPrefetcher() {}

    /** Tier the reel player's owner avatar (36dp view, item_reel_player) binds at. */
    private static final AvatarSizeTier OWNER_AVATAR_TIER = AvatarSizeTier.SMALL;

    /** Tiny blur-up tier chained via .thumbnail() — must match ReelUiController's thumbnail tier. */
    public static final AvatarSizeTier THUMBNAIL_TIER = AvatarSizeTier.TINY;

    // Scroll velocity is px/ms (same unit ReelsFragment already computes in
    // onPageScrolled). Thresholds picked empirically around typical ViewPager2
    // fling vs. deliberate-drag speeds on a ~2400px-tall reel page.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — user is flying past reels
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — deliberate, unhurried scroll

    private static final int DEPTH_DEFAULT = 1; // unknown velocity (e.g. very first page)
    private static final int DEPTH_SLOW    = 4; // slow scroll — go deep, it's essentially free
    private static final int DEPTH_FAST    = 0; // fast fling — skip entirely, would be wasted work

    /**
     * Preload upcoming reels' owner avatars, depth chosen by current scroll velocity.
     *
     * @param reels                  full reel list (adapter's list)
     * @param fromIndex              first index to prefetch from (inclusive) — normally position+1
     * @param scrollVelocityPxPerMs  ReelsFragment#lastScrollVelocity; pass <= 0 for "unknown"
     */
    public static void prefetch(Context context, List<ReelModel> reels, int fromIndex, float scrollVelocityPxPerMs) {
        if (context == null || reels == null || reels.isEmpty()) return;
        int depth = depthForVelocity(scrollVelocityPxPerMs);
        if (depth == 0) return;

        Context appCtx = context.getApplicationContext();
        for (int i = fromIndex; i < fromIndex + depth && i < reels.size(); i++) {
            prefetchOne(appCtx, reels.get(i));
        }
    }

    /** @deprecated use {@link #prefetch(Context, List, int, float)} so depth is velocity-aware. */
    @Deprecated
    public static void prefetch(Context context, ReelModel reel) {
        if (context == null || reel == null) return;
        prefetchOne(context.getApplicationContext(), reel);
    }

    private static int depthForVelocity(float v) {
        if (v <= 0f) return DEPTH_DEFAULT;
        if (v >= FAST_FLING_THRESHOLD) return DEPTH_FAST;
        if (v <= SLOW_SCROLL_THRESHOLD) return DEPTH_SLOW;
        return DEPTH_DEFAULT;
    }

    private static void prefetchOne(Context appCtx, ReelModel reel) {
        if (reel == null) return;
        String ownerPhoto = reel.ownerPhoto;
        if (ownerPhoto == null || ownerPhoto.isEmpty()) return;

        int mainPx = AvatarUrlBuilder.tierPx(appCtx, OWNER_AVATAR_TIER);
        String mainUrl = AvatarUrlBuilder.build(appCtx, ownerPhoto, OWNER_AVATAR_TIER);

        int thumbPx = AvatarUrlBuilder.tierPx(appCtx, THUMBNAIL_TIER);
        String thumbUrl = AvatarUrlBuilder.build(appCtx, ownerPhoto, THUMBNAIL_TIER);

        // Warm the real tier with the thumbnail chained in — matches the
        // exact request shape ReelUiController#loadOwnerAvatarNow issues at
        // bind time, so both the blur-up frame and the final image are
        // cache hits once the user actually lands on this reel.
        Glide.with(appCtx)
            .load(mainUrl)
            .thumbnail(Glide.with(appCtx).load(thumbUrl).override(thumbPx, thumbPx))
            .override(mainPx, mainPx)
            .preload();
    }
}
