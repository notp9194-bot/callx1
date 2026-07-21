package com.callx.app.utils;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Executors;

/**
 * MediaSaveHelper — Save received chat media to the device gallery / Downloads.
 *
 * WhatsApp-style: images go to Pictures/CallX, videos to Movies/CallX.
 * Works on API 29+ via MediaStore and pre-29 via direct File copy.
 *
 * Usage:
 *   MediaSaveHelper.save(ctx, cachedFile, "image", url, new MediaSaveHelper.Callback() { ... });
 */
public final class MediaSaveHelper {

    private static final String TAG      = "MediaSaveHelper";
    private static final String APP_NAME = "CallX";

    private MediaSaveHelper() {}

    public interface Callback {
        void onSaved(Uri savedUri);
        void onError(String reason);
    }

    /**
     * Saves a locally-cached file into the device gallery.
     *
     * @param ctx       Application context.
     * @param srcFile   The cached file (from MediaCache.getCached / getWithProgress).
     * @param mediaType "image" | "video" | "gif"
     * @param sourceUrl Original URL — used to derive a sensible filename.
     * @param cb        Result callback, always fired on the main thread.
     */
    public static void save(Context ctx, File srcFile, String mediaType,
                            String sourceUrl, Callback cb) {
        if (srcFile == null || !srcFile.exists()) {
            post(cb, () -> cb.onError("File not available — download it first"));
            return;
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Uri uri = saveInternal(ctx, srcFile, mediaType, sourceUrl);
                post(cb, () -> cb.onSaved(uri));
            } catch (Exception e) {
                Log.e(TAG, "Save failed", e);
                post(cb, () -> cb.onError(e.getMessage() != null ? e.getMessage() : "Save failed"));
            }
        });
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private static Uri saveInternal(Context ctx, File src, String mediaType,
                                    String sourceUrl) throws IOException {
        String mimeType  = mimeFor(mediaType);
        String fileName  = fileNameFor(sourceUrl, mediaType);
        boolean isVideo  = "video".equals(mediaType);
        boolean isImage  = !isVideo;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // ── API 29+: MediaStore ─────────────────────────────────────
            Uri collection = isVideo
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    isVideo
                            ? Environment.DIRECTORY_MOVIES + "/" + APP_NAME
                            : Environment.DIRECTORY_PICTURES + "/" + APP_NAME);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri dest = ctx.getContentResolver().insert(collection, cv);
            if (dest == null) throw new IOException("MediaStore insert returned null");

            try (OutputStream os = ctx.getContentResolver().openOutputStream(dest);
                 FileInputStream fis = new FileInputStream(src)) {
                if (os == null) throw new IOException("Could not open output stream");
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
            }

            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(dest, cv, null, null);
            return dest;

        } else {
            // ── Pre-29: direct file copy ────────────────────────────────
            File dir = new File(
                    isVideo
                            ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                            : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    APP_NAME);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dest = new File(dir, fileName);

            try (FileInputStream fis = new FileInputStream(src);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
                fos.getFD().sync();
            }

            // Notify MediaScanner so it appears in Gallery immediately
            android.media.MediaScannerConnection.scanFile(
                    ctx,
                    new String[]{dest.getAbsolutePath()},
                    new String[]{mimeType},
                    null);
            return Uri.fromFile(dest);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String mimeFor(String mediaType) {
        switch (mediaType != null ? mediaType : "") {
            case "video": return "video/mp4";
            case "gif":   return "image/gif";
            default:      return "image/jpeg";
        }
    }

    private static String fileNameFor(String url, String mediaType) {
        // Try to extract original filename from URL path
        if (url != null && !url.isEmpty()) {
            try {
                String path = new java.net.URL(url).getPath();
                int slash = path.lastIndexOf('/');
                if (slash >= 0 && slash < path.length() - 1) {
                    String name = path.substring(slash + 1);
                    // Strip query string if any
                    int q = name.indexOf('?');
                    if (q >= 0) name = name.substring(0, q);
                    if (name.length() > 3) return name;
                }
            } catch (Exception ignored) {}
        }
        // Fallback: timestamp-based name
        String ext;
        switch (mediaType != null ? mediaType : "") {
            case "video": ext = ".mp4"; break;
            case "gif":   ext = ".gif"; break;
            default:      ext = ".jpg"; break;
        }
        return APP_NAME.toLowerCase() + "_" + System.currentTimeMillis() + ext;
    }

    private static void post(Callback cb, Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
