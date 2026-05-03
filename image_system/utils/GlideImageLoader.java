package com.callx.app.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;

import androidx.annotation.Nullable;

/**
 * GlideImageLoader — Smart image loading for CallX2
 *
 * Features:
 *  ✅ Progressive loading (blur thumb → sharp full)
 *  ✅ Network-aware quality (slow = thumb only)
 *  ✅ Preloading for next images
 *  ✅ Smart cache (RAM + Disk)
 *  ✅ Retry/fallback on error
 */
public class GlideImageLoader {

    // ── 1. Chat message image (thumb first → full replace) ───────────────

    /**
     * Progressive load: show thumb instantly → fade to full image.
     * This is the MAIN method used in chat bubbles.
     *
     * @param thumbUrl low-res (~30KB) thumbnail URL
     * @param fullUrl  high-res (~500KB) full image URL
     */
    public static void loadProgressive(Context ctx,
                                       String thumbUrl,
                                       String fullUrl,
                                       ImageView imageView) {
        if (ctx == null || imageView == null) return;

        boolean slowNetwork = isSlowNetwork(ctx);

        if (slowNetwork) {
            // Slow network: show only thumbnail, save data
            loadThumbOnly(ctx, thumbUrl, imageView);
            return;
        }

        // Fast network: thumb instantly, full replaces it
        Glide.with(ctx)
            .load(fullUrl)
            .thumbnail(
                Glide.with(ctx)
                    .load(thumbUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
            )
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .apply(new RequestOptions()
                .override(1280, 1920)          // cap memory usage
                .encodeQuality(90)
            )
            .listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(@Nullable GlideException e,
                                            Object model,
                                            Target<Drawable> target,
                                            boolean isFirstResource) {
                    // Fallback: show thumb on full load failure
                    loadThumbOnly(ctx, thumbUrl, imageView);
                    return true;
                }
                @Override
                public boolean onResourceReady(Drawable resource,
                                               Object model,
                                               Target<Drawable> target,
                                               DataSource dataSource,
                                               boolean isFirstResource) {
                    return false; // let Glide handle it
                }
            })
            .into(imageView);
    }

    // ── 2. Thumbnail only (chat list, slow network) ───────────────────────

    public static void loadThumbOnly(Context ctx, String thumbUrl, ImageView imageView) {
        if (ctx == null || imageView == null) return;

        Glide.with(ctx)
            .load(thumbUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .apply(new RequestOptions().override(200, 200))
            .into(imageView);
    }

    // ── 3. Avatar / Profile picture ───────────────────────────────────────

    /**
     * Circular avatar load for profile pictures, contact list.
     * Cached aggressively since avatars rarely change.
     */
    public static void loadAvatar(Context ctx,
                                  String url,
                                  ImageView imageView,
                                  int placeholderRes) {
        if (ctx == null || imageView == null) return;

        Glide.with(ctx)
            .load(url)
            .circleCrop()
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .apply(new RequestOptions().override(200, 200))
            .into(imageView);
    }

    /**
     * Force refresh avatar (call after user updates profile photo).
     * Uses cache-busting signature.
     */
    public static void loadAvatarFresh(Context ctx,
                                       String url,
                                       ImageView imageView,
                                       int placeholderRes) {
        if (ctx == null || imageView == null) return;

        Glide.with(ctx)
            .load(url)
            .circleCrop()
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .signature(new ObjectKey(System.currentTimeMillis()))  // bust cache
            .apply(new RequestOptions().override(200, 200))
            .into(imageView);
    }

    // ── 4. Full-screen image viewer ───────────────────────────────────────

    /**
     * High-quality load for full-screen image viewer.
     * No size limit — show original quality.
     */
    public static void loadFullScreen(Context ctx, String fullUrl, ImageView imageView) {
        if (ctx == null || imageView == null) return;

        Glide.with(ctx)
            .load(fullUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            // No override() → full resolution
            .into(imageView);
    }

    // ── 5. Preload (next image) ───────────────────────────────────────────

    /**
     * Call this for the NEXT image in a list so it's cached before user scrolls.
     * Preload thumb only (cheap), full loads on demand.
     */
    public static void preload(Context ctx, String thumbUrl, String fullUrl) {
        if (ctx == null) return;

        // Preload thumbnail (cheap, always)
        Glide.with(ctx)
            .load(thumbUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .preload(200, 200);

        // Preload full only on fast network
        if (!isSlowNetwork(ctx)) {
            Glide.with(ctx)
                .load(fullUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .preload(1280, 1920);
        }
    }

    // ── 6. Clear single image cache ───────────────────────────────────────

    public static void clearCache(Context ctx, String url) {
        Glide.with(ctx)
            .load(url)
            .signature(new ObjectKey(System.currentTimeMillis()))
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .preload();
    }

    // ── Network quality detection ─────────────────────────────────────────

    private static boolean isSlowNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return true;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return true;

            // 2G / slow connections
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }
        return false;
    }
}
