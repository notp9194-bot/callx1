package com.callx.app.youtube.core.utils;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.callx.app.youtube.core.models.YouTubeVideo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * YouTubeDownloadManager — yt-core mein download utility.
 * yt-library ka wrapper class isko extend karta hai (backward compat).
 */
public class YouTubeDownloadManager {

    private static final String TAG = "YTDownloadManager";
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public interface DownloadCallback {
        void onStarted();
        void onProgress(int percent);
        void onCompleted(String filePath);
        void onAlreadyDownloaded(String filePath);
        void onError(String error);
    }

    public interface GalleryCallback {
        void onStarted();
        void onProgress(int percent);
        void onCompleted();
        void onError(String error);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * App-private downloads folder mein video save karo.
     */
    public static void startDownload(Context ctx, YouTubeVideo video, DownloadCallback cb) {
        if (video == null || video.videoUrl == null || video.videoUrl.isEmpty()) {
            if (cb != null) cb.onError("Video URL missing");
            return;
        }
        executor.execute(() -> {
            try {
                File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "CallX_YT");
                if (!dir.exists()) dir.mkdirs();

                String fileName = sanitize(video.videoId != null ? video.videoId : video.title) + ".mp4";
                File outFile = new File(dir, fileName);

                if (outFile.exists()) {
                    if (cb != null) cb.onAlreadyDownloaded(outFile.getAbsolutePath());
                    return;
                }

                if (cb != null) cb.onStarted();
                downloadToFile(video.videoUrl, outFile, cb != null ? cb::onProgress : null);
                if (cb != null) cb.onCompleted(outFile.getAbsolutePath());

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                if (cb != null) cb.onError(e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
        });
    }

    /**
     * Gallery (MediaStore) mein video save karo — Photos app mein dikhega.
     */
    public static void saveToGallery(Context ctx, YouTubeVideo video, GalleryCallback cb) {
        if (video == null || video.videoUrl == null || video.videoUrl.isEmpty()) {
            if (cb != null) cb.onError("Video URL missing");
            return;
        }
        executor.execute(() -> {
            try {
                if (cb != null) cb.onStarted();
                String displayName = sanitize(video.title != null ? video.title : video.videoId) + ".mp4";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
                    cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CallX_YT");
                    Uri uri = ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("MediaStore insert failed");
                    try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                        streamUrl(video.videoUrl, os, cb != null ? cb::onProgress : null);
                    }
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MOVIES), "CallX_YT");
                    if (!dir.exists()) dir.mkdirs();
                    File outFile = new File(dir, displayName);
                    downloadToFile(video.videoUrl, outFile, cb != null ? cb::onProgress : null);
                }

                if (cb != null) cb.onCompleted();

            } catch (Exception e) {
                Log.e(TAG, "Gallery save failed", e);
                if (cb != null) cb.onError(e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
        });
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static void downloadToFile(String urlStr, File outFile, ProgressListener pl) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            streamUrl(urlStr, fos, pl);
        }
    }

    private static void streamUrl(String urlStr, java.io.OutputStream out, ProgressListener pl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.connect();

        int total = conn.getContentLength();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int read, downloaded = 0;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                downloaded += read;
                if (pl != null && total > 0) pl.onProgress((int) (downloaded * 100L / total));
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String sanitize(String name) {
        if (name == null) return "video";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").substring(0, Math.min(name.length(), 60));
    }

    private interface ProgressListener {
        void onProgress(int percent);
    }
}
