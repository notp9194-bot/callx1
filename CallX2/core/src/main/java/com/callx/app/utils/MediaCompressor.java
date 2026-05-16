package com.callx.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaCompressor v25 — IMAGE LIGHT, VIDEO SERVER-SIDE
 *
 * PEHLE (v24): Video: Mobile pe MediaCodec encode — heavy CPU, mobile garam
 *              Image: Mobile pe Bitmap resize — acceptable
 *
 * AB (v25):
 *  - VIDEO: VideoCompressor.compress() use karo jo server pe bhejta hai — is class mein video nahi
 *  - IMAGE: Same as before (Bitmap resize, WebP/JPEG — CPU-light, acceptable)
 *
 * Image compression mobile pe rakhna theek hai kyunki:
 *  - Sirf Bitmap decode + resize + encode hota hai (koi video encode nahi)
 *  - 1-2 seconds mein ho jaata hai, CPU spike short hota hai
 *  - Server roundtrip se slow ho jaata overall
 *
 * Video ab is class mein handle nahi hota — VideoCompressor.compress() directly use karo.
 */
public class MediaCompressor {

    private static final String TAG = "MediaCompressor";

    // Image settings
    private static final int  MAX_IMAGE_PX      = 1280;
    private static final int  IMAGE_QUALITY      = 80;
    private static final int  THUMB_MAX_PX       = 320;
    private static final int  THUMB_QUALITY      = 70;

    private static final ExecutorService sPool = Executors.newFixedThreadPool(2);
    private static final Handler         sMain = new Handler(Looper.getMainLooper());

    // ── Callbacks ─────────────────────────────────────────────────────────────────

    public interface ImageCallback {
        void onDone(byte[] imageBytes);
        void onError(String msg);
    }

    /**
     * @deprecated Video compression ab server pe hoti hai.
     * VideoCompressor.compress(ctx, uri, quality, callback) use karo directly.
     */
    @Deprecated
    public interface VideoCallback {
        void onDone(java.io.File outFile);
        void onError(String msg);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // IMAGE COMPRESSION (Mobile pe — acceptable CPU load)
    // ─────────────────────────────────────────────────────────────────────────────

    /** Compress image at default quality (1280px, JPEG/WebP 80%) */
    public static byte[] compressImage(Context ctx, Uri uri) {
        return compressImageWithQuality(ctx, uri, MAX_IMAGE_PX, IMAGE_QUALITY, false);
    }

    /** Compress image thumbnail (320px, WebP 70%) */
    public static byte[] compressImageThumb(Context ctx, Uri uri) {
        return compressImageWithQuality(ctx, uri, THUMB_MAX_PX, THUMB_QUALITY, true);
    }

    /**
     * Full image compression with quality control.
     * WebP on Android 11+ for smaller size, JPEG fallback.
     */
    public static byte[] compressImageWithQuality(Context ctx, Uri uri,
                                                   int maxPx, int quality,
                                                   boolean forceWebP) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;

            // Decode with inSampleSize to avoid OOM on large files
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, opts);

            opts.inSampleSize    = calcSampleSize(opts.outWidth, opts.outHeight, maxPx);
            opts.inJustDecodeBounds = false;

            try (InputStream in2 = ctx.getContentResolver().openInputStream(uri)) {
                if (in2 == null) return null;
                Bitmap bmp = BitmapFactory.decodeStream(in2, null, opts);
                if (bmp == null) return null;

                // Resize agar abhi bhi bada hai
                bmp = resizeIfNeeded(bmp, maxPx);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Bitmap.CompressFormat fmt;
                if (forceWebP || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fmt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? Bitmap.CompressFormat.WEBP_LOSSY
                        : Bitmap.CompressFormat.WEBP;
                } else {
                    fmt = Bitmap.CompressFormat.JPEG;
                }
                bmp.compress(fmt, quality, out);
                bmp.recycle();
                return out.toByteArray();
            }
        } catch (IOException e) {
            Log.e(TAG, "Image compress failed", e);
            return null;
        }
    }

    /** Async image compress — callback on main thread */
    public static void compressImageAsync(Context ctx, Uri uri,
                                           int maxPx, int quality,
                                           ImageCallback cb) {
        sPool.execute(() -> {
            byte[] result = compressImageWithQuality(ctx, uri, maxPx, quality, false);
            sMain.post(() -> {
                if (result != null) cb.onDone(result);
                else cb.onError("Image compression failed");
            });
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // VIDEO — REDIRECT TO SERVER-SIDE VideoCompressor
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * @deprecated Server-side compression use karo.
     *
     * Agar purana code is method ko call karta hai to compile error aaye.
     * Replace: VideoCompressor.compress(ctx, uri, quality, callback)
     *
     * Ye stub sirf backward compatibility ke liye hai taaki compile ho sake.
     * Runtime pe ye sirf error callback dega.
     */
    @Deprecated
    public static void compressVideoAsync(Context ctx, Uri uri, VideoCallback cb) {
        // Video ab server pe compress hota hai.
        // VideoCompressor.compress(ctx, uri, callback) use karo.
        sMain.post(() -> cb.onError(
            "MediaCompressor.compressVideoAsync deprecated. " +
            "VideoCompressor.compress() use karo — server-side compression."));
    }

    // ── Bitmap helpers ────────────────────────────────────────────────────────────

    private static int calcSampleSize(int w, int h, int maxPx) {
        int sample = 1;
        while (w / (sample * 2) >= maxPx && h / (sample * 2) >= maxPx) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap resizeIfNeeded(Bitmap bmp, int maxPx) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= maxPx && h <= maxPx) return bmp;
        float ratio = Math.min((float) maxPx / w, (float) maxPx / h);
        Bitmap scaled = Bitmap.createScaledBitmap(bmp,
            Math.round(w * ratio), Math.round(h * ratio), true);
        bmp.recycle();
        return scaled;
    }
}
