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
 * HomeStoryAvatarBinder — brings the SAME deep avatar pipeline the reel
 * player owner-avatar (AvatarPrefetcher / ReelUiController), the comment
 * sheet (ReelCommentAvatarBinder), and the Status tray (StatusAvatarBinder)
 * already have to HomeFragment's Stories bar (the row of contact status
 * avatars at the top of the Home feed, item_home_story.xml rows inside
 * rv_stories/StoriesAdapter).
 *
 * GAP THIS CLOSES: HomeFragment#addStoryView (and #loadMyAvatar for the
 * leading "My Story" avatar) previously did a flat
 * {@code Glide.load(entry.photo).apply(circleCropTransform()).override(96, 96)}
 * straight off the raw stored photoUrl/thumbUrl — no shared
 * {@link AvatarSizeTier} bucketing (a different Cloudinary URL/cache-key per
 * screen for the SAME user), no density-aware sizing, no WebP/AVIF transform,
 * no L2/L3 bitmap reuse, no blur-up thumbnail, and no prefetch.
 *
 * UPDATE (RecyclerView conversion): the Stories bar used to be a plain
 * LinearLayout inside a HorizontalScrollView — every row was inflated and
 * attached to the window up front, so there was no natural "row not bound
 * yet" signal the way View#isAttachedToWindow() gives
 * StatusAvatarBinder/ChatAvatarBinder for a real RecyclerView. That gap is
 * why {@link #bindGated} / {@link #promote} existed: HomeFragment drove an
 * isVisible gate itself off scroll position, bound anything past the
 * initial viewport disk-cache-only, then promoted it for real once scrolled
 * into view. rv_stories is now a genuine RecyclerView (see
 * HomeFragment#StoriesAdapter), so that signal comes for free —
 * onBindViewHolder is only ever called for a row RecyclerView actually laid
 * out a View for. HomeFragment now calls {@link #bind} unconditionally from
 * onBindViewHolder; {@link #bindGated} and {@link #promote} are kept here
 * (unused by HomeFragment) in case another still-LinearLayout-based avatar
 * list in the app needs the same isVisible-gate shape later.
 *
 * Reuses {@link ReelsAvatarL2Cache} (and its L3 disk tier) rather than
 * standing up a dedicated cache for a fifth spot in the same module — the
 * Stories bar lives in feature-reels right alongside the reel player and
 * comment sheet, so it already gets that cache's per-module
 * TRIM_MEMORY_MODERATE survival and independent onTrimMemory registration
 * for free.
 *
 *  • url()        — AvatarUrlBuilder#buildResponsive: shared tier bucket +
 *                    density-bucketed dpr_ param + WebP/AVIF format param +
 *                    ?v=&lt;avatarVersion&gt; cache-bust, all server-side.
 *  • bind()        — L2 memory fast-path → L3 disk fast-path (raced against
 *                    a real Glide decode, stale hit dropped if the tag no
 *                    longer matches) → Glide decode chained with a
 *                    TINY-tier .thumbnail() blur-up, decode result written
 *                    back into L2+L3. Called from StoriesAdapter#onBindViewHolder
 *                    for every row RecyclerView lays out a View for.
 *  • bindGated()   — legacy isVisible-gate path (disk-cache-only,
 *                    DiskCacheStrategy.DATA + onlyRetrieveFromCache, never
 *                    touches the network) — see UPDATE note above.
 *  • promote()     — legacy isVisible-gate path, upgrades a still-pending
 *                    gated bind to a real HIGH-priority load — see UPDATE
 *                    note above.
 *  • cancel()      — Glide.clear() for a row RecyclerView is recycling
 *                    (StoriesAdapter#onViewRecycled), so a still-in-flight
 *                    request for a torn-down row never lands into whatever
 *                    the recycled View gets rebound to next.
 *  • prefetch()    — velocity-based depth (same thresholds/depths as every
 *                    other binder in the app), DiskCacheStrategy.DATA only
 *                    (bytes, decode deferred to a real bind), called from
 *                    HomeFragment's RecyclerView.OnScrollListener for
 *                    whatever sits just past findLastVisibleItemPosition().
 *
 * ETag/Last-Modified conditional requests are NOT re-implemented here — same
 * as every other binder, every Glide request app-wide already gets that for
 * free via CallxGlideModule routing through AvatarHttpCache's shared
 * OkHttpClient.
 */
public final class HomeStoryAvatarBinder {

    private HomeStoryAvatarBinder() {}

    /** item_home_story.xml's iv_story_avatar is a fixed 78dp circle; rounds up to shared LARGE(96) tier. */
    public static final AvatarSizeTier TIER = AvatarSizeTier.forViewSizeDp(78);
    /** Tiny blur-up tier chained via .thumbnail() — same tier every other binder's blur-up frame uses. */
    private static final AvatarSizeTier THUMBNAIL_TIER = AvatarSizeTier.TINY;

    // Same thresholds/depths as AvatarPrefetcher/ReelCommentAvatarBinder/
    // StatusAvatarBinder — kept in sync deliberately so "fast fling" and
    // "slow scroll" mean the same thing across every avatar list in the app.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — flinging past the tray
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — deliberate scroll
    private static final int DEPTH_DEFAULT = 1;
    private static final int DEPTH_SLOW    = 4;
    private static final int DEPTH_FAST    = 0;

    /** Read-only view over the tray's entries — same shape as every other binder's AvatarSource. */
    public interface AvatarSource {
        String photo(int index);
        long avatarVersion(int index);
        int size();
    }

    public static String url(Context ctx, String photo, long avatarVersion) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, TIER, avatarVersion);
    }

    private static String thumbUrl(Context ctx, String photo, long avatarVersion) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, THUMBNAIL_TIER, avatarVersion);
    }

    /** Unconditional real bind (IMMEDIATE priority) — for rows already inside the tray's initial visible window. See {@link #bindGated} for the offscreen-aware entry point. */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        bind(ctx, iv, photo, avatarVersion, placeholderRes, Priority.IMMEDIATE);
    }

    private static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes, Priority priority) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            iv.setTag(com.callx.app.reels.R.id.tag_avatar_url, null);
            return;
        }
        String url = url(ctx, photo, avatarVersion);

        // PERF: skip everything below if this exact URL is already what's
        // loaded/loading into this row — a rebuild of the tray after a
        // pull-to-refresh often re-resolves the same contacts to the same
        // photos.
        if (url.equals(iv.getTag(com.callx.app.reels.R.id.tag_avatar_url))) return;
        iv.setTag(com.callx.app.reels.R.id.tag_avatar_url, url);

        Bitmap l2Hit = ReelsAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }

        // FIX (L3 disk tier): raced in parallel with the Glide request below
        // — a stale hit is dropped if the tag no longer matches (row
        // rebuilt/rebound/cancelled since).
        ReelsAvatarL2Cache.l3(ctx).getAsync(url, l3Bmp -> {
            if (l3Bmp == null) return;
            if (!url.equals(iv.getTag(com.callx.app.reels.R.id.tag_avatar_url))) return;
            iv.setImageBitmap(l3Bmp);
            ReelsAvatarL2Cache.get(ctx).put(url, l3Bmp); // warm L2 too
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L3_DISK);
        });

        int mainPx = AvatarUrlBuilder.tierPx(ctx, TIER);
        int thumbPx = AvatarUrlBuilder.tierPx(ctx, THUMBNAIL_TIER);
        String thumb = thumbUrl(ctx, photo, avatarVersion);

        // No circleCrop() — iv_story_avatar is a de.hdodenhof CircleImageView,
        // which clips itself via its own BitmapShader (same reasoning as
        // ReelUiController's owner avatar / ReelCommentAvatarBinder).
        RequestOptions opts = new RequestOptions()
            .override(mainPx, mainPx)
            .format(DecodeFormat.PREFER_RGB_565) // opaque avatar, no alpha needed
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // PERF: cache resized variant on disk — re-scroll/rebuild won't re-download
            .priority(priority)
            .placeholder(placeholderRes)
            .error(placeholderRes);

        Glide.with(ctx)
            .load(url)
            .apply(opts)
            // FIX (thumbnail blur-up): TINY tier chained ahead of the real
            // decode — exactly what prefetch() warms, so a prefetched row
            // shows an instant blur-up frame instead of a bare placeholder.
            .thumbnail(Glide.with(ctx)
                    .load(thumb)
                    .apply(new RequestOptions()
                            .override(thumbPx, thumbPx)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .priority(priority)))
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
                    // URL (re-scroll, tray rebuild, warm restart) is instant.
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
     * FIX (isVisible gate): call for a row that's inflated (attached to the
     * window — the whole tray is) but sits OUTSIDE the initial visible
     * scroll viewport HomeFragment computed for this build of the tray.
     * Never opens a network connection; a cache miss just falls through to
     * the placeholder, same as a hard skip would, but a hit (e.g. warmed by
     * {@link #prefetch}) paints immediately instead of waiting on
     * {@link #promote} to fire the real request from scratch.
     */
    public static void bindGated(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            iv.setTag(com.callx.app.reels.R.id.tag_avatar_url, null);
            return;
        }
        String url = url(ctx, photo, avatarVersion);
        iv.setTag(com.callx.app.reels.R.id.tag_avatar_url, url);
        int mainPx = AvatarUrlBuilder.tierPx(ctx, TIER);
        Glide.with(ctx)
            .load(url)
            .apply(new RequestOptions()
                .override(mainPx, mainPx)
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.DATA) // disk-only tier — no client-side resize to cache, so DATA (raw bytes) is what a cache hit here needs
                .onlyRetrieveFromCache(true)                // never touches the network — a miss just falls through to the placeholder
                .priority(Priority.LOW)                     // PERF: this row is still outside the viewport — never contend with a visible row's IMMEDIATE request
                .placeholder(placeholderRes)
                .error(placeholderRes))
            .into(iv);
    }

    /**
     * Call from HomeFragment's scroll_stories OnScrollChangeListener once a
     * gated row's bounds enter the visible viewport: upgrades it to the
     * real HIGH-priority network-capable load. Cheap no-op if this exact
     * URL already resolved for real (a successful {@link #bind} re-tags the
     * view via the identical URL, so the tag comparison naturally matches
     * and this just re-issues the same request at a higher priority — Glide
     * de-dupes in-flight identical requests, so that's harmless).
     */
    public static void promote(Context ctx, ImageView iv, String photo, long avatarVersion, int placeholderRes) {
        String expected = url(ctx, photo, avatarVersion);
        if (expected == null || !expected.equals(iv.getTag(com.callx.app.reels.R.id.tag_avatar_url))) return;
        bind(ctx, iv, photo, avatarVersion, placeholderRes, Priority.HIGH);
    }

    /**
     * FIX (Lifecycle-aware cancel — the Java equivalent of cancelling a
     * coroutine Job on scope-exit): call from
     * HomeFragment#clearStoriesKeepAddButton right before a row is removed
     * for the tray rebuild. Stops an in-flight request for a row about to
     * be torn down instead of letting it keep competing for bandwidth/decode
     * time, and clears the URL tag so a still-pending L3 async callback for
     * the OLD bind can never paint into whatever gets inflated at that same
     * child index next.
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
        iv.setTag(com.callx.app.reels.R.id.tag_avatar_url, null);
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling across the
     * tray → skip prefetch entirely (wasted work — the user blows past a
     * story before its avatar even finishes decoding); slow/deliberate
     * scroll → warm several rows ahead. DiskCacheStrategy.DATA (raw bytes,
     * NOT the decoded bitmap) for both the main tier and the thumbnail tier
     * — the full RESOURCE decode only happens in {@link #bind}/{@link
     * #promote} once a row genuinely enters the viewport, so this never
     * pays speculative CPU decode cost, only the (cheap, disk-cached)
     * network fetch.
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
            long version = source.avatarVersion(i);
            String mainUrl = url(appCtx, photo, version);
            String thumb = thumbUrl(appCtx, photo, version);
            Glide.with(appCtx)
                .load(mainUrl)
                .thumbnail(Glide.with(appCtx)
                        .load(thumb)
                        .diskCacheStrategy(DiskCacheStrategy.DATA)
                        .priority(Priority.LOW))
                .diskCacheStrategy(DiskCacheStrategy.DATA) // bytes only — decode deferred to a real bind()/promote()
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
