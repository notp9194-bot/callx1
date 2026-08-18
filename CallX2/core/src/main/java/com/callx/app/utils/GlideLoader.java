package com.callx.app.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

/**
 * GLIDE LOADER — common image-loading helper
 * ──────────────────────────────────────────────────────────────────────
 * Sab jagah jaha simple "load url into ImageView/PhotoView with
 * placeholder + error fallback" pattern repeat ho raha tha (avatar zoom
 * dialogs, list rows, etc.) — usko ek jagah consolidate kiya hai.
 *
 * Agar photoUrl null/empty hai, directly placeholder resource set kar
 * deta hai (Glide call hi nahi karta) — same as pehle har jagah manually
 * likha hua tha.
 */
public final class GlideLoader {

    private GlideLoader() {
        // no instances
    }

    /**
     * Plain load — koi transformation (circleCrop etc.) nahi.
     * Avatar-zoom fullscreen dialogs ke liye yahi use hota hai.
     */
    public static void load(Context ctx, String url, ImageView target,
                             int placeholderRes, int errorRes) {
        if (ctx == null || target == null) return;

        if (url != null && !url.isEmpty()) {
            Glide.with(ctx).load(url)
                .placeholder(placeholderRes)
                .error(errorRes)
                .override(720, 720)
                // PERF: force full disk-cache (source + result) instead of
                // relying on Glide's AUTOMATIC default. Same URL always
                // requested at the same 720x720 size here, so this
                // guarantees a second avatar-zoom open of the same person
                // is served straight from disk (or memory) with zero
                // network round-trip.
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(target);
        } else {
            target.setImageResource(placeholderRes);
        }
    }

    /**
     * PERF (ultra-advanced avatar-zoom pass): same as {@link #load} above,
     * but takes a real Drawable as the placeholder instead of a resource id.
     * Used when the caller already has the avatar's small circle-cropped
     * bitmap sitting in memory (the row/profile ImageView that was just
     * tapped) — showing THAT instantly instead of the generic person icon
     * means the dock-open animation always starts on real pixels, so there's
     * no visible "pop" once the full-res 720x720 load lands, and zero extra
     * decode cost since the drawable is already decoded.
     */
    public static void load(Context ctx, String url, ImageView target,
                             Drawable placeholderDrawable, int errorRes) {
        if (ctx == null || target == null) return;

        if (url != null && !url.isEmpty()) {
            Glide.with(ctx).load(url)
                .placeholder(placeholderDrawable)
                .error(errorRes)
                .override(720, 720)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(target);
        } else if (placeholderDrawable != null) {
            target.setImageDrawable(placeholderDrawable);
        } else {
            target.setImageResource(errorRes);
        }
    }

    /** Circle-cropped load — list rows / small avatars ke liye. */
    public static void loadCircle(Context ctx, String url, ImageView target,
                                   int placeholderRes, int errorRes) {
        if (ctx == null || target == null) return;

        if (url != null && !url.isEmpty()) {
            Glide.with(ctx).load(url)
                .circleCrop()
                .placeholder(placeholderRes)
                .error(errorRes)
                .override(96, 96)
                .into(target);
        } else {
            target.setImageResource(placeholderRes);
        }
    }
}
