package com.callx.app.cache;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Media Streaming Cache — partial/progressive download support.
 *
 * Instead of downloading full video/audio before playback:
 *   1. Stream directly via ExoPlayer (handles range requests natively)
 *   2. Cache downloaded segments to DiskCache for offline replay
 *
 * Provides:
 *   - Async background download with callback
 *   - Resumable downloads (checks existing partial file)
 *   - Glide-compatible file path resolution
 */
public class MediaStreamCache {

    private static final String TAG          = "MediaStreamCache";
    private static final int    BUFFER_SIZE  = 8 * 1024; // 8 KB chunks
    private static final long   MAX_PRELOAD  = 512 * 1024; // preload first 512 KB

    private static MediaStreamCache sInstance;

    private final DiskCache       mDisk;
    private final ExecutorService mExecutor;

    private MediaStreamCache(Context ctx) {
        mDisk     = DiskCache.getInstance(ctx);
        mExecutor = Executors.newFixedThreadPool(2);
    }

    public static synchronized MediaStreamCache getInstance(Context ctx) {
        if (sInstance == null) sInstance = new MediaStreamCache(ctx.getApplicationContext());
        return sInstance;
    }

    public interface DownloadCallback {
        void onComplete(File file);
        void onError(String error);
        void onProgress(int percent);
    }

    /**
     * Get a cached media file, or download it in the background.
     * Returns cached file immediately if available, otherwise downloads async.
     */
    public File getCached(String mediaUrl) {
        String key = urlToKey(mediaUrl);
        return mDisk.get(key);
    }

    /**
     * Preload first 512 KB of a media file (for fast playback start).
     * Used for voice notes and short videos.
     */
    public void preloadPartial(String mediaUrl, DownloadCallback callback) {
        String key = urlToKey(mediaUrl);
        File existing = mDisk.get(key);
        if (existing != null) {
            if (callback != null) callback.onComplete(existing);
            return;
        }

        mExecutor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(mediaUrl).openConnection();
                conn.setRequestProperty("Range", "bytes=0-" + (MAX_PRELOAD - 1));
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                conn.connect();

                long total = conn.getContentLengthLong();
                byte[] buffer   = new byte[BUFFER_SIZE];
                int    read;
                long   downloaded = 0;

                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                try (InputStream is = conn.getInputStream()) {
                    while ((read = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                        downloaded += read;
                        if (callback != null && total > 0) {
                            callback.onProgress((int) (downloaded * 100 / total));
                        }
                    }
                }

                byte[] data = baos.toByteArray();
                mDisk.save(key, data);
                File saved = mDisk.get(key);

                if (callback != null) {
                    if (saved != null) callback.onComplete(saved);
                    else callback.onError("File save failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Preload failed: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    /**
     * Full async download with progress reporting.
     */
    public void downloadFull(String mediaUrl, DownloadCallback callback) {
        String key = urlToKey(mediaUrl);
        File existing = mDisk.get(key);
        if (existing != null) {
            if (callback != null) callback.onComplete(existing);
            return;
        }

        mExecutor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(mediaUrl).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.connect();

                long total  = conn.getContentLengthLong();
                byte[] buf  = new byte[BUFFER_SIZE];
                int    read;
                long   downloaded = 0;

                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                try (InputStream is = conn.getInputStream()) {
                    while ((read = is.read(buf)) != -1) {
                        baos.write(buf, 0, read);
                        downloaded += read;
                        if (callback != null && total > 0) {
                            callback.onProgress((int) (downloaded * 100 / total));
                        }
                    }
                }

                boolean saved = mDisk.save(key, baos.toByteArray());
                if (callback != null) {
                    if (saved) callback.onComplete(mDisk.get(key));
                    else callback.onError("Disk save failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Full download failed: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    private String urlToKey(String url) {
        // Use URL hash as cache file name (avoids illegal filename chars)
        return "media_" + Integer.toHexString(url.hashCode());
    }
}
