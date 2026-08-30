package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
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

import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

/**
 * ReelCommentAvatarBinder — brings the SAME deep avatar pipeline the reel
 * player owner-avatar (AvatarPrefetcher / ReelUiController#loadOwnerAvatarNow)
 * and the Follow lists (FollowAvatarBinder) already have to
 * ReelCommentsAdapter (the comment sheet's RecyclerView rows).
 *
 * GAP THIS CLOSES: ReelCommentsAdapter's bindAvatar()/loadAvatarInto() already
 * had the tier-aware, density-bucketed, WebP/AVIF, versioned URL (via
 * AvatarUrlBuilder#buildResponsive — that part was already done) and its own
 * uid→url LruCache (avatarCache) to avoid repeated Firebase reads for the
 * "ownerPhoto missing, fall back to reels/users/{uid}" case. What it never
 * had was anything below the URL: no L2/L3 BITMAP reuse (every scroll-back
 * to a comment re-decoded from Glide's own cache at best), no thumbnail
 * blur-up (bare ic_person placeholder until the full decode landed, instead
 * of an instant tiny blurred frame), no prefetch (every row's avatar loaded
 * strictly on bind, nothing warmed ahead of a scroll), and no isVisible gate
 * (a request for a row that scrolled off — or got recycled to a different
 * comment by a live Firebase update — kept running with nothing to cancel it).
 *
 * Deliberately reuses {@link ReelsAvatarL2Cache} / its L3 disk tier instead
 * of standing up a fourth parallel cache — comments live in the SAME
 * feature-reels module as the reel player and follow lists, so they already
 * get that cache's per-module TRIM_MEMORY_MODERATE survival for free (see
 * ReelsAvatarL2Cache's own doc for why that per-module independence from
 * chat's/status's/profile's/search's caches matters).
 *
 *  • url()      — AvatarUrlBuilder#buildResponsive: shared AvatarSizeTier
 *                 bucket + density-bucketed dpr_ param + WebP/AVIF format
 *                 param + ?v=&lt;avatarVersion&gt; cache-bust, all server-side.
 *  • bind()     — L2 memory fast-path (ReelsAvatarL2Cache, survives
 *                 TRIM_MEMORY_MODERATE) → L3 disk fast-path (survives process
 *                 death, raced in parallel against the Glide request, same
 *                 "stale hit dropped if the real request already won"
 *                 tag-guard ReelUiController#loadOwnerAvatarNow uses) → a
 *                 real Glide decode chained with a TINY-tier .thumbnail()
 *                 blur-up (same tier AvatarPrefetcher warms for the reel
 *                 player), decode result written back into L2+L3 so the next
 *                 bind of this exact URL (re-scroll, re-open the sheet) is
 *                 instant.
 *  • cancel()   — FIX (isVisible gate): call from
 *                 ReelCommentsAdapter#onViewRecycled. Stops an in-flight
 *                 request for a row that just scrolled off screen, and clears
 *                 the URL tag bind() uses to guard its L3 race — so a slow
 *                 disk read that resolves AFTER the row was recycled can
 *                 never paint into a VH now showing a different comment. This
 *                 is the per-row equivalent of ReelUiController#onBecameInvisible
 *                 canceling loadOwnerAvatarNow's in-flight request for a
 *                 whole reel going offscreen.
 *  • prefetch() — velocity-based depth (same thresholds as AvatarPrefetcher/
 *                 FollowAvatarBinder/ChatAvatarBinder — fast fling skips
 *                 entirely, slow scroll warms several rows ahead), using
 *                 DiskCacheStrategy.DATA (raw bytes only, decode deferred to
 *                 a real bind()) so a comment row flung past without ever
 *                 actually binding never pays a speculative CPU decode.
 *
 * ETag/Last-Modified conditional requests are NOT re-implemented here — same
 * as every other binder, every Glide request app-wide (this screen included)
 * already gets that for free via CallxGlideModule routing through
 * AvatarHttpCache's shared OkHttpClient.
 */
public final class ReelCommentAvatarBinder {

    private ReelCommentAvatarBinder() {}

    /** Comment row avatar — item_reel_comment.xml iv_avatar is a fixed 36dp circle; SMALL(48) is the shared tier ReelCommentsAdapter already bucketed to. */
    public static final AvatarSizeTier TIER = AvatarSizeTier.SMALL;
    /** Tiny blur-up tier chained via .thumbnail() — same tier every other binder's blur-up frame uses. */
    private static final AvatarSizeTier THUMBNAIL_TIER = AvatarSizeTier.TINY;

    // Same thresholds/depths as AvatarPrefetcher/FollowAvatarBinder/ChatAvatarBinder —
    // kept in sync deliberately so "fast fling" and "slow scroll" mean the
    // same thing across every avatar list in the app.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — flinging past rows
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — deliberate scroll
    private static final int DEPTH_DEFAULT = 1;
    private static final int DEPTH_SLOW    = 4;
    private static final int DEPTH_FAST    = 0;

    /** Read-only view over whatever comment list is scrolling — same shape as ChatAvatarBinder.AvatarSource/FollowAvatarBinder.AvatarSource. */
    public interface AvatarSource {
        String photo(int index);
        long avatarVersion(int index);
        int size();
    }

    /** Server-side responsive, version-tagged URL for one row — the resolved (ownerPhoto-or-fallback) uid photo as input. */
    public static String url(Context ctx, String photo, long avatarVersion) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, TIER, avatarVersion);
    }

    /**
     * Bind a VISIBLE row's avatar. Checks L2 memory first (instant paint,
     * survives MODERATE trim); races an L3 disk read against a real Glide
     * decode chained with a TINY blur-up thumbnail; a successful decode is
     * written back into L2 (+ L3 disk) so the very next bind of this exact
     * URL (re-scroll, re-open the sheet, warm restart) is instant.
     *
     * iv is tagged with the resolved URL so {@link #cancel} can tell a
     * still-in-flight request for THIS bind apart from one that already
     * resolved or belongs to a since-recycled row's earlier comment.
     */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            iv.setTag(com.callx.app.reels.R.id.tag_avatar_url, null);
            return;
        }
        String url = url(ctx, photo, avatarVersion);

        // PERF: skip everything below — even the L2 lookup — if this exact
        // URL is already what's loaded/loading into this row. Preserves the
        // original loadAvatarInto()'s "redundant rebind" short-circuit
        // (e.g. a like/reaction-only refresh that still routes through a
        // full bind). A row leaving the pool always clears this tag first
        // (see cancel()), so a freshly recycled row never false-positives here.
        if (url.equals(iv.getTag(com.callx.app.reels.R.id.tag_avatar_url))) return;
        iv.setTag(com.callx.app.reels.R.id.tag_avatar_url, url);

        Bitmap l2Hit = ReelsAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }

        // FIX (L3 disk tier): fired in parallel with the Glide request below
        // — if the async disk read lands first AND this row still wants this
        // exact URL (tag unchanged — i.e. not recycled to a different
        // comment, not cancel()'d), paint it immediately; otherwise the
        // stale hit is dropped so it can never flicker over a newer image.
        ReelsAvatarL2Cache.l3(ctx).getAsync(url, l3Bmp -> {
            if (l3Bmp == null) return;
            if (!url.equals(iv.getTag(com.callx.app.reels.R.id.tag_avatar_url))) return;
            iv.setImageBitmap(l3Bmp);
            ReelsAvatarL2Cache.get(ctx).put(url, l3Bmp); // warm L2 too
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L3_DISK);
        });

        int mainPx = AvatarUrlBuilder.tierPx(ctx, TIER);
        int thumbPx = AvatarUrlBuilder.tierPx(ctx, THUMBNAIL_TIER);
        String thumbUrl = AvatarUrlBuilder.buildResponsive(ctx, photo, THUMBNAIL_TIER, avatarVersion);

        RequestOptions opts = new RequestOptions()
            .override(mainPx, mainPx)
            .format(DecodeFormat.PREFER_RGB_565) // opaque avatar clipped via view outline, not circleCrop — see ReelCommentsAdapter's original doc on why circleCrop is skipped here
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // PERF: cache resized variant on disk — re-scroll won't re-download
            .placeholder(placeholderRes)
            .error(placeholderRes);

        Glide.with(ctx)
            .load(url)
            .apply(opts)
            // FIX (thumbnail blur-up): TINY tier chained ahead of the real
            // decode — shows an instant blur-up frame instead of a bare
            // ic_person placeholder while the full SMALL-tier decode finishes.
            .thumbnail(Glide.with(ctx)
                    .load(thumbUrl)
                    .apply(new RequestOptions()
                            .override(thumbPx, thumbPx)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)))
            .listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                    return false; // let Glide still apply the error placeholder
                }
                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                    AvatarCacheAnalytics.getInstance(ctx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    // FIX (L2/L3 write-through): a future bind of this exact
                    // URL (re-scroll, re-open sheet, warm restart) skips
                    // Glide entirely. No circleCrop() is applied (the row
                    // clips via an outline, see opts above), so this is
                    // always a plain BitmapDrawable.
                    if (resource instanceof android.graphics.drawable.BitmapDrawable) {
                        Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) resource).getBitmap();
                        ReelsAvatarL2Cache.get(ctx).put(url, bmp);
                        ReelsAvatarL2Cache.l3(ctx).put(url, bmp);
                    }
                    return false; // let Glide still deliver the drawable into the ImageView
                }
            })
            .into(iv);
    }

    /**
     * FIX (isVisible gate): call from ReelCommentsAdapter#onViewRecycled.
     * Stops an in-flight request for a row that just scrolled off screen (or
     * got recycled to a different comment by a live Firebase update) instead
     * of letting it keep competing for bandwidth/decode time against
     * whatever's now actually visible — and clears the URL tag so a still-
     * pending L3 async callback for the OLD bind can never paint into this
     * row after it's rebound to something else.
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
        iv.setTag(com.callx.app.reels.R.id.tag_avatar_url, null);
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling past the
     * comment sheet → skip prefetch entirely (would be wasted work — the
     * user blows past a comment before its avatar even finishes decoding);
     * slow/deliberate scroll → warm several rows ahead. Uses
     * {@link DiskCacheStrategy#DATA} — raw bytes only, NOT the full decoded
     * bitmap — for rows that might still get flung past without ever
     * actually binding; the full RESOURCE decode only happens in
     * {@link #bind} once a row genuinely becomes visible, so this never pays
     * CPU decode cost speculatively, only the (cheap, disk-cached) network
     * fetch.
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
