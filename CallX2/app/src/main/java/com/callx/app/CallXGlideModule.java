package com.callx.app;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;

/**
 * CallXGlideModule — v86 Ultra Pro Max Glide configuration.
 *
 * WHY THIS EXISTS
 * ───────────────
 * Glide's default memory cache is 1/8 of available RAM, bitmap pool is 1/4 of RAM.
 * On a chat list with 50+ contacts, the default cache evicts avatars after ~20 scrolls,
 * forcing bitmap re-decode on every re-visit. Telegram uses a significantly larger
 * in-memory bitmap pool so avatars survive across tab switches and back-navigation.
 *
 * SETTINGS (v86):
 *  Memory cache   : 48 MB   — fits ~600 RGB_565 50dp avatars (50*3px * 50*3px * 2B ≈ 81KB each)
 *  BitmapPool     : 48 MB   — same size pool so recycled bitmaps can be reused for loads
 *  Disk cache     : 100 MB  — caches the already-circle-cropped+scaled RESOURCE bitmaps
 *  Default format : RGB_565 — 50% less GPU memory vs ARGB_8888 for non-transparent avatars
 *
 * These values are conservative for modern 4GB+ Android phones. On 2GB devices Glide's
 * low-memory trimming callbacks will reduce the pool automatically.
 */
@GlideModule
public class CallXGlideModule extends AppGlideModule {

    private static final int MEMORY_CACHE_MB  = 48;
    private static final int DISK_CACHE_MB    = 100;

    // API 26+ (HARDWARE bitmaps): intermediate software bitmaps are short-lived
    // during the decode+transform pipeline. 24 MB handles the worst-case burst.
    // API < 26 (RGB_565 software bitmaps): larger pool avoids re-allocation during
    // rapid scrolling. 48 MB fits ~1000 50dp RGB_565 avatars.
    private static final int BITMAP_POOL_MB_HW  = 24;
    private static final int BITMAP_POOL_MB_SW  = 48;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        long memoryCacheBytes = MEMORY_CACHE_MB * 1024L * 1024L;
        long diskCacheBytes   = DISK_CACHE_MB   * 1024L * 1024L;

        // v90: HARDWARE bitmaps on API 26+ — live in GPU memory, zero CPU→GPU upload
        // per frame.  We request ARGB_8888 (the required software format) and Glide
        // automatically promotes the decoded+transformed bitmap to HARDWARE config.
        // Fallback: PREFER_RGB_565 on API < 26 keeps memory footprint low.
        boolean hwBitmaps = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        DecodeFormat format = hwBitmaps
                ? DecodeFormat.PREFER_ARGB_8888   // → Glide promotes to HARDWARE
                : DecodeFormat.PREFER_RGB_565;    // 2 bytes/px, stays in RAM

        long bitmapPoolBytes = (hwBitmaps ? BITMAP_POOL_MB_HW : BITMAP_POOL_MB_SW)
                * 1024L * 1024L;

        builder.setMemoryCache(new LruResourceCache(memoryCacheBytes))
               .setBitmapPool(new LruBitmapPool(bitmapPoolBytes))
               .setDiskCache(new InternalCacheDiskCacheFactory(context, diskCacheBytes))
               .setDefaultRequestOptions(new RequestOptions().format(format));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide,
                                   @NonNull Registry registry) {
        // No custom component overrides needed
    }

    @Override
    public boolean isManifestParsingEnabled() {
        // Disable legacy manifest parsing (speeds up app cold start)
        return false;
    }
}
