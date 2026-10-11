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

    public static RequestOptions options(Context ctx) {
        int px = AvatarUrlBuilder.tierPx(ctx, TIER);
        return new RequestOptions()
                .override(px, px)
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE);
    }

    public static void load(Context ctx, ImageView iv, String photo, int placeholderRes) {
        if (photo == null || photo.isEmpty()) {
            Glide.with(ctx).clear(iv);
            iv.setImageResource(placeholderRes);
            return;
        }
        Glide.with(ctx)
                .load(AvatarUrlBuilder.buildResponsive(ctx, photo, TIER))
                .apply(options(ctx))
                .placeholder(placeholderRes)
                .into(iv);
    }
}
