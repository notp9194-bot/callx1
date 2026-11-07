package com.callx.app.interactions;

import android.content.Context;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

/**
 * Shared avatar loader for status bottom sheets (reply compose, seen-by).
 * Same tier (TINY, 32dp), same URL (AvatarUrlBuilder#buildResponsive), same RequestOptions
 * as StatusRepliesBottomSheet / ReelCommentAvatarBinder -> one shared Glide cache entry.
 */
public final class StatusSheetAvatar {
    private StatusSheetAvatar() {}

    public static final AvatarSizeTier TIER = AvatarSizeTier.TINY; // 32dp
    public static final int SIZE_DP = 32;

    /** Seen-by rows + the viewer's bottom bar use the reel-comment avatar spec: 32dp avatar / 36dp ring -> TINY (32dp),
     *  same URL + RequestOptions as ReelCommentAvatarBinder -> one shared Glide cache entry. */
    public static final AvatarSizeTier ROW_TIER = AvatarSizeTier.TINY;

    public static RequestOptions options(Context ctx) { return options(ctx, TIER); }

    public static RequestOptions options(Context ctx, AvatarSizeTier tier) {
        int px = AvatarUrlBuilder.tierPx(ctx, tier);
        RequestOptions o = new RequestOptions()
                .override(px, px)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE);
        // TINY (reply sheet) keeps RGB_565 exactly as before. The 54dp Seen-by row avatar (ROW_TIER) uses the app
        // default on API 26+ (ARGB_8888 -> HARDWARE bitmap: no 565 banding / no first-draw upload), same rule as the
        // Status cards; RGB_565 stays for API < 26 / low-RAM devices or when ROW_HW_BITMAPS=false.
        if (tier == TIER || useRgb565(ctx)) o = o.format(DecodeFormat.PREFER_RGB_565);
        return o;
    }

    /** Kill switch: false = RGB_565 for the row avatar too (v30 behaviour). */
    private static final boolean ROW_HW_BITMAPS = true;
    private static int sFmt = -1; // 0 = app default (HW on 26+), 1 = RGB_565
    private static boolean useRgb565(Context ctx) {
        if (sFmt < 0) {
            boolean low = false;
            try {
                final android.app.ActivityManager am = (android.app.ActivityManager)
                        ctx.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
                low = am != null && am.isLowRamDevice();
            } catch (Exception ignored) {}
            sFmt = (!ROW_HW_BITMAPS || android.os.Build.VERSION.SDK_INT < 26 || low) ? 1 : 0;
        }
        return sFmt == 1;
    }

    public static void load(Context ctx, ImageView iv, String photo, int placeholderRes) {
        load(ctx, iv, photo, placeholderRes, TIER);
    }

    public static void load(Context ctx, ImageView iv, String photo, int placeholderRes, AvatarSizeTier tier) {
        load(ctx, iv, photo, placeholderRes, tier, 0L);
    }

    /** v717: memo photo|tier|px|version -> CDN URL (buildResponsive is string/regex heavy and runs on every row bind). */
    private static final android.util.LruCache<String, String> URL_MEMO = new android.util.LruCache<>(256);

    public static String url(Context ctx, String photo, AvatarSizeTier tier, long avatarVersion) {
        final String key = photo + "|" + tier.ordinal() + "|" + AvatarUrlBuilder.tierPx(ctx, tier) + "|" + avatarVersion;
        String u = URL_MEMO.get(key);
        if (u != null) return u;
        u = avatarVersion > 0
                ? AvatarUrlBuilder.buildResponsive(ctx, photo, tier, avatarVersion)
                : AvatarUrlBuilder.buildResponsive(ctx, photo, tier);
        if (u == null || u.isEmpty()) u = photo;
        URL_MEMO.put(key, u);
        return u;
    }

    /** Cached avatar version of a user (0 = unknown). Same source the Status tab cards use. */
    public static long versionOf(Context ctx, String uid) {
        if (uid == null) return 0L;
        try { return com.callx.app.cache.AvatarVersionSyncManager.getInstance(ctx.getApplicationContext()).getCachedVersion(uid); }
        catch (Exception e) { return 0L; }
    }

    /** Versioned load: a changed profile photo (new version) gets a new URL -> new cache key, never the stale cached image. */
    public static void load(Context ctx, ImageView iv, String photo, int placeholderRes, AvatarSizeTier tier, long avatarVersion) {
        if (photo == null || photo.isEmpty()) {
            Glide.with(ctx).clear(iv);
            iv.setImageResource(placeholderRes);
            return;
        }
        Glide.with(ctx)
                .load(url(ctx, photo, tier, avatarVersion))
                .apply(options(ctx, tier))
                .placeholder(placeholderRes)
                .into(iv);
    }
}
