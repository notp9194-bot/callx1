package com.callx.app;

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
 *   • Memory cache 20 MB  — fits ~40–80 mid-res chat thumbnails in RAM.
 *     Keeps recently viewed images instant-load without re-decoding from disk.
 *   • Disk cache 50 MB    — stores ~200–400 chat images across sessions.
 *     A user re-opening a chat sees the same images without any network round-trip.
 *   • BitmapPool 10 MB    — Glide reuses Bitmap objects instead of allocating new ones
 *     for every image decode. Cuts GC pressure during fast scrolling.
 *   • PREFER_ARGB_8888    — higher quality than RGB_565; worth it on modern AMOLED screens.
 *
 * @GlideModule triggers annotation-processor code-gen (GeneratedAppGlideModuleImpl).
 * No AndroidManifest.xml meta-data is needed (that was Glide 3 only).
 * isManifestParsingEnabled() = false speeds up init by skipping manifest scanning.
 */
@GlideModule
public final class CallxGlideModule extends AppGlideModule {

    private static final long MEMORY_CACHE_BYTES = 20L * 1024 * 1024;  // 20 MB
    private static final long DISK_CACHE_BYTES   = 50L * 1024 * 1024;  // 50 MB
    private static final long BITMAP_POOL_BYTES  = 10L * 1024 * 1024;  // 10 MB

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder
            // ── In-memory LRU cache ────────────────────────────────────────
            // Holds decoded, ready-to-draw Bitmap objects.
            // Images served from here appear instantly (no disk I/O, no decode).
            .setMemoryCache(new LruResourceCache(MEMORY_CACHE_BYTES))

            // ── On-disk LRU cache ──────────────────────────────────────────
            // Persists compressed image data across app sessions.
            // Chat images re-opened tomorrow load from disk, not the network.
            .setDiskCache(new InternalCacheDiskCacheFactory(context, DISK_CACHE_BYTES))

            // ── Bitmap pool ────────────────────────────────────────────────
            // Recycles Bitmap allocations during scrolling instead of GC-ing them.
            // Critical for smooth 60fps in media-heavy chat lists.
            .setBitmapPool(new LruBitmapPool(BITMAP_POOL_BYTES))

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
