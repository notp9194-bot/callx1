package com.callx.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

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
 * v91: merged from two previously-duplicate GlideModule classes
 * (CallXGlideModule + CallxGlideModule) that only differed by filename
 * case. Having two @GlideModule AppGlideModule subclasses in one app is
 * invalid — Glide's annotation processor only supports a single one —
 * and the case-only filename difference broke case-insensitive
 * filesystems/CI checkouts. This file keeps the best of both:
 *   - HARDWARE-bitmap-aware decode format switching (API 26+)
 *   - Low-RAM-device adaptive cache/pool scaling
 *
 * Why custom cache sizes?
 *   Default Glide memory cache = ~1/8 of available RAM (typically 25–40 MB
 *   on modern devices, as low as 8 MB on low-end phones). Default disk
 *   cache = 250 MB.
 *
 *   For CallX2 (a media-heavy chat app with images, video thumbs, avatars,
 *   status previews):
 *   • Memory cache 40 MB  — fits well over a hundred mid-res chat thumbnails
 *     in RAM. Keeps recently viewed images instant-load without re-decode.
 *   • Disk cache 200 MB   — stores ~800-1500 chat images across sessions.
 *   • BitmapPool 20 MB (HW) / 48 MB (SW) — see decode-format note below.
 *
 * Low-RAM scaling:
 *   On a low-end device (ActivityManager.getMemoryClass() < 128MB app heap),
 *   flat budgets are a big chunk of the whole process heap — during a fast
 *   fling through a media-heavy chat, Glide's own caches could crowd out
 *   headroom and increase OOM risk instead of preventing it. Budgets scale
 *   with getMemoryClass(): full size on normal/high-RAM devices, halved
 *   below the 128MB heap-class threshold.
 *
 * Decode format / HARDWARE bitmaps:
 *   API 26+: request ARGB_8888 and let Glide promote the decoded+transformed
 *   bitmap to Bitmap.Config.HARDWARE. Hardware bitmaps live in GPU memory —
 *   zero CPU→GPU upload per frame during composite. The intermediate
 *   software bitmap during decode is short-lived, so the bitmap pool for
 *   this path can be smaller.
 *   API < 26: no HARDWARE bitmap support — use PREFER_RGB_565 (2 bytes/px)
 *   to keep the memory footprint low, with a larger pool since bitmaps stay
 *   resident in normal RAM and get reused across scroll/reload cycles.
 *
 * @GlideModule triggers annotation-processor code-gen (GeneratedAppGlideModuleImpl).
 * No AndroidManifest.xml meta-data is needed (that was Glide 3 only).
 * isManifestParsingEnabled() = false speeds up init by skipping manifest scanning.
 */
@GlideModule
public final class CallxGlideModule extends AppGlideModule {

    private static final long MEMORY_CACHE_BYTES = 40L * 1024 * 1024;  // 40 MB — normal/high-RAM devices
    private static final long DISK_CACHE_BYTES   = 200L * 1024 * 1024; // 200 MB — unaffected by RAM (disk, not heap)

    // API 26+ (HARDWARE bitmaps): intermediate software bitmaps are short-lived
    // during the decode+transform pipeline. 20 MB handles the worst-case burst.
    // API < 26 (RGB_565 software bitmaps): larger pool avoids re-allocation during
    // rapid scrolling. 48 MB fits ~1000 50dp RGB_565 avatars.
    private static final long BITMAP_POOL_BYTES_HW = 20L * 1024 * 1024;
    private static final long BITMAP_POOL_BYTES_SW = 48L * 1024 * 1024;

    // Devices reporting less than this app-heap class are treated as low-RAM.
    private static final int LOW_RAM_MEMORY_CLASS_MB = 128;
    private static final float LOW_RAM_SCALE = 0.5f;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        boolean hwBitmaps = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        DecodeFormat format = hwBitmaps
                ? DecodeFormat.PREFER_ARGB_8888   // → Glide promotes to HARDWARE
                : DecodeFormat.PREFER_RGB_565;    // 2 bytes/px, stays in RAM

        boolean lowRam = isLowRamDevice(context);
        long baseBitmapPoolBytes = hwBitmaps ? BITMAP_POOL_BYTES_HW : BITMAP_POOL_BYTES_SW;

        long memoryCacheBytes = lowRam ? (long) (MEMORY_CACHE_BYTES * LOW_RAM_SCALE) : MEMORY_CACHE_BYTES;
        long bitmapPoolBytes  = lowRam ? (long) (baseBitmapPoolBytes * LOW_RAM_SCALE) : baseBitmapPoolBytes;

        builder
            // ── In-memory LRU cache ────────────────────────────────────────
            // Holds decoded, ready-to-draw Bitmap objects.
            // Images served from here appear instantly (no disk I/O, no decode).
            .setMemoryCache(new LruResourceCache(memoryCacheBytes))

            // ── On-disk LRU cache ──────────────────────────────────────────
            // Persists compressed image data across app sessions.
            // Chat images re-opened tomorrow load from disk, not the network.
            // (Disk budget is left unscaled — it costs storage, not heap, so
            // it isn't part of the OOM risk this scaling addresses.)
            .setDiskCache(new InternalCacheDiskCacheFactory(context, DISK_CACHE_BYTES))

            // ── Bitmap pool ────────────────────────────────────────────────
            // Recycles Bitmap allocations during scrolling instead of GC-ing them.
            // Critical for smooth 60fps in media-heavy chat lists — but on a
            // low-RAM device an oversized pool eats into the same tiny heap
            // it's trying to protect, so it's scaled down there too.
            .setBitmapPool(new LruBitmapPool(bitmapPoolBytes))

            // ── Decode format ────────────────────────────────────────────────
            .setDefaultRequestOptions(new RequestOptions().format(format));
    }

    private boolean isLowRamDevice(@NonNull Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        if (am.isLowRamDevice()) return true;
        return am.getMemoryClass() < LOW_RAM_MEMORY_CLASS_MB;
    }

    @Override
    public boolean isManifestParsingEnabled() {
        // Disable legacy Glide-v3 manifest meta-data scanning.
        // Speeds up Glide init; we configure everything above.
        return false;
    }
}
