package com.callx.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * StatusMediaUploader — Compress + upload image/video/GIF for statuses.
 *
 * Image flow:
 *   Pick image → compress to WebP (thumb ~30KB + full ~600KB) → upload both
 *   → statusItem.thumbnailUrl + statusItem.mediaUrl
 *
 * Video flow:
 *   Pick video → generate thumb frame → compress → upload
 *   → statusItem.thumbnailUrl (frame) + statusItem.mediaUrl (video)
 *
 * GIF: upload as-is (GIFs don't compress well), generate frame thumb.
 *
 * All work is off the main thread via ExecutorService.
 */
public class StatusMediaUploader {

    private static final int THUMB_SIZE_PX  = 300;
    private static final int FULL_MAX_PX    = 1280;
    private static final int THUMB_QUALITY  = 60;
    private static final int FULL_QUALITY   = 80;
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024; // 5MB for images

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Context ctx;

    public StatusMediaUploader(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ── Upload image ──────────────────────────────────────────────────────
    public void uploadImage(Uri imageUri, UploadCallback callback) {
        executor.execute(() -> {
            try {
                callback.onProgress(5, "Compressing image…");

                // 1. Load bitmap with EXIF correction
                Bitmap original = loadAndCorrectExif(imageUri);
                if (original == null) {
                    callback.onError("Failed to load image");
                    return;
                }
                callback.onProgress(20, "Compressing image…");

                // 2. Generate thumbnail
                File thumbFile = compressBitmap(original, THUMB_SIZE_PX, THUMB_QUALITY,
                    "status_thumb_" + UUID.randomUUID() + ".webp");
                callback.onProgress(35, "Uploading thumbnail…");

                // 3. Generate full-res
                File fullFile = compressBitmap(original, FULL_MAX_PX, FULL_QUALITY,
                    "status_full_" + UUID.randomUUID() + ".webp");
                original.recycle();
                callback.onProgress(50, "Uploading image…");

                // 4. Upload thumbnail
                String thumbUrl = CloudinaryUploader.uploadFile(thumbFile, "status/thumb");
                callback.onProgress(70, "Uploading full image…");

                // 5. Upload full
                String fullUrl = CloudinaryUploader.uploadFile(fullFile, "status/image");
                callback.onProgress(95, "Finalizing…");

                // 6. Cleanup temp files
                thumbFile.delete();
                fullFile.delete();

                callback.onSuccess(fullUrl, thumbUrl, "image");

            } catch (Exception e) {
                callback.onError("Upload failed: " + e.getMessage());
            }
        });
    }

    // ── Upload video ──────────────────────────────────────────────────────
    public void uploadVideo(Uri videoUri, UploadCallback callback) {
        executor.execute(() -> {
            try {
                callback.onProgress(5, "Preparing video…");

                // 1. Copy to local file
                File videoFile = copyUriToFile(videoUri,
                    "status_video_" + UUID.randomUUID() + ".mp4");
                if (videoFile == null) {
                    callback.onError("Failed to access video");
                    return;
                }
                callback.onProgress(20, "Generating thumbnail…");

                // 2. Generate thumbnail from first frame
                Bitmap frameBitmap = extractVideoFrame(videoUri);
                String thumbUrl = "";
                if (frameBitmap != null) {
                    File thumbFile = compressBitmap(frameBitmap, THUMB_SIZE_PX, THUMB_QUALITY,
                        "status_vthumb_" + UUID.randomUUID() + ".webp");
                    frameBitmap.recycle();
                    thumbUrl = CloudinaryUploader.uploadFile(thumbFile, "status/thumb");
                    thumbFile.delete();
                }
                callback.onProgress(50, "Uploading video…");

                // 3. Upload video
                String videoUrl = CloudinaryUploader.uploadFile(videoFile, "status/video");
                videoFile.delete();
                callback.onProgress(95, "Finalizing…");

                callback.onSuccess(videoUrl, thumbUrl, "video");

            } catch (Exception e) {
                callback.onError("Video upload failed: " + e.getMessage());
            }
        });
    }

    // ── Upload GIF ────────────────────────────────────────────────────────
    public void uploadGif(Uri gifUri, UploadCallback callback) {
        executor.execute(() -> {
            try {
                callback.onProgress(10, "Loading GIF…");
                File gifFile = copyUriToFile(gifUri,
                    "status_gif_" + UUID.randomUUID() + ".gif");
                if (gifFile == null) { callback.onError("Failed to load GIF"); return; }
                callback.onProgress(40, "Uploading GIF…");
                String gifUrl = CloudinaryUploader.uploadFile(gifFile, "status/gif");
                gifFile.delete();

                // Thumb from first frame
                Bitmap frame = BitmapFactory.decodeFile(gifFile.getAbsolutePath());
                String thumbUrl = "";
                if (frame != null) {
                    File tf = compressBitmap(frame, THUMB_SIZE_PX, THUMB_QUALITY,
                        "status_gifthumb_" + UUID.randomUUID() + ".webp");
                    frame.recycle();
                    thumbUrl = CloudinaryUploader.uploadFile(tf, "status/thumb");
                    tf.delete();
                }
                callback.onProgress(95, "Finalizing…");
                callback.onSuccess(gifUrl, thumbUrl, "gif");

            } catch (Exception e) {
                callback.onError("GIF upload failed: " + e.getMessage());
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private Bitmap loadAndCorrectExif(Uri uri) {
        try {
            InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return null;

            // Read dimensions without loading full bitmap (OOM protection)
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            byte[] raw = toByteArray(is);
            is.close();
            BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);

            int sampleSize = 1;
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            if (maxDim > FULL_MAX_PX * 2) {
                sampleSize = maxDim / (FULL_MAX_PX * 2);
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);

            // EXIF rotation fix
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                InputStream exifIs = ctx.getContentResolver().openInputStream(uri);
                if (exifIs != null) {
                    ExifInterface exif = new ExifInterface(exifIs);
                    exifIs.close();
                    int orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                    bmp = rotateBitmap(bmp, orientation);
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap rotateBitmap(Bitmap bmp, int orientation) {
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:  matrix.postRotate(90);  break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:   matrix.postScale(1, -1); break;
            default: return bmp;
        }
        try {
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0,
                bmp.getWidth(), bmp.getHeight(), matrix, true);
            bmp.recycle();
            return rotated;
        } catch (OutOfMemoryError e) {
            return bmp;
        }
    }

    private File compressBitmap(Bitmap bmp, int maxSizePx, int quality, String filename)
            throws IOException {
        // Scale down if needed
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w > maxSizePx || h > maxSizePx) {
            float ratio = (float) maxSizePx / Math.max(w, h);
            int nw = Math.round(w * ratio), nh = Math.round(h * ratio);
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        File out = new File(ctx.getCacheDir(), filename);
        FileOutputStream fos = new FileOutputStream(out);
        Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ? Bitmap.CompressFormat.WEBP_LOSSY
            : Bitmap.CompressFormat.WEBP;
        // Adaptive quality: reduce if still too big
        int q = quality;
        bmp.compress(fmt, q, fos);
        fos.close();
        while (out.length() > MAX_FILE_SIZE && q > 40) {
            q -= 10;
            fos = new FileOutputStream(out);
            bmp.compress(fmt, q, fos);
            fos.close();
        }
        return out;
    }

    private File copyUriToFile(Uri uri, String filename) {
        try {
            File out = new File(ctx.getCacheDir(), filename);
            InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            is.close(); fos.close();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap extractVideoFrame(Uri videoUri) {
        try {
            android.media.MediaMetadataRetriever mmr =
                new android.media.MediaMetadataRetriever();
            mmr.setDataSource(ctx, videoUri);
            Bitmap frame = mmr.getFrameAtTime(0,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            mmr.release();
            return frame;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] toByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
        return bos.toByteArray();
    }

    // ── Callback ──────────────────────────────────────────────────────────
    public interface UploadCallback {
        /** @param mediaUrl    full-res URL
         *  @param thumbUrl    thumbnail URL
         *  @param mediaType   "image"|"video"|"gif" */
        void onSuccess(String mediaUrl, String thumbUrl, String mediaType);
        void onProgress(int percent, String message);
        void onError(String message);
    }

    // ── Stub — replace with your real Cloudinary/Firebase Storage uploader ─
    private static class CloudinaryUploader {
        static String uploadFile(File file, String folder) throws Exception {
            // TODO: integrate with your Cloudinary SDK or Firebase Storage
            // Example: CloudinaryManager.upload(file, folder)
            throw new UnsupportedOperationException(
                "Replace CloudinaryUploader.uploadFile with your real uploader");
        }
    }
}
