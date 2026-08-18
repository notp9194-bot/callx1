package com.callx.app.utils;

import android.content.Context;

/**
 * Central helper for avatar image URLs — every avatar load call (reel
 * player, comments, profile, story ring, contact rows, etc.) should go
 * through build()/buildPx() instead of loading a stored thumbUrl/photoUrl
 * directly.
 *
 * Why this exists: we only ever generate 2 static variants at upload time
 * (100×100 thumb, 800×800 full — see CloudinaryUploader#uploadAvatar), but
 * an avatar is rendered at many different sizes across the app (34dp reel
 * player, 36dp comments, 42dp lists, larger profile screens...). Loading
 * the same 100×100 thumb everywhere either over-downloads for small views
 * or under-resolves for larger ones. Cloudinary can resize+reformat any
 * already-uploaded image on the fly by inserting a transform segment right
 * after "/upload/" in the delivery URL — CDN caches the resized variant on
 * first request, no extra upload/storage needed. This helper is the single
 * place that builds that transform segment, so every screen asks for
 * exactly the pixels it needs.
 *
 * g_face (gravity: face) keeps a face centered in the crop when the source
 * photo isn't already a tight square headshot — matters more for the
 * 800×800 full photo than the pre-cropped 100×100 thumb, but is harmless
 * either way and future-proofs this if the thumb generation ever changes.
 */
public final class AvatarUrlBuilder {

    private AvatarUrlBuilder() {}

    /** Build a resized avatar URL for a view that is sizeDp × sizeDp, at 2x for retina. */
    public static String build(Context ctx, String baseUrl, int sizeDp) {
        int sizePx = dpToPx(ctx, sizeDp) * 2;
        return buildPx(baseUrl, sizePx);
    }

    /** Build a resized avatar URL for an exact target pixel size (already 2x'd if needed). */
    public static String buildPx(String baseUrl, int sizePx) {
        if (baseUrl == null || baseUrl.isEmpty()) return baseUrl;
        String marker = "/upload/";
        int idx = baseUrl.indexOf(marker);
        if (idx < 0) return baseUrl; // not a Cloudinary delivery URL — return as-is, no-op

        String transform = "w_" + sizePx + ",h_" + sizePx
                + ",c_fill,g_face,q_auto,f_auto/";
        return baseUrl.substring(0, idx + marker.length())
                + transform
                + baseUrl.substring(idx + marker.length());
    }

    public static int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }
}
