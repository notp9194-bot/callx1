package com.callx.app.utils;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.app.DownloadManager;
import android.os.Environment;
import android.widget.Toast;

public class FileUtils {

    public static String fileName(Context ctx, Uri uri) {
        String name = "file";
        try (Cursor c = ctx.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return name;
    }

    public static long fileSize(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.1f GB", b / 1073741824.0);
    }

    public static String formatDuration(long ms) {
        long total = ms / 1000;
        long m = total / 60, s = total % 60;
        return String.format("%d:%02d", m, s);
    }

    /**
     * Try to open a remote URL with an intent chooser (browser / media player / docs viewer).
     * If the URL is null or empty, fall back to downloading via DownloadManager.
     *
     * @param ctx      Activity or Application context
     * @param url      Remote URL of the file
     * @param fileName Suggested filename for the download notification
     */
    public static void openOrDownload(Context ctx, String url, String fileName) {
        if (ctx == null || url == null || url.isEmpty()) {
            Toast.makeText(ctx, "File not available", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(Intent.createChooser(intent, "Open with"));
        } catch (Exception e) {
            try {
                DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setTitle(fileName != null ? fileName : "Download");
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName != null ? fileName : "file");
                    dm.enqueue(req);
                    Toast.makeText(ctx, "Downloading…", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ex) {
                Toast.makeText(ctx, "Cannot open file", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
