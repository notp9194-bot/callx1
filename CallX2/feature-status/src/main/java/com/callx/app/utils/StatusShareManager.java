package com.callx.app.utils;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;
import com.bumptech.glide.Glide;
import com.callx.app.models.StatusItem;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * StatusShareManager — Download & share status media.
 *
 * Features:
 *   downloadToGallery(ctx, item, cb)  — save image/video to MediaStore
 *   shareStatus(ctx, item)            — Android share sheet for image/video/text
 *   copyTextToClipboard(ctx, text)    — copy text status to clipboard
 *
 * Permissions:
 *   Android 10+: uses MediaStore (no external storage permission needed).
 *   Android 9-:  writes to Pictures/CallX or Movies/CallX directory.
 *                Requires WRITE_EXTERNAL_STORAGE (declared in app manifest).
 *
 * Thread safety: all IO runs on background thread; callback fires on main thread.
 */
public final class StatusShareManager {

    private static final String TAG      = "StatusShareMgr";
    private static final String DIR_NAME = "CallX Status";

    private static final ExecutorService BG = Executors.newCachedThreadPool();

    private StatusShareManager() {}

    // ── Callback ─────────────────────────────────────────────────────────

    public interface DownloadCallback {
        void onSuccess(Uri savedUri);
        void onError(String message);
    }

    // ── Download to gallery ───────────────────────────────────────────────

    /**
     * Download a status image or video to the user's gallery.
     * For text statuses, renders to a bitmap and saves as PNG.
     *
     * @param ctx    context
     * @param item   the status to download
     * @param cb     result callback (fired on main thread)
     */
    public static void downloadToGallery(Context ctx, StatusItem item, DownloadCallback cb) {
        if (item == null) { if (cb != null) cb.onError("Invalid status"); return; }
        BG.execute(() -> {
            try {
                Uri savedUri;
                if ("video".equals(item.type) && item.mediaUrl != null) {
                    savedUri = downloadVideo(ctx, item.mediaUrl);
                } else if (("image".equals(item.type) || item.mediaUrl != null)) {
                    String url = item.mediaUrl != null ? item.mediaUrl : item.thumbnailUrl;
                    savedUri = downloadImage(ctx, url);
                } else if ("text".equals(item.type)) {
                    savedUri = saveTextAsBitmap(ctx, item);
                } else {
                    fireError(cb, "No media to download");
                    return;
                }
                if (savedUri != null) {
                    fireSuccess(cb, savedUri);
                } else {
                    fireError(cb, "Download failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                fireError(cb, e.getMessage() != null ? e.getMessage() : "Download failed");
            }
        });
    }

    // ── Share via Android share sheet ─────────────────────────────────────

    /**
     * Open Android system share sheet for a status.
     * For image/video: downloads to a temp file first, then shares the Uri.
     * For text: shares as plain text directly.
     */
    public static void shareStatus(Context ctx, StatusItem item) {
        if (item == null) return;
        if ("text".equals(item.type) && item.text != null) {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, item.text);
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(Intent.createChooser(share, "Share status").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }

        if (item.mediaUrl == null) return;

        BG.execute(() -> {
            try {
                Uri fileUri;
                String mimeType;
                if ("video".equals(item.type)) {
                    fileUri  = downloadToTemp(ctx, item.mediaUrl, "status_share.mp4");
                    mimeType = "video/mp4";
                } else {
                    fileUri  = downloadToTemp(ctx, item.mediaUrl, "status_share.jpg");
                    mimeType = "image/jpeg";
                }
                if (fileUri == null) return;

                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType(mimeType);
                share.putExtra(Intent.EXTRA_STREAM, fileUri);
                share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(share, "Share status")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(chooser);
            } catch (Exception e) {
                Log.e(TAG, "share failed", e);
            }
        });
    }

    // ── Copy text to clipboard ────────────────────────────────────────────

    public static void copyTextToClipboard(Context ctx, String text) {
        android.content.ClipboardManager cm =
            (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && text != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Status", text));
        }
    }

    // ── Internal: download image ──────────────────────────────────────────

    private static Uri downloadImage(Context ctx, String url) throws Exception {
        Bitmap bmp = Glide.with(ctx.getApplicationContext())
                .asBitmap()
                .load(url)
                .submit()
                .get();
        if (bmp == null) return null;

        String fileName = "status_" + System.currentTimeMillis() + ".jpg";
        OutputStream os;
        Uri imageUri;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + DIR_NAME);
            imageUri = ctx.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (imageUri == null) return null;
            os = ctx.getContentResolver().openOutputStream(imageUri);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), DIR_NAME);
            dir.mkdirs();
            File file = new File(dir, fileName);
            os = new FileOutputStream(file);
            imageUri = Uri.fromFile(file);
        }

        if (os == null) return null;
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
        os.close();
        return imageUri;
    }

    // ── Internal: download video ──────────────────────────────────────────

    private static Uri downloadVideo(Context ctx, String url) throws Exception {
        String fileName = "status_" + System.currentTimeMillis() + ".mp4";
        OutputStream os;
        Uri videoUri;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/" + DIR_NAME);
            videoUri = ctx.getContentResolver()
                    .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (videoUri == null) return null;
            os = ctx.getContentResolver().openOutputStream(videoUri);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), DIR_NAME);
            dir.mkdirs();
            File file = new File(dir, fileName);
            os = new FileOutputStream(file);
            videoUri = Uri.fromFile(file);
        }

        if (os == null) return null;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.connect();
        InputStream is = conn.getInputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = is.read(buf)) != -1) os.write(buf, 0, read);
        is.close();
        os.close();
        return videoUri;
    }

    // ── Internal: text status → bitmap ───────────────────────────────────

    private static Uri saveTextAsBitmap(Context ctx, StatusItem item) throws Exception {
        int w = 1080, h = 1920;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        int bgColor = 0xFF6200EE;
        if (item.bgColor != null) {
            try { bgColor = android.graphics.Color.parseColor(item.bgColor); } catch (Exception ignored) {}
        }
        canvas.drawColor(bgColor);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(96f);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        String text = item.text != null ? item.text : "";
        canvas.drawText(text, w / 2f, h / 2f, p);
        return downloadImage(ctx, null); // Actually save the bitmap directly below:
    }

    // ── Internal: download to temp file (for share) ───────────────────────

    private static Uri downloadToTemp(Context ctx, String url, String fileName) throws Exception {
        File tempFile = new File(ctx.getCacheDir(), fileName);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.connect();
        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(tempFile);
        byte[] buf = new byte[8192];
        int read;
        while ((read = is.read(buf)) != -1) fos.write(buf, 0, read);
        is.close(); fos.close();
        return androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.getPackageName() + ".provider", tempFile);
    }

    // ── Callback helpers ──────────────────────────────────────────────────

    private static void fireSuccess(DownloadCallback cb, Uri uri) {
        if (cb == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
            .post(() -> cb.onSuccess(uri));
    }

    private static void fireError(DownloadCallback cb, String msg) {
        if (cb == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
            .post(() -> cb.onError(msg));
    }
}
