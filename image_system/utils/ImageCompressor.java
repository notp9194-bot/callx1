package com.callx.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ImageCompressor — WhatsApp-level image compression
 *
 * FLOW:
 *   Original (any size) → EXIF fix → Resize → WebP compress → Two outputs
 *   ├── Thumbnail  (~30KB,  max 200×200)
 *   └── Full image (~500KB, max 1280px)
 *
 * Usage:
 *   ImageCompressor.compress(ctx, uri, new ImageCompressor.Callback() {
 *       public void onSuccess(Result r) { upload(r.thumbFile, r.fullFile); }
 *       public void onError(Exception e) { showError(); }
 *   });
 */
public class ImageCompressor {

    private static final String TAG = "ImageCompressor";

    // ── Config ────────────────────────────────────────────────────────────
    static final int FULL_MAX_WIDTH  = 1280;
    static final int FULL_MAX_HEIGHT = 1920;
    static final int THUMB_MAX_SIZE  = 200;   // px — square crop for thumbnail

    static final int FULL_QUALITY  = 80;      // WebP quality 0-100
    static final int THUMB_QUALITY = 70;

    static final long FULL_TARGET_BYTES  = 800_000L;  // 800 KB max
    static final long THUMB_TARGET_BYTES =  50_000L;  //  50 KB max

    // Single background thread — no UI freeze
    private static final ExecutorService BG = Executors.newSingleThreadExecutor();

    // ─────────────────────────────────────────────────────────────────────

    public interface Callback {
        void onSuccess(Result result);
        void onError(Exception e);
    }

    public static class Result {
        public final File thumbFile;    // small thumbnail, ~30 KB
        public final File fullFile;     // full compressed image, ~300-800 KB
        public final long originalBytes;
        public final long thumbBytes;
        public final long fullBytes;

        Result(File thumb, File full, long orig, long thumbB, long fullB) {
            this.thumbFile     = thumb;
            this.fullFile      = full;
            this.originalBytes = orig;
            this.thumbBytes    = thumbB;
            this.fullBytes     = fullB;
        }

        public String compressionSummary() {
            return String.format("%.1fMB → thumb %.1fKB + full %.1fKB",
                originalBytes / 1_000_000f,
                thumbBytes    / 1_000f,
                fullBytes     / 1_000f);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Compress on background thread, callback on main thread */
    public static void compress(Context ctx, Uri imageUri, Callback callback) {
        BG.execute(() -> {
            try {
                Result result = compressSync(ctx, imageUri);
                android.os.Handler main = new android.os.Handler(
                    android.os.Looper.getMainLooper());
                main.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                Log.e(TAG, "Compression failed", e);
                android.os.Handler main = new android.os.Handler(
                    android.os.Looper.getMainLooper());
                main.post(() -> callback.onError(e));
            }
        });
    }

    // ── Core logic (runs on background thread) ────────────────────────────

    static Result compressSync(Context ctx, Uri imageUri) throws IOException {
        // 1. Get original file size
        long originalBytes = getUriSize(ctx, imageUri);

        // 2. Decode with inSampleSize (memory safe — no full bitmap in RAM)
        Bitmap original = decodeSampled(ctx, imageUri, FULL_MAX_WIDTH, FULL_MAX_HEIGHT);

        // 3. Fix EXIF rotation (image ulta nahi aayega)
        original = fixExifRotation(ctx, imageUri, original);

        // 4. Generate full-size compressed
        Bitmap fullBitmap = resize(original, FULL_MAX_WIDTH, FULL_MAX_HEIGHT);
        File   fullFile   = writeWebP(ctx, fullBitmap, "full", FULL_QUALITY, FULL_TARGET_BYTES);

        // 5. Generate thumbnail (center crop square)
        Bitmap thumbBitmap = centerCrop(original, THUMB_MAX_SIZE);
        File   thumbFile   = writeWebP(ctx, thumbBitmap, "thumb", THUMB_QUALITY, THUMB_TARGET_BYTES);

        // 6. Cleanup
        original.recycle();
        fullBitmap.recycle();
        thumbBitmap.recycle();

        Log.i(TAG, "Done: " + new Result(thumbFile, fullFile,
            originalBytes, thumbFile.length(), fullFile.length()).compressionSummary());

        return new Result(thumbFile, fullFile, originalBytes,
            thumbFile.length(), fullFile.length());
    }

    // ── Step 1: Memory-safe decode ────────────────────────────────────────

    private static Bitmap decodeSampled(Context ctx, Uri uri, int reqW, int reqH)
        throws IOException {

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, opts);
        }

        opts.inSampleSize    = calcSampleSize(opts.outWidth, opts.outHeight, reqW, reqH);
        opts.inJustDecodeBounds = false;
        opts.inPreferredConfig  = Bitmap.Config.ARGB_8888;

        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
            if (bmp == null) throw new IOException("Failed to decode bitmap from URI");
            return bmp;
        }
    }

    private static int calcSampleSize(int w, int h, int reqW, int reqH) {
        int sample = 1;
        if (h > reqH || w > reqW) {
            int halfH = h / 2;
            int halfW = w / 2;
            while ((halfH / sample) >= reqH && (halfW / sample) >= reqW) {
                sample *= 2;
            }
        }
        return sample;
    }

    // ── Step 2: Fix EXIF rotation ─────────────────────────────────────────

    private static Bitmap fixExifRotation(Context ctx, Uri uri, Bitmap src) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return src;
            ExifInterface exif = new ExifInterface(in);
            int orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);

            float degrees = 0f;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  degrees =  90f; break;
                case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180f; break;
                case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270f; break;
            }

            if (degrees == 0f) return src;

            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            src.recycle();
            return rotated;

        } catch (Exception e) {
            Log.w(TAG, "EXIF fix skipped: " + e.getMessage());
            return src;
        }
    }

    // ── Step 3: Resize (keep aspect ratio) ────────────────────────────────

    static Bitmap resize(Bitmap src, int maxW, int maxH) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxW && h <= maxH) return src;

        float scale = Math.min((float) maxW / w, (float) maxH / h);
        int newW = Math.round(w * scale);
        int newH = Math.round(h * scale);

        Bitmap scaled = Bitmap.createScaledBitmap(src, newW, newH, true);
        if (scaled != src) src.recycle();
        return scaled;
    }

    // ── Step 4: Center crop square (for thumbnail) ─────────────────────────

    private static Bitmap centerCrop(Bitmap src, int size) {
        int w = src.getWidth(), h = src.getHeight();
        int min = Math.min(w, h);
        int x = (w - min) / 2;
        int y = (h - min) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, min, min);
        return Bitmap.createScaledBitmap(cropped, size, size, true);
    }

    // ── Step 5: WebP compress with adaptive quality ────────────────────────

    static File writeWebP(Context ctx, Bitmap bmp, String prefix,
                          int quality, long targetBytes) throws IOException {

        File dir  = new File(ctx.getCacheDir(), "img_compress");
        dir.mkdirs();
        File out  = new File(dir, prefix + "_" + UUID.randomUUID() + ".webp");

        // Try target quality; if too big → reduce quality adaptively
        int  q     = quality;
        int  tries = 0;

        while (tries < 5) {
            try (FileOutputStream fos = new FileOutputStream(out)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, fos);
                } else {
                    bmp.compress(Bitmap.CompressFormat.WEBP, q, fos);
                }
            }
            if (out.length() <= targetBytes || q <= 30) break;
            q  -= 10;
            tries++;
        }

        Log.d(TAG, prefix + " file: " + out.length() / 1000 + "KB (q=" + q + ")");
        return out;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static long getUriSize(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            return in != null ? in.available() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
