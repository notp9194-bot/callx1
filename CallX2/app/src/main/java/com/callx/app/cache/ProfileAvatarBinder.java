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
 * ProfileAvatarBinder — brings the SAME deep avatar pipeline Reels
 * (AvatarPrefetcher / ReelUiController#loadOwnerAvatarNow), Chat
 * (ChatAvatarBinder) and Status (StatusAvatarBinder) already have to
 * ProfileActivity's own-profile hero avatar and UserProfileActivity's
 * partner hero avatar + 3 Reel/X/YouTube "peek" avatars.
 *
 * ADAPTED SHAPE, not a copy-paste: Reels/Chat/Status all bind a scrolled
 * RecyclerView of MANY rows, so their binders center on per-row concepts —
 * an isVisible gate keyed to "is this specific row attached to the window
 * right now", and a velocity-based prefetch depth keyed to "how many MORE
 * rows is the user about to scroll past". Neither ProfileActivity nor
 * UserProfileActivity has a RecyclerView at all — each shows a small FIXED
 * number of avatars (one hero avatar; UserProfileActivity's peek trio) that
 * are all already known and requested up front, never revealed by scroll.
 * Porting the row-visibility/scroll-velocity machinery here verbatim would
 * be solving a problem these screens don't have. What DOES carry over,
 * translated to a single-screen shape:
 *
 *  • url()/thumbUrl()   — same AvatarUrlBuilder#buildResponsive: shared
 *                         tier bucket + density-bucketed dpr_ param +
 *                         WebP/AVIF format param + ?v=&lt;avatarVersion&gt;
 *                         cache-bust. GAP CLOSED: UserProfileActivity
 *                         previously bypassed this helper entirely (flat
 *                         ".override(720,720)"/".override(240,240)"), so
 *                         its avatars shared no cache key with any other
 *                         screen showing the same user.
 *  • bind()             — L2 memory fast-path (ProfileAvatarL2Cache,
 *                         survives TRIM_MEMORY_MODERATE) → L3 disk
 *                         fast-path (survives process death) → a real
 *                         Glide decode chained with a TINY-tier
 *                         .thumbnail() blur-up, decode result written back
 *                         into L2+L3 so the next bind of this exact URL
 *                         (re-open the screen, warm restart) is instant.
 *  • bindGated()        — FIX (isVisible gate, screen-scoped): a
 *                         RecyclerView row's gate question is "is THIS ROW
 *                         on screen"; a hero avatar's is "is THIS SCREEN on
 *                         screen". UserProfileActivity's
 *                         partnerAvatarVersionListener can fire a fresh
 *                         avatarVersion push at any time the watch() is
 *                         alive — including while the Activity is paused in
 *                         the back stack (e.g. the viewer opened a chat
 *                         media viewer on top). Call bindGated() from that
 *                         listener with the Activity's current resumed
 *                         state: while NOT resumed, issues a disk-cache-only
 *                         (DiskCacheStrategy.DATA + onlyRetrieveFromCache)
 *                         load instead of a real network-capable one — never
 *                         opens a network connection for a screen the user
 *                         isn't looking at right now, but still paints
 *                         instantly if the bytes are already on disk.
 *  • promote()          — call from onResume(): upgrades a still-pending
 *                         gated bind to the real HIGH-priority
 *                         network-capable load now that the screen is
 *                         confirmed back on screen. No-op if bind() already
 *                         resolved it (tag no longer matches).
 *  • cancel()           — call from onDestroy(): Glide.clear() cancels an
 *                         in-flight request for a hero avatar whose screen
 *                         is going away for good — the single-screen
 *                         equivalent of ReelUiController#onBecameInvisible /
 *                         StatusAvatarBinder#cancel's onViewRecycled. (Note:
 *                         Glide.with(activity) already pauses/resumes
 *                         requests automatically across onStop/onStart, same
 *                         as every other screen in the app — this only
 *                         covers the final teardown Glide's own lifecycle
 *                         hook doesn't, releasing the tag so a stale L3
 *                         callback can never paint into a dead screen.)
 *
 * The peek trio (UserProfileActivity's Reel/X/YouTube avatars) has no
 * "prefetch ahead" concept to adapt — all 3 are already fetched and bound
 * up front the moment the screen opens (loadAvatarAndStartAnimation()), not
 * revealed one-by-one by scroll — so the AvatarPrefetcher/StatusAvatarBinder
 * .prefetch() velocity heuristic simply doesn't apply here. What those 3
 * binds DO gain from going through bind() instead of raw Glide, same as the
 * hero avatar: shared-tier URLs, L2/L3 reuse, and the thumbnail blur-up
 * chain below (previously a bare placeholder until the full 46dp decode
 * landed).
 *
 * ETag/Last-Modified conditional requests are NOT re-implemented here — same
 * as ChatAvatarBinder/StatusAvatarBinder, every Glide request app-wide
 * already gets that for free via CallxGlideModule routing through
 * AvatarHttpCache's shared OkHttpClient, and every URL here already carries
 * AvatarUrlBuilder's &v=&lt;avatarVersion&gt; on top.
 */
public final class ProfileAvatarBinder {

    private ProfileAvatarBinder() {}

    /** ProfileActivity's own-profile avatar — 120dp FrameLayout, iv_avatar. */
    public static final AvatarSizeTier HERO_TIER = AvatarSizeTier.forViewSizeDp(120);
    /** UserProfileActivity's partner avatar — 90dp iv_avatar_large. */
    public static final AvatarSizeTier LARGE_TIER = AvatarSizeTier.forViewSizeDp(90);
    /** UserProfileActivity's Reel/X/YouTube peek circles — 46dp each. */
    public static final AvatarSizeTier PEEK_TIER = AvatarSizeTier.forViewSizeDp(46);
    /** Tiny blur-up tier chained via .thumbnail() for every tier above. */
    public static final AvatarSizeTier THUMBNAIL_TIER = AvatarSizeTier.TINY;

    public static String url(Context ctx, String photo, long avatarVersion, AvatarSizeTier tier) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, tier, avatarVersion);
    }

    private static String thumbUrl(Context ctx, String photo, long avatarVersion) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, THUMBNAIL_TIER, avatarVersion);
    }

    /** Unconditional real bind — used once the screen showing iv is known to be visible. See {@link #bindGated} for the screen-visibility-aware entry point. */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, AvatarSizeTier tier, int placeholderRes) {
        bind(ctx, iv, photo, avatarVersion, tier, placeholderRes, Priority.IMMEDIATE);
    }

    private static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, AvatarSizeTier tier, int placeholderRes, Priority priority) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            iv.setTag(null);
            return;
        }
        String url = url(ctx, photo, avatarVersion, tier);
        iv.setTag(url); // FIX (isVisible gate): tags the exact bind this view currently wants, so promote()/a later bind() can tell a stale in-flight request apart from the current one

        // FIX (onTrimMemory / L2 cache): survives TRIM_MEMORY_MODERATE — see
        // AvatarL2MemoryCache/ProfileAvatarL2Cache. Checked before touching
        // Glide at all, same as ReelUiController#loadOwnerAvatarNow.
        Bitmap l2Hit = ProfileAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }

        // FIX (L3 disk tier): covers process death, which L2 (in-process
        // memory) can't. Fired in parallel with the Glide request below —
        // if the async disk read lands first AND this exact bind is still
        // current (tag still matches), paint it immediately; otherwise the
        // stale hit is dropped so it can never flicker over a newer image.
        ProfileAvatarL2Cache.l3(ctx).getAsync(url, l3Bmp -> {
            if (l3Bmp == null) return;
            if (!url.equals(iv.getTag())) return; // rebound to a different photo/screen since
            iv.setImageBitmap(l3Bmp);
            ProfileAvatarL2Cache.get(ctx).put(url, l3Bmp); // warm L2 too
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L3_DISK);
        });

        String thumb = thumbUrl(ctx, photo, avatarVersion);
        RequestOptions opts = new RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565) // opaque avatar, no alpha needed
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // PERF: cache resized variant on disk — re-open won't re-download
                .priority(priority)
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .circleCrop();

        Glide.with(ctx)
            .load(url)
            .apply(opts)
            // FIX (thumbnail blur-up): TINY tier chained ahead of the real
            // decode — shows an instant blur-up frame instead of a bare
            // placeholder while the real-tier decode finishes.
            .thumbnail(Glide.with(ctx)
                    .load(thumb)
                    .apply(new RequestOptions()
                            .format(DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .priority(priority)
                            .circleCrop()))
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
                    // URL (re-open screen, warm restart) skips Glide entirely.
                    // circleCrop() forces a fresh bitmap so this is always a
                    // plain BitmapDrawable, never a hardware/animated one.
                    if (resource instanceof android.graphics.drawable.BitmapDrawable) {
                        Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) resource).getBitmap();
                        ProfileAvatarL2Cache.get(ctx).put(url, bmp);
                        ProfileAvatarL2Cache.l3(ctx).put(url, bmp);
                    }
                    return false; // let Glide still deliver the drawable into the ImageView
                }
            })
            .into(iv);
    }

    /**
     * FIX (isVisible gate, screen-scoped): call this instead of bind()
     * directly from any listener that can fire while the Activity showing
     * iv might not be the one currently in the foreground (e.g.
     * UserProfileActivity's partnerAvatarVersionListener, alive for as long
     * as the version watch() is registered — which spans pause/resume, not
     * just onCreate..onDestroy). screenVisible should reflect the calling
     * Activity's own onResume()/onPause() state.
     */
    public static void bindGated(Context ctx, ImageView iv, String photo, long avatarVersion, AvatarSizeTier tier, int placeholderRes, boolean screenVisible) {
        if (screenVisible) {
            bind(ctx, iv, photo, avatarVersion, tier, placeholderRes, Priority.IMMEDIATE);
            return;
        }
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            iv.setTag(null);
            return;
        }
        String url = url(ctx, photo, avatarVersion, tier);
        iv.setTag(url);
        Glide.with(ctx)
            .load(url)
            .apply(new RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.DATA) // disk-only tier — no client-side resize to cache, so DATA (raw bytes) is what a cache hit here needs
                .onlyRetrieveFromCache(true)                // never touches the network — a miss just falls through to the placeholder, resolved for real once the screen resumes
                .priority(Priority.LOW)                     // PERF: this screen is backgrounded — never contend with whatever IS in the foreground right now
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .circleCrop())
            .into(iv);
    }

    /**
     * Call from onResume(): upgrades a still-pending {@link #bindGated}
     * disk-only load to the real HIGH-priority network-capable one now that
     * the screen is confirmed back in the foreground. Cheap no-op if this
     * exact URL already resolved (a fresh bind() re-tags the view, so a
     * stale tag here is naturally impossible).
     */
    public static void promote(Context ctx, ImageView iv, String photo, long avatarVersion, AvatarSizeTier tier, int placeholderRes) {
        String expected = url(ctx, photo, avatarVersion, tier);
        if (expected == null || !expected.equals(iv.getTag())) return; // already bound/rebound to something else
        bind(ctx, iv, photo, avatarVersion, tier, placeholderRes, Priority.HIGH);
    }

    /**
     * FIX (Lifecycle-aware cancel): call from onDestroy(). Stops an
     * in-flight request for a hero avatar whose screen is going away for
     * good, and releases the tag so a stale L3 async callback can never
     * paint into a dead view.
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
        iv.setTag(null);
    }
}
