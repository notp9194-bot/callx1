package com.callx.app.utils;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.callx.app.models.StatusItem;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/**
 * StatusDownloadHelper — Download status images and videos to gallery.
 * Android 10+: uses MediaStore (no WRITE_EXTERNAL_STORAGE needed).
 * Android 9-: saves to Pictures/CallX-Status and triggers MediaScanner.
 */
public final class StatusDownloadHelper {

    public static final int REQUEST_WRITE = 9011;

    private StatusDownloadHelper() {}

    /** Check and request storage permission if needed (Android 9 and below). */
    public static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestPermission(Activity act) {
        ActivityCompat.requestPermissions(act,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_WRITE);
    }

    /** Download image status to gallery. */
    public static void downloadImage(Context ctx, StatusItem item) {
        if (item == null || item.mediaUrl == null) {
            toast(ctx, "No image to download"); return;
        }
        download(ctx, item.mediaUrl, "image", item.ownerName);
    }

    /** Download video status to gallery. */
    public static void downloadVideo(Context ctx, StatusItem item) {
        if (item == null || item.mediaUrl == null) {
            toast(ctx, "No video to download"); return;
        }
        download(ctx, item.mediaUrl, "video", item.ownerName);
    }

    public static void downloadStatus(Context ctx, StatusItem item) {
        if (item == null) return;
        if ("video".equals(item.type)) downloadVideo(ctx, item);
        else if ("image".equals(item.type)) downloadImage(ctx, item);
        else toast(ctx, "Only image and video statuses can be downloaded");
    }

    private static void download(Context ctx, String urlStr, String mediaType, String ownerName) {
        if (!hasPermission(ctx)) {
            toast(ctx, "Storage permission required"); return;
        }
        toast(ctx, "Downloading…");
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.connect();
                byte[] data = readBytes(conn.getInputStream());
                conn.disconnect();

                String name = "CallX_Status_" + System.currentTimeMillis();
                boolean isVideo = "video".equals(mediaType);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name + (isVideo ? ".mp4" : ".jpg"));
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            isVideo ? Environment.DIRECTORY_MOVIES + "/CallX-Status"
                                    : Environment.DIRECTORY_PICTURES + "/CallX-Status");
                    Uri uri = ctx.getContentResolver().insert(
                            isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri != null) {
                        try (FileOutputStream out = new FileOutputStream(
                                ctx.getContentResolver().openFileDescriptor(uri, "w").getFileDescriptor())) {
                            out.write(data);
                        }
                    }
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES),
                            "CallX-Status");
                    dir.mkdirs();
                    File file = new File(dir, name + (isVideo ? ".mp4" : ".jpg"));
                    try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); }
                    MediaScannerConnection.scanFile(ctx, new String[]{file.getAbsolutePath()},
                            null, null);
                }

                if (ctx instanceof Activity) {
                    ((Activity) ctx).runOnUiThread(() ->
                            toast(ctx, "Saved to gallery ✓"));
                }
            } catch (Exception e) {
                if (ctx instanceof Activity) {
                    ((Activity) ctx).runOnUiThread(() ->
                            toast(ctx, "Download failed: " + e.getMessage()));
                }
            }
        });
    }

    private static byte[] readBytes(InputStream is) throws Exception {
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private static void toast(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }
}
