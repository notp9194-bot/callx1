package com.callx.app.followers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import com.callx.app.cache.AvatarCacheAnalytics;
import com.callx.app.cache.ReelsAvatarL2Cache;
import com.callx.app.reels.R;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

/**
 * FollowAvatarBinder — brings reels' full avatar pipeline to the unified
 * Followers/Following/Mutual/Suggested screen (FollowConnectionsActivity,
 * which replaced the old FollowersListActivity/FollowingListActivity/
 * MutualFollowersActivity trio). Those old activities' UserListAdapter DID
 * wire this class up correctly; the consolidation into
 * FollowConnectionsActivity regressed it back to a flat, untiered
 * {@code Glide.load(photo)} with no L2/L3 reuse, no velocity-aware prefetch,
 * and nothing cancelling a request once its row scrolled off screen — this
 * class (and FollowConnectionsActivity's wiring of it) restores parity.
 *
 * Deliberately reuses {@link ReelsAvatarL2Cache} / its L3 disk tier instead
 * of standing up a third parallel cache — these lists live in the SAME
 * feature-reels module, so they already get that cache's per-module
 * TRIM_MEMORY_MODERATE survival (see ReelsAvatarL2Cache's own doc for why
 * that independence from chat's cache matters) for free.
 *
 * ETag/Last-Modified conditional requests (a stale-but-unchanged CDN URL
 * getting a 304 instead of a full re-download) are NOT re-implemented here —
 * that already happens for every Glide request app-wide, this screen
 * included, via CallxGlideModule routing through AvatarHttpCache's
 * OkHttpClient. Nothing screen-specific needed for that part.
 *
 * FIX (L2/L3 write-through): bind() previously only READ ReelsAvatarL2Cache
 * on a hit; a real Glide decode never wrote its result back, so every
 * cold row paid the full network/decode cost again on re-scroll, tray
 * rebuild, or warm restart. Now matches ChatAvatarBinder/StatusAvatarBinder:
 * a successful decode is written into both the L2 memory tier and the L3
 * disk tier so the next bind of this exact URL is instant.
 */
public final class FollowAvatarBinder {

    private FollowAvatarBinder() {}

    /** Follow-list row avatar (~48dp) — same coarse tier bucket the reel owner-avatar strip uses one size down from. */
    private static final AvatarSizeTier TIER = AvatarSizeTier.SMALL;

    // Same thresholds/depths as AvatarPrefetcher (see that class) — kept in
    // sync deliberately so "fast fling" and "slow scroll" mean the same
    // thing across every avatar list in the app.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — flinging past rows
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — deliberate scroll
    private static final int DEPTH_DEFAULT = 1;
    private static final int DEPTH_SLOW    = 4;
    private static final int DEPTH_FAST    = 0;

    /** Read-only view over whatever list a screen is scrolling, so prefetch() doesn't need to know about UserItem/ReelModel/etc. */
    public interface AvatarSource {
        String photo(int index);
        long avatarVersion(int index);
        int size();
    }

    /** Server-side responsive, version-tagged URL for one row — same buildResponsive() upgrade as the reel owner avatar. */
    public static String url(Context ctx, String photo, long avatarVersion) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, TIER, avatarVersion);
    }

    /**
     * Bind a VISIBLE row's avatar. Checks L2 memory first (instant paint,
     * survives MODERATE trim); otherwise a full RESOURCE-cached Glide
     * decode at the shared SMALL tier's exact pixel size (density-aware —
     * no more decoding whatever raw dimensions the CDN happens to hand
     * back), analytics-wrapped so this row's hits feed the same
     * L2/L3/Glide/CDN split every other screen records into. A successful
     * decode is written back into L2 (+ L3 disk) so the next bind of this
     * exact URL (re-scroll, tab rebuild, warm restart) is instant.
     *
     * NOTE: no circleCropTransform() — the row's ImageView is a real
     * CircleImageView, which already clips to a circle at draw time;
     * applying circleCrop() on top would allocate + draw a second,
     * redundant bitmap on every decode for no visual difference (same
     * reasoning FollowConnectionsActivity's UserListAdapter already
     * documented for its own now-replaced inline bind).
     */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            iv.setTag(R.id.tag_avatar_url, null);
            return;
        }
        String url = url(ctx, photo, avatarVersion);

        // PERF: skip everything below if this exact URL is already what's
        // loaded/loading into this row — a recycled row rebinding to the
        // same user (e.g. a follow-state-only refresh notifyItemChanged)
        // shouldn't re-issue an identical Glide request.
        if (url.equals(iv.getTag(R.id.tag_avatar_url))) return;
        iv.setTag(R.id.tag_avatar_url, url);

        Bitmap l2Hit = ReelsAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }
        int px = AvatarUrlBuilder.tierPx(ctx, TIER);
        Glide.with(ctx)
            .load(url)
            .apply(new RequestOptions()
                    .override(px, px)
                    .format(DecodeFormat.PREFER_RGB_565) // opaque avatar, no alpha needed
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)) // PERF: cache resized variant on disk — re-scroll/rebuild won't re-download
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                    return false; // let Glide still apply the error placeholder
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                    AvatarCacheAnalytics.getInstance(ctx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    if (resource instanceof BitmapDrawable) {
                        Bitmap bmp = ((BitmapDrawable) resource).getBitmap();
                        ReelsAvatarL2Cache.get(ctx).put(url, bmp);
                        ReelsAvatarL2Cache.l3(ctx).put(url, bmp);
                    }
                    return false; // let Glide still deliver the drawable into the ImageView
                }
            })
            .into(iv);
    }

    /**
     * FIX (lifecycle-aware cancel): call from the adapter's
     * onViewRecycled(). Stops an in-flight request for a row that just
     * scrolled off screen instead of letting it keep competing for
     * bandwidth/decode time against whatever's now actually visible.
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
        iv.setTag(R.id.tag_avatar_url, null);
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling past this
     * list → skip prefetch entirely (would be wasted work, same reasoning
     * as AvatarPrefetcher for reels); slow/deliberate scroll → warm several
     * rows ahead. Uses {@link DiskCacheStrategy#DATA} — raw bytes cached,
     * NOT the full decoded bitmap — for rows that might still get flung
     * past without ever actually binding; the full RESOURCE decode only
     * happens in {@link #bind} once a row genuinely becomes visible, so
     * this never pays CPU decode cost speculatively, only the (cheap,
     * disk-cached) network fetch.
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
            String url = url(appCtx, photo, source.avatarVersion(i));
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
