package com.callx.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
 * FLOW: Original → EXIF fix → Resize → WebP compress
 *   ├── Thumbnail  (~30KB,  200×200px)    ← chat list / quick preview
 *   └── Full image (~400KB, max 1280px)   ← full view
 *
 * Usage:
 *   ImageCompressor.compress(ctx, uri, new Callback() {
 *       public void onSuccess(Result r) { upload(r); }
 *       public void onError(Exception e) { showError(); }
 *   });
 */
public class ImageCompressor {

    private static final String TAG = "ImageCompressor";

    // ── Config ─────────────────────────────────────────────────────────────
    private static final int  FULL_MAX_WIDTH   = 1280;
    private static final int  FULL_MAX_HEIGHT  = 1920;
    private static final int  THUMB_SIZE       = 200;     // square thumbnail px
    private static final int  FULL_QUALITY     = 80;      // WebP quality
    private static final int  THUMB_QUALITY    = 65;
    private static final long FULL_TARGET_BYTES  = 800_000L; // 800 KB max
    private static final long THUMB_TARGET_BYTES =  50_000L; //  50 KB max

    private static final ExecutorService BG = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ── Public types ───────────────────────────────────────────────────────

    public interface Callback {
        void onSuccess(Result result);
        void onError(Exception e);
    }

    public static class Result {
        /** ~30KB thumbnail — used in chat list and as placeholder */
        public final File thumbFile;
        /** ~400KB full image — uploaded to Cloudinary */
        public final File fullFile;
        public final long originalBytes;
        public final long thumbBytes;
        public final long fullBytes;

        public Result(File thumb, File full, long orig, long thumbB, long fullB) {
            this.thumbFile     = thumb;
            this.fullFile      = full;
            this.originalBytes = orig;
            this.thumbBytes    = thumbB;
            this.fullBytes     = fullB;
        }

        public String summary() {
            return String.format("%.1fMB → thumb %.0fKB + full %.0fKB",
                originalBytes / 1_000_000f, thumbBytes / 1000f, fullBytes / 1000f);
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Compress on background thread, callback on main thread */
    public static void compress(Context ctx, Uri imageUri, Callback callback) {
        BG.execute(() -> {
            try {
                Result r = compressSync(ctx, imageUri);
                MAIN.post(() -> callback.onSuccess(r));
            } catch (Exception e) {
                Log.e(TAG, "Compression failed", e);
                MAIN.post(() -> callback.onError(e));
            }
        });
    }

    // ── Core (background thread) ───────────────────────────────────────────

    public static Result compressSync(Context ctx, Uri uri) throws IOException {
        long originalBytes = getUriSize(ctx, uri);

        // 1. Memory-safe decode (avoid OOM on huge images)
        Bitmap bmp = decodeSampled(ctx, uri, FULL_MAX_WIDTH, FULL_MAX_HEIGHT);

        // 2. Fix EXIF rotation (selfies often come sideways)
        bmp = fixExifRotation(ctx, uri, bmp);

        // 3. Full image: resize + compress
        Bitmap fullBmp  = resize(bmp, FULL_MAX_WIDTH, FULL_MAX_HEIGHT);
        File   fullFile = writeWebP(ctx, fullBmp, "full_", FULL_QUALITY, FULL_TARGET_BYTES);

        // 4. Thumbnail: center-crop square + compress
        Bitmap thumbBmp  = centerCropSquare(bmp, THUMB_SIZE);
        File   thumbFile = writeWebP(ctx, thumbBmp, "thumb_", THUMB_QUALITY, THUMB_TARGET_BYTES);

        // 5. Cleanup
        if (bmp != fullBmp)  bmp.recycle();
        if (fullBmp != thumbBmp) fullBmp.recycle();
        thumbBmp.recycle();

        Result r = new Result(thumbFile, fullFile, originalBytes,
            thumbFile.length(), fullFile.length());
        Log.i(TAG, r.summary());
        return r;
    }

    // ── Step 1: Memory-safe decode ─────────────────────────────────────────

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
            if (bmp == null) throw new IOException("Failed to decode bitmap");
            return bmp;
        }
    }

    private static int calcSampleSize(int w, int h, int reqW, int reqH) {
        int sample = 1;
        if (h > reqH || w > reqW) {
            int halfH = h / 2, halfW = w / 2;
            while ((halfH / sample) >= reqH && (halfW / sample) >= reqW) sample *= 2;
        }
        return sample;
    }

    // ── Step 2: EXIF rotation fix ──────────────────────────────────────────

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
            Bitmap rotated = Bitmap.createBitmap(src, 0, 0,
                src.getWidth(), src.getHeight(), m, true);
            src.recycle();
            return rotated;
        } catch (Exception e) {
            Log.w(TAG, "EXIF fix skipped: " + e.getMessage());
            return src;
        }
    }

    // ── Step 3: Resize keeping aspect ratio ───────────────────────────────

    private static Bitmap resize(Bitmap src, int maxW, int maxH) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxW && h <= maxH) return src;
        float scale = Math.min((float) maxW / w, (float) maxH / h);
        int nw = Math.round(w * scale), nh = Math.round(h * scale);
        Bitmap out = Bitmap.createScaledBitmap(src, nw, nh, true);
        if (out != src) src.recycle();
        return out;
    }

    // ── Step 4: Center-crop square thumbnail ──────────────────────────────

    private static Bitmap centerCropSquare(Bitmap src, int size) {
        int w = src.getWidth(), h = src.getHeight();
        int min = Math.min(w, h);
        int x = (w - min) / 2, y = (h - min) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, min, min);
        Bitmap scaled  = Bitmap.createScaledBitmap(cropped, size, size, true);
        if (cropped != scaled) cropped.recycle();
        return scaled;
    }

    // ── Step 5: WebP write with adaptive quality ──────────────────────────

    private static File writeWebP(Context ctx, Bitmap bmp, String prefix,
                                  int quality, long targetBytes) throws IOException {
        File dir = new File(ctx.getCacheDir(), "img_compress");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, prefix + UUID.randomUUID() + ".webp");

        int q = quality;
        for (int tries = 0; tries < 5; tries++) {
            try (FileOutputStream fos = new FileOutputStream(out)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, fos);
                } else {
                    //noinspection deprecation
                    bmp.compress(Bitmap.CompressFormat.WEBP, q, fos);
                }
            }
            if (out.length() <= targetBytes || q <= 30) break;
            q -= 10;
        }
        Log.d(TAG, prefix + out.length() / 1000 + "KB (q=" + q + ")");
        return out;
    }

    // ── Util ──────────────────────────────────────────────────────────────

    private static long getUriSize(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            return in != null ? in.available() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
