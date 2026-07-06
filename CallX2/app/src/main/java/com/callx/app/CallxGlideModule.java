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
 * Why custom cache sizes?
 *   Default Glide memory cache = ~1/8 of available RAM (typically 25–40 MB on modern devices,
 *   but as low as 8 MB on low-end phones). Default disk cache = 250 MB.
 *
 *   For CallX2 (a media-heavy chat app with images, video thumbs, avatars, status previews):
 *   • Memory cache 40 MB  — fits well over a hundred mid-res chat thumbnails in RAM.
 *     Keeps recently viewed images instant-load without re-decoding from disk.
 *   • Disk cache 200 MB   — stores ~800-1500 chat images across sessions.
 *     A user re-opening a chat sees the same thumbnails without any network round-trip,
 *     even after scrolling through a long, media-heavy conversation.
 *   • BitmapPool 20 MB    — Glide reuses Bitmap objects instead of allocating new ones
 *     for every image decode. Cuts GC pressure during fast scrolling.
 *   • PREFER_ARGB_8888    — higher quality than RGB_565; worth it on modern AMOLED screens.
 *
 * FIX: all three budgets above were fixed constants regardless of device RAM.
 * On a low-end device (ActivityManager.getMemoryClass() ~ 96-128MB app heap),
 * a flat 40MB memory cache + 20MB bitmap pool is a big chunk of the whole
 * process heap — during a fast fling through a media-heavy chat, Glide's own
 * caches could crowd out headroom needed elsewhere and increase OOM risk
 * instead of preventing it. Budgets now scale with getMemoryClass(): full
 * size on normal/high-RAM devices, roughly halved below a 128MB heap class.
 *
 * @GlideModule triggers annotation-processor code-gen (GeneratedAppGlideModuleImpl).
 * No AndroidManifest.xml meta-data is needed (that was Glide 3 only).
 * isManifestParsingEnabled() = false speeds up init by skipping manifest scanning.
 */
@GlideModule
public final class CallxGlideModule extends AppGlideModule {

    // PERF: bumped from the original 20/50/10 MB defaults. CallX2 chats can
    // easily hold hundreds of image/video-thumb/reply-thumb bubbles, and the
    // old 50 MB disk cache was getting evicted within a single long chat —
    // reopening a chat (or even scrolling back up) re-hit the network for
    // thumbnails that had *just* been downloaded. Bigger budgets mean a
    // re-opened chat loads its media thumbnails from disk, not the network.
    // v111: cache budgets tripled — media-heavy chats were still evicting
    // recently decoded bitmaps within a single long conversation, forcing
    // re-decodes (memory) and re-downloads (disk) just from scrolling back.
    // 120 MB memory keeps ~300 mid-res chat thumbnails in RAM.
    // 500 MB disk survives a multi-day offline gap without a single re-fetch.
    //  40 MB pool — wider bitmap recycling cuts GC pressure during fast flings.
    private static final long MEMORY_CACHE_BYTES = 120L * 1024 * 1024;  // 120 MB — normal/high-RAM devices
    private static final long DISK_CACHE_BYTES   = 500L * 1024 * 1024;  // 500 MB — unaffected by RAM (disk, not heap)
    private static final long BITMAP_POOL_BYTES  =  40L * 1024 * 1024;  //  40 MB — normal/high-RAM devices

    // Devices reporting less than this app-heap class are treated as low-RAM.
    // Low-RAM path scales memory/pool by LOW_RAM_SCALE; disk is left full
    // because it costs storage not heap.
    private static final int LOW_RAM_MEMORY_CLASS_MB = 128;
    private static final float LOW_RAM_SCALE = 0.5f;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        boolean lowRam = isLowRamDevice(context);
        long memoryCacheBytes = lowRam ? (long) (MEMORY_CACHE_BYTES * LOW_RAM_SCALE) : MEMORY_CACHE_BYTES;
        long bitmapPoolBytes  = lowRam ? (long) (BITMAP_POOL_BYTES * LOW_RAM_SCALE) : BITMAP_POOL_BYTES;

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

            // ── Decode format ──────────────────────────────────────────────
            // PREFER_ARGB_8888: full colour depth. Better than RGB_565 on AMOLED
            // screens (no colour banding on gradients / profile photos).
            .setDefaultRequestOptions(
                new RequestOptions().format(DecodeFormat.PREFER_ARGB_8888));
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
