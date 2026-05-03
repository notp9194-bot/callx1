package com.callx.app.utils;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
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
     * Opens a remote URL in the browser/downloads it.
     * If the URL points to a known file type, fires an ACTION_VIEW intent;
     * otherwise opens in the browser.
     */
    public static void openOrDownload(Context ctx, String url, String fileName) {
        if (url == null || url.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            android.util.Log.w("FileUtils", "openOrDownload failed: " + e.getMessage());
        }
    }
}
