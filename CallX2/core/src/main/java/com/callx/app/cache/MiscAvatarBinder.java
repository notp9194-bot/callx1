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
 * MiscAvatarBinder — brings the SAME deep avatar pipeline Reels
 * (AvatarPrefetcher), Chat (ChatAvatarBinder), Status (StatusAvatarBinder),
 * Profile (ProfileAvatarBinder), Search (SearchAvatarBinder) and Follow
 * (FollowAvatarBinder) already have to the app's remaining "misc" screens:
 * AccountMenuActivity, BlockedUsersActivity, ContactsActivity,
 * AllNotificationsActivity, NotificationCenterActivity,
 * CreateBroadcastActivity, MutedChatsActivity and
 * GlobalSavedMessagesActivity.
 *
 * GAP THIS CLOSES: every one of these 8 screens was a flat
 * {@code Glide.with().load(url).override(<hardcoded px>)} with no shared
 * tier bucket (a different px per screen — 96, 240, 720 — meant the SAME
 * user's avatar produced a different CDN URL/cache-key on every one of
 * them), no L2/L3 reuse, no lifecycle-aware cancel on recycle, and no
 * velocity-aware prefetch on the 7 that are RecyclerView lists.
 *
 *  • url()      — AvatarUrlBuilder#buildResponsive: shared AvatarSizeTier
 *                 bucket + density-bucketed dpr_ param + WebP/AVIF format
 *                 param + ?v=&lt;avatarVersion&gt; cache-bust, exactly like
 *                 every other binder in the app.
 *  • bind()     — L2 memory fast-path (MiscAvatarL2Cache, survives
 *                 TRIM_MEMORY_MODERATE) → L3 disk fast-path (survives
 *                 process death) → a real Glide decode, analytics-wrapped,
 *                 written back into L2+L3 so the next bind of this exact
 *                 URL (re-open the screen, warm restart) is instant.
 *  • cancel()   — call from onViewRecycled() (list screens) / onDestroy()
 *                 (AccountMenuActivity's single hero avatars) — stops an
 *                 in-flight request for a row/screen that's gone.
 *  • prefetch() — velocity-based depth, same thresholds as
 *                 ChatAvatarBinder/AvatarPrefetcher/FollowAvatarBinder: fast
 *                 fling skips prefetch entirely, slow/deliberate scroll
 *                 warms several rows ahead, using
 *                 {@link DiskCacheStrategy#DATA} (raw bytes only, decode
 *                 deferred to a real bind()) so a row flung past without
 *                 ever binding never pays a speculative CPU decode.
 *
 * ETag/Last-Modified conditional requests are NOT re-implemented here —
 * same as every other binder, every Glide request app-wide already gets
 * that for free via CallxGlideModule routing through AvatarHttpCache's
 * shared OkHttpClient.
 */
public final class MiscAvatarBinder {

    private MiscAvatarBinder() {}

    /** Typical misc-list row avatar (40-50dp: item_blocked_user, item_member_select, item_all_notification, item_notification_center, item_recipient_select, item_muted_chat row, item_saved_message). */
    public static final AvatarSizeTier ROW_TIER = AvatarSizeTier.forViewSizeDp(48);
    /** AccountMenuActivity's own-profile + header hero avatars (~120dp FrameLayouts). */
    public static final AvatarSizeTier HERO_TIER = AvatarSizeTier.forViewSizeDp(120);

    // Same thresholds/depths as ChatAvatarBinder/AvatarPrefetcher/
    // FollowAvatarBinder — kept in sync deliberately so "fast fling" and
    // "slow scroll" mean the same thing across every avatar list in the app.
    private static final float FAST_FLING_THRESHOLD = 3.5f;  // px/ms — flinging past rows
    private static final float SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms — deliberate scroll
    private static final int DEPTH_DEFAULT = 1;
    private static final int DEPTH_SLOW    = 4;
    private static final int DEPTH_FAST    = 0;

    /** Read-only view over whatever list a misc screen is scrolling — same shape as ChatAvatarBinder.AvatarSource. */
    public interface AvatarSource {
        String photo(int index);
        long avatarVersion(int index);
        int size();
    }

    public static String url(Context ctx, String photo, long avatarVersion, AvatarSizeTier tier) {
        if (photo == null || photo.isEmpty()) return null;
        return AvatarUrlBuilder.buildResponsive(ctx, photo, tier, avatarVersion);
    }

    /** Bind with circleCrop applied (the common case — plain ImageView targets). */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, AvatarSizeTier tier, int placeholderRes) {
        bind(ctx, iv, photo, avatarVersion, tier, placeholderRes, true);
    }

    /**
     * @param applyCircleCrop pass false when iv is already a
     *        de.hdodenhof.circleimageview.CircleImageView (MutedChatsActivity,
     *        GlobalSavedMessagesActivity) — the view itself clips to a
     *        circle, so a second Glide-side circleCrop() would just be
     *        wasted decode work.
     */
    public static void bind(Context ctx, ImageView iv, String photo, long avatarVersion, AvatarSizeTier tier, int placeholderRes, boolean applyCircleCrop) {
        if (photo == null || photo.isEmpty()) {
            iv.setImageResource(placeholderRes);
            iv.setTag(null);
            return;
        }
        String url = url(ctx, photo, avatarVersion, tier);
        iv.setTag(url); // lets a stale in-flight L3 callback recognize it's been rebound since

        Bitmap l2Hit = MiscAvatarL2Cache.get(ctx).get(url);
        if (l2Hit != null) {
            iv.setImageBitmap(l2Hit);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
            return;
        }

        // L3 disk fast-path — covers process death, fired in parallel with
        // the Glide request below; only paints if this exact bind is still
        // current (tag still matches) by the time the async disk read lands.
        MiscAvatarL2Cache.l3(ctx).getAsync(url, l3Bmp -> {
            if (l3Bmp == null) return;
            if (!url.equals(iv.getTag())) return;
            iv.setImageBitmap(l3Bmp);
            MiscAvatarL2Cache.get(ctx).put(url, l3Bmp);
            AvatarCacheAnalytics.getInstance(ctx).record(AvatarCacheAnalytics.Tier.L3_DISK);
        });

        RequestOptions opts = new RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565) // opaque avatar, no alpha needed
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // PERF: cache resized variant on disk — re-open won't re-download
                .placeholder(placeholderRes)
                .error(placeholderRes);
        if (applyCircleCrop) opts = opts.circleCrop();

        Glide.with(ctx)
            .load(url)
            .apply(opts)
            .listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                    return false; // let Glide still apply the error placeholder
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                    AvatarCacheAnalytics.getInstance(ctx)
                        .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                    if (resource instanceof android.graphics.drawable.BitmapDrawable) {
                        Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) resource).getBitmap();
                        MiscAvatarL2Cache.get(ctx).put(url, bmp);
                        MiscAvatarL2Cache.l3(ctx).put(url, bmp);
                    }
                    return false; // let Glide still deliver the drawable into the ImageView
                }
            })
            .into(iv);
    }

    /**
     * Call from onViewRecycled() (list screens) or onDestroy()
     * (AccountMenuActivity). Stops an in-flight request for a row/screen
     * that's gone instead of letting it keep competing for bandwidth/decode
     * time against whatever's actually visible now.
     */
    public static void cancel(Context ctx, ImageView iv) {
        try { Glide.with(ctx).clear(iv); } catch (Exception ignored) {}
        iv.setTag(null);
    }

    /**
     * FIX (velocity-based prefetch + disk-only gate): fast fling past a
     * misc list → skip prefetch entirely (wasted work — the row would be
     * flung past before its avatar even finishes decoding); slow/
     * deliberate scroll → warm several rows ahead. Uses
     * {@link DiskCacheStrategy#DATA} — raw bytes only, decode deferred to a
     * real {@link #bind} once a row genuinely becomes visible — so this
     * never pays CPU decode cost speculatively, only the (cheap,
     * disk-cached) network fetch.
     */
    public static void prefetch(Context context, AvatarSource source, int fromIndex, float velocityPxPerMs, AvatarSizeTier tier) {
        if (context == null || source == null) return;
        int depth = depthForVelocity(velocityPxPerMs);
        if (depth == 0) return;
        Context appCtx = context.getApplicationContext();
        int size = source.size();
        for (int i = Math.max(0, fromIndex); i < fromIndex + depth && i < size; i++) {
            String photo = source.photo(i);
            if (photo == null || photo.isEmpty()) continue;
            String url = url(appCtx, photo, source.avatarVersion(i), tier);
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
