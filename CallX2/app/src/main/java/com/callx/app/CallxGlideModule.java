package com.callx.app;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.cache.AvatarHttpCache;
import com.callx.app.cache.DynamicCachePolicy;

import java.io.InputStream;

import okhttp3.OkHttpClient;

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
 * Cache sizing:
 *   Glide's MemorySizeCalculator derives the memory cache and bitmap-pool
 *   sizes from the device's app heap/RAM class. DiskCache uses the shared
 *   DynamicCachePolicy, which derives its budget from free storage.
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

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        boolean hwBitmaps = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        DecodeFormat format = hwBitmaps
                ? DecodeFormat.PREFER_ARGB_8888   // → Glide promotes to HARDWARE
                : DecodeFormat.PREFER_RGB_565;    // 2 bytes/px, stays in RAM

        // Glide's own calculator is heap/RAM aware and already reduces both
        // budgets on low-RAM devices. This removes the old 40 MB hard cap.
        MemorySizeCalculator calculator = new MemorySizeCalculator.Builder(context).build();
        long memoryCacheBytes = calculator.getMemoryCacheSize();
        long bitmapPoolBytes = calculator.getBitmapPoolSize();
        long diskCacheBytes = DynamicCachePolicy.getGlideDiskCacheBytes(context);

        builder
            // ── In-memory LRU cache ────────────────────────────────────────
            // Holds decoded, ready-to-draw Bitmap objects.
            // Images served from here appear instantly (no disk I/O, no decode).
            .setMemoryCache(new LruResourceCache(memoryCacheBytes))

            // ── On-disk LRU cache ──────────────────────────────────────────
            // Persists compressed image data across app sessions.
            // Chat images re-opened tomorrow load from disk, not the network.
             // (Disk budget is calculated separately from free storage by the
             // shared DynamicCachePolicy.)
             .setDiskCache(new InternalCacheDiskCacheFactory(context, diskCacheBytes))

            // ── Bitmap pool ────────────────────────────────────────────────
            // Recycles Bitmap allocations during scrolling instead of GC-ing them.
             // Critical for smooth 60fps in media-heavy chat lists. The
             // MemorySizeCalculator keeps it proportional to the app heap.
            .setBitmapPool(new LruBitmapPool(bitmapPoolBytes))

            // ── Decode format ────────────────────────────────────────────────
            .setDefaultRequestOptions(new RequestOptions().format(format));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        // Disable legacy Glide-v3 manifest meta-data scanning.
        // Speeds up Glide init; we configure everything above.
        return false;
    }

    /**
     * FIX (ETag/Last-Modified conditional requests): routes ALL of Glide's
     * HTTP image fetches — avatars included — through an OkHttpClient backed
     * by a disk Cache (AvatarHttpCache), replacing Glide's default bare
     * HttpURLConnection loader. See AvatarHttpCache's class doc for the full
     * "combines with the ?v= version param" rationale: version param handles
     * "did the avatar actually change", this handles "the URL is unchanged
     * but Glide's own resource cache doesn't have it anymore" — a CDN 304 in
     * that case means only headers cross the wire, not the image body.
     */
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        OkHttpClient client = AvatarHttpCache.getClient(context);
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(client));
    }
}
