package com.callx.app.cache;

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

import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

/**
 * PostFeedAvatarBinder — brings the SAME deep avatar pipeline the reel
 * player owner-avatar (AvatarPrefetcher/ReelUiController), the comment
 * sheet (ReelCommentAvatarBinder), the Home Stories tray
 * (HomeStoryAvatarBinder), and the Follow lists (FollowAvatarBinder)
 * already have to PostsFeedActivity's profile grid feed (item_post_feed_photo
 * rows: the 38dp owner avatar, plus the 32dp collab dual-avatar for collab
 * repost cards).
 *
 * GAP THIS CLOSES: PostsAdapter#onBindViewHolder previously did a flat
 * {@code Glide.load(r.ownerPhoto).apply(circleCrop-only RequestOptions)}
 * straight off the raw stored photoUrl — no shared {@link AvatarSizeTier}
 * bucketing (a different Cloudinary URL/cache-key for the SAME user than
 * every other avatar-bearing screen), no density-aware sizing, no WebP/AVIF
 * transform, no ?v=avatarVersion cache-bust, and no L2/L3 bitmap reuse — a
 * cold app restart re-downloaded and re-decoded the owner's avatar from
 * scratch even though the reel player/comments/Home tray already had it
 * warm in {@link ReelsAvatarL2Cache}. Same gap for the collab-post dual
 * avatar (initiator + collaborator).
 *
 * Reuses {@link ReelsAvatarL2Cache} (and its L3 disk tier) rather than
 * standing up a dedicated cache — this screen lives in feature-reels
 * alongside the reel player/comments/Stories tray/follow lists, so it
 * already gets that cache's per-module TRIM_MEMORY_MODERATE survival and
 * independent onTrimMemory registration for free.
 *
 * Unlike the Follow lists' CircleImageView rows, PostsFeedActivity's
 * iv_post_avatar (and the dynamically-added collab "av2") are a mix of
 * plain ImageView and CircleImageView — CircleImageView already clips at
 * draw time, but the main 38dp slot is a plain ImageView, so {@link #bind}
 * takes an explicit circleCrop flag instead of assuming one shape app-wide.
 *
 *  • url()      — AvatarUrlBuilder#buildResponsive: shared SMALL tier
 *                 bucket + density-bucketed dpr_ param + WebP/AVIF format
 *                 param + ?v=&lt;avatarVersion&gt; cache-bust, all server-side.
 *  • bind()     — L2 memory fast-path → real Glide decode (RESOURCE-cached,
 *                 density-aware override(), opaque-avatar RGB_565), decode
 *                 result written back into L2+L3 so the next bind of this
 *                 exact URL (re-scroll, warm restart) is instant. Circle
 *                 crop only applied when the target view needs it.
 *  • cancel()   — call from onViewRecycled(); stops an in-flight request for
 *                 a row that just scrolled off screen.
 *  • prefetch() — velocity-based depth (same thresholds/depths as every
 *                 other binder in the app): fast fling skips prefetch
 *                 entirely, slow/deliberate scroll warms several rows
 *                 ahead, using DiskCacheStrategy.DATA (bytes only, decode
 *                 deferred to a real bind) so a row flung past without
 *                 binding never pays a speculative CPU decode. Since this
 *                 screen is a single user's grid, most rows share the SAME
 *                 owner avatar URL (an instant L2/Glide-dedup hit) — the
 *                 prefetch mainly pays off for the varying collab-post
 *                 avatars scattered through the feed, which is why
 *                 AvatarSource resolves each index to whichever avatar that
 *                 row would ACTUALLY show (collab initiator photo for a
 *                 collab post, plain owner photo otherwise) rather than
 *                 always the plain owner photo.
 *
 * ETag/Last-Modified conditional requests are NOT re-implemented here —
 * same as every other binder, every Glide request app-wide already gets
 * that for free via CallxGlideModule routing through AvatarHttpCache's
 * shared OkHttpClient.
 */
public final class PostFeedAvatarBinder {

    private PostFeedAvatarBinder() {}

    /** item_post_feed_photo.xml's iv_post_avatar is a fixed 38dp circle; rounds up to shared SMALL(48) tier. Also used for the 32dp collab av2 — no reason to fetch a separate size for a slightly smaller slot. */
    public static final AvatarSizeTier TIER = AvatarSizeTier.forViewSizeDp(38);

    // Same thresholds/depths as AvatarPrefetcher/FollowAvatarBinder/
    // HomeStoryAvatarBinder — kept in sync deliberately so "fast fling" and
    // "slow scroll" mean the same thing across every avatar list in the app.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — flinging past rows
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — deliberate scroll
    private static final int DEPTH_DEFAULT = 1;
    private static final int DEPTH_SLOW    = 4;
    private static final int DEPTH_FAST    = 0;

    /** Read-only view over the feed's currently-bound reel list — resolves each index to whichever avatar that row would actually render (collab-aware). */
    public interface AvatarSource {
        String photo(int index);
        long avatarVersion(int index);
        int size();
    }

    public static String url(Context ctx, String photo, long avatarVersion) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, TIER, avatarVersion);
    }

    /**
     * Bind a VISIBLE row's avatar. Checks L2 memory first (instant paint,
     * survives MODERATE trim); otherwise a full RESOURCE-cached Glide
     * decode at the shared SMALL tier's exact pixel size, decode result
     * written back into L2 (+ L3 disk) so the next bind of this exact URL
     * is instant.
     *
     * @param circleCrop true for a plain ImageView target (the main
     *                   iv_post_avatar); false for a CircleImageView
     *                   target (the dynamically-added collab "av2"), which
     *                   already clips to a circle at draw time — applying
     *                   circleCrop() on top would allocate + draw a
     *                   redundant second bitmap for no visual difference.
     */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes, boolean circleCrop) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            return;
        }
        String url = url(ctx, photo, avatarVersion);
        Bitmap l2Hit = ReelsAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }
        int px = AvatarUrlBuilder.tierPx(ctx, TIER);
        RequestOptions opts = new RequestOptions()
            .override(px, px)
            .format(DecodeFormat.PREFER_RGB_565) // opaque avatar, no alpha needed
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE); // PERF: cache resized variant on disk — re-scroll/warm restart won't re-download
        if (circleCrop) opts = opts.circleCrop();

        Glide.with(ctx)
            .load(url)
            .apply(opts)
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
     * FIX (lifecycle-aware cancel): call from onViewRecycled(). Stops an
     * in-flight request for a row that just scrolled off screen instead of
     * letting it keep competing for bandwidth/decode time against whatever
     * is now actually visible.
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling down the
     * grid feed → skip prefetch entirely (wasted work — the user blows
     * past a row before its avatar even finishes decoding); slow/
     * deliberate scroll → warm several rows ahead. DiskCacheStrategy.DATA
     * (raw bytes, NOT the decoded bitmap) — the full RESOURCE decode only
     * happens in {@link #bind} once a row genuinely enters the viewport, so
     * this never pays speculative CPU decode cost, only the (cheap,
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
