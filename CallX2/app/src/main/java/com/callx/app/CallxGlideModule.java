package com.callx.app;

import android.app.ActivityManager;
import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;

/**
 * CallxGlideModule — app-wide Glide configuration.
 *
 * PRODUCTION UPGRADE: Adaptive memory sizing based on device RAM class.
 *
 * Why adaptive instead of fixed values?
 *   Fixed 20 MB was too small on flagship devices (wasted RAM → re-decodes)
 *   and too large on low-end (< 512 MB RAM) devices (contributed to OOM).
 *
 * Device tiers (ActivityManager.getMemoryClass()):
 *   Low-end  (memClass < 128 MB) : memory 16 MB, disk 50 MB,  pool 8 MB
 *   Mid-range (128-256 MB)       : memory 24 MB, disk 100 MB, pool 12 MB
 *   Flagship  (> 256 MB)         : memory 48 MB, disk 150 MB, pool 24 MB
 *
 * Disk cache 100-150 MB vs old 50 MB: chat images are accessed across
 * sessions (user closes app, reopens tomorrow). Larger disk cache = fewer
 * network re-fetches = faster chat opens on subsequent visits.
 *
 * @GlideModule triggers annotation-processor code-gen (GeneratedAppGlideModuleImpl).
 * No AndroidManifest.xml meta-data is needed (that was Glide 3 only).
 * isManifestParsingEnabled() = false speeds up init by skipping manifest scanning.
 */
@GlideModule
public final class CallxGlideModule extends AppGlideModule {

    // Disk cache: fixed at 100 MB — large enough for ~400 chat images across
    // sessions. Not adaptive (disk space is cheap; consistent across devices).
    private static final long DISK_CACHE_BYTES = 100L * 1024 * 1024; // 100 MB

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        final long memoryCacheBytes;
        final long bitmapPoolBytes;

        // Adaptive sizing: scale caches to device RAM class.
        // memClass is heap limit in MB (e.g. 128, 256, 512).
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        int memClass = (am != null) ? am.getMemoryClass() : 128; // fallback: mid-range

        if (memClass < 128) {
            // Low-end: be conservative — OOM risk is real.
            // ~16 chat thumbnails in RAM; disk compensates.
            memoryCacheBytes = 16L * 1024 * 1024; // 16 MB
            bitmapPoolBytes  =  8L * 1024 * 1024; //  8 MB
        } else if (memClass < 256) {
            // Mid-range: balanced — fits ~50-60 thumbnails in RAM.
            memoryCacheBytes = 24L * 1024 * 1024; // 24 MB
            bitmapPoolBytes  = 12L * 1024 * 1024; // 12 MB
        } else {
            // Flagship: use more RAM for silky-smooth media scrolling.
            // ~100-150 thumbnails stay decoded and ready in RAM.
            memoryCacheBytes = 48L * 1024 * 1024; // 48 MB
            bitmapPoolBytes  = 24L * 1024 * 1024; // 24 MB
        }

        builder
            // ── In-memory LRU cache ────────────────────────────────────────
            // Holds decoded, ready-to-draw Bitmap objects.
            // Images served from here appear instantly (no disk I/O, no decode).
            .setMemoryCache(new LruResourceCache(memoryCacheBytes))

            // ── On-disk LRU cache ──────────────────────────────────────────
            // Persists compressed image data across app sessions.
            // 100 MB instead of 50 MB: chat images are frequently re-accessed
            // across sessions. Fewer evictions = fewer network re-fetches.
            .setDiskCache(new InternalCacheDiskCacheFactory(context, DISK_CACHE_BYTES))

            // ── Bitmap pool ────────────────────────────────────────────────
            // Recycles Bitmap allocations during scrolling instead of GC-ing them.
            // Critical for smooth 60fps in media-heavy chat lists.
            .setBitmapPool(new LruBitmapPool(bitmapPoolBytes))

            // ── Decode format ──────────────────────────────────────────────
            // PREFER_ARGB_8888: full colour depth. Better than RGB_565 on AMOLED
            // screens (no colour banding on gradients / profile photos).
            .setDefaultRequestOptions(
                new RequestOptions().format(DecodeFormat.PREFER_ARGB_8888));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        // Disable legacy Glide-v3 manifest meta-data scanning.
        // Speeds up Glide init; we configure everything above.
        return false;
    }
}
