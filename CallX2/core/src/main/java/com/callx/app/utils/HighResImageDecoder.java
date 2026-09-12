package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * HighResImageDecoder — safe decode path for outlier-sized images
 * (panoramas, scanned documents, high-res camera originals) opened in the
 * full-screen media viewer.
 *
 * WHY: Glide's normal decode already downsamples using inSampleSize before
 * allocating a Bitmap, which is fine for ordinary chat photos. But a small
 * minority of images are genuinely huge (10000x5000 panorama, 8000x8000
 * scan) — for those, reading dimensions and decoding directly via
 * BitmapRegionDecoder at a safe target resolution is a more
 * predictable/OOM-resistant path, since BitmapRegionDecoder never needs to
 * materialize the full-resolution image at any point, even transiently.
 *
 * SCOPE (intentionally limited): this decodes the *whole* image once, at a
 * safe sample size, for display — it is NOT a tile-based/pixel-level
 * zoom-in system. Real tile-by-tile region paging on pinch-zoom (decoding
 * only the visible crop at full resolution as the user zooms deeper into a
 * panorama) would need a dedicated tiled view (a SubsamplingScaleImageView
 * -style widget), since PhotoView's matrix assumes the whole image is
 * already sitting in its drawable and swapping in a partial crop bitmap
 * would desync pan/zoom coordinates. That's a reasonable future follow-up,
 * not attempted here.
 */
public final class HighResImageDecoder {

    private static final String TAG = "HighResImageDecoder";

    // ~24 total megapixels (e.g. 6000x4000 and up) is comfortably above
    // what a normal camera/chat photo needs, so this path only engages for
    // genuine panoramas/scans, not everyday images.
    private static final long OVERSIZED_PIXEL_THRESHOLD = 24_000_000L;

    private HighResImageDecoder() {}

    public static final class Dimensions {
        public final int width;
        public final int height;
        Dimensions(int w, int h) { this.width = w; this.height = h; }
    }

    /** Cheap bounds-only read (inJustDecodeBounds — no pixel allocation). */
    public static Dimensions readDimensions(File file) {
        if (file == null || !file.exists()) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream in = new FileInputStream(file)) {
            BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException e) {
            Log.w(TAG, "readDimensions failed for " + file.getName() + ": " + e.getMessage());
            return null;
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
        return new Dimensions(opts.outWidth, opts.outHeight);
    }

    /** True if this file is large enough to route through decodeSafely() instead of Glide's default path. */
    public static boolean isOversized(File file) {
        Dimensions d = readDimensions(file);
        return d != null && (long) d.width * (long) d.height >= OVERSIZED_PIXEL_THRESHOLD;
    }

    /**
     * Decodes the full image at the largest sample size that still covers
     * (reqWidth, reqHeight), via BitmapRegionDecoder — the full-resolution
     * source is never allocated, only the already-downsampled output.
     * Returns null on any failure; caller should fall back to Glide.
     * Call off the main thread — this does file I/O + decode work.
     */
    public static Bitmap decodeSafely(File file, int reqWidth, int reqHeight) {
        if (file == null || !file.exists()) return null;
        Dimensions dims = readDimensions(file);
        if (dims == null) return null;

        int sampleSize = calculateInSampleSize(dims.width, dims.height, reqWidth, reqHeight);

        BitmapRegionDecoder decoder = null;
        try {
            decoder = openRegionDecoder(file);
            if (decoder == null) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Rect full = new Rect(0, 0, dims.width, dims.height);
            return decoder.decodeRegion(full, opts);
        } catch (Exception e) {
            Log.w(TAG, "decodeSafely failed for " + file.getName() + ": " + e.getMessage());
            return null;
        } finally {
            if (decoder != null) decoder.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private static BitmapRegionDecoder openRegionDecoder(File file) throws IOException {
        if (Build.VERSION.SDK_INT >= 31) {
            return BitmapRegionDecoder.newInstance(file.getAbsolutePath());
        }
        try (InputStream in = new FileInputStream(file)) {
            return BitmapRegionDecoder.newInstance(in, false);
        }
    }

    private static int calculateInSampleSize(int rawW, int rawH, int reqW, int reqH) {
        int sampleSize = 1;
        if (reqW <= 0 || reqH <= 0) return sampleSize;
        if (rawH > reqH || rawW > reqW) {
            int halfH = rawH / 2;
            int halfW = rawW / 2;
            while ((halfH / sampleSize) >= reqH && (halfW / sampleSize) >= reqW) {
                sampleSize *= 2;
            }
        }
        return sampleSize;
    }
}
