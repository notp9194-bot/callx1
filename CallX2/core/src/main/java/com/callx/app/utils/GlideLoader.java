package com.callx.app.utils;

import android.content.Context;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

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
                .into(target);
        } else {
            target.setImageResource(placeholderRes);
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
                .into(target);
        } else {
            target.setImageResource(placeholderRes);
        }
    }
}
