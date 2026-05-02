package com.callx.app.utils;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
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
}
