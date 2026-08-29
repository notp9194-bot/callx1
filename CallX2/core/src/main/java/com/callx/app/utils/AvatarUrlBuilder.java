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

    /**
     * Build a resized avatar URL for a shared {@link AvatarSizeTier}, with an
     * explicit avatar version appended as a cache-busting query param.
     *
     * Why: Cloudinary already gives every fresh upload a brand-new
     * public_id/URL, so the CDN-level URL itself never collides with an old
     * one. The gap this closes is upstream of that — every screen that shows
     * an avatar (reel owner, comments, follow list, mentions...) resolves it
     * from a URL string it already has cached locally (Room's UserEntity,
     * Firebase snapshot listeners, in-memory LruCaches like
     * ReelCommentsAdapter#avatarCache) which can still be holding the OLD
     * url string for a few seconds/minutes after a profile update, before
     * that particular cache happens to refresh from Firebase. Appending
     * &v=<avatarVersion> means even if two different call sites end up
     * passing the exact same (stale) baseUrl for the same user, callers that
     * DO have the fresh version (e.g. this device's own ProfileActivity,
     * right after upload) immediately produce a different Glide cache key
     * from the still-stale one — no waiting on LRU eviction for the switch
     * to take effect locally. version <= 0 omits the param entirely
     * (unversioned — same behavior as before), so existing callers that
     * don't have a version handy keep working unchanged.
     */
    public static String build(Context ctx, String baseUrl, AvatarSizeTier tier, long avatarVersion) {
        return appendVersion(build(ctx, baseUrl, tier), avatarVersion);
    }

    /** Appends "&v=<avatarVersion>" (or "?v=" if the URL has no query yet) to any URL. No-op if version <= 0 or url is empty. */
    public static String appendVersion(String url, long avatarVersion) {
        if (url == null || url.isEmpty() || avatarVersion <= 0) return url;
        return url + (url.indexOf('?') >= 0 ? "&v=" : "?v=") + avatarVersion;
    }

    /**
     * Build a resized avatar URL for a shared {@link AvatarSizeTier}, at 2x for retina.
     * Prefer this over the raw-dp overload below — every call site asking for the
     * same tier produces the identical URL (and therefore Glide cache key), so a
     * user's avatar is decoded once per tier and reused across every screen instead
     * of once per screen. Pick the tier with {@link AvatarSizeTier#forViewSizeDp}.
     * If you have the user's current avatarVersion available, prefer the
     * 4-arg overload above instead — it guarantees a cache-key change the
     * moment a fresher version is known, without waiting for baseUrl itself
     * to be refetched.
     */
    public static String build(Context ctx, String baseUrl, AvatarSizeTier tier) {
        return buildPx(baseUrl, tierPx(ctx, tier));
    }

    /**
     * The exact px Glide's .override() should decode to for a tier. Always pair
     * this with {@link #build(Context, String, AvatarSizeTier)} for the SAME tier,
     * so the downloaded size and the decode size match exactly.
     *
     * FIX (density-aware override): was a flat "* 2" for every device — an
     * mdpi/hdpi phone was decoding the same retina-2x pixels as an xxxhdpi
     * flagship, over-downloading and over-decoding for no visible benefit.
     * Now scales with the device's actual density bucket via
     * {@link #densityMultiplier}. Deliberately BUCKETED (not the raw exact
     * density) — using the raw density would mean two devices at 2.0 vs 2.75
     * produce two different override sizes for the same tier, which
     * fragments both the Glide disk-cache key AND the Cloudinary CDN edge
     * cache per device model instead of per tier. Bucketing keeps the "one
     * URL per tier" cache-sharing win from AvatarSizeTier intact while still
     * being visually sharp on every density class.
     */
    public static int tierPx(Context ctx, AvatarSizeTier tier) {
        return Math.round(dpToPx(ctx, tier.dp) * densityMultiplier(ctx));
    }

    /**
     * Buckets the device's exact display density into one of 3 cache-friendly
     * retina multipliers, always rounding UP so we never under-resolve:
     *   density <= 1.0  (mdpi and below)      → 1.5x
     *   density <= 2.0  (hdpi/xhdpi)          → 2.0x
     *   density  > 2.0  (xxhdpi/xxxhdpi+)     → 3.0x
     * Avatars are small (max tier 150dp) so even the top bucket stays cheap
     * to decode — this only controls how sharp the smallest/cheapest devices
     * over-fetch, not how much the biggest devices under-fetch.
     */
    private static float densityMultiplier(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        if (density <= 1.0f) return 1.5f;
        if (density <= 2.0f) return 2.0f;
        return 3.0f;
    }

    /**
     * @deprecated Raw dp values fragment the Glide cache — the same avatar photo
     * requested at 28dp on one screen and 32dp on another produces two different
     * URLs/cache entries even though nothing about the image differs. Use
     * {@link #build(Context, String, AvatarSizeTier)} with a shared tier instead.
     */
    @Deprecated
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

        // f_auto = Cloudinary content-negotiates the best format per request
        // (WebP/AVIF on clients that support it, falls back to JPEG) purely
        // from the Accept header — no separate URL/param needed on our side,
        // and it composes with the density-bucketed sizePx above so each
        // (tier × density-bucket) combination still resolves to exactly one
        // cached CDN variant instead of fragmenting further.
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
