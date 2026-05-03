package com.callx.app.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaCache — Local disk cache for audio, video, and file messages.
 *
 * How it works:
 *   1. URL ka MD5 hash bana ke filename banata hai
 *   2. App ke cache folder mein check karta hai — file hai to seedha return
 *   3. File nahi hai to background mein download karta hai, phir callback deta hai
 *
 * Result: Pehli baar sirf download hota hai. Baar baar kholo — zero data use.
 */
public class MediaCache {

    private static final String TAG       = "MediaCache";
    private static final String DIR_NAME  = "callx_media_cache";
    private static final long   MAX_CACHE = 200L * 1024 * 1024; // 200 MB limit

    private static final ExecutorService sPool =
            Executors.newFixedThreadPool(3);
    private static final Handler sMain =
            new Handler(Looper.getMainLooper());

    public interface Callback {
        void onReady(File file);
        void onError(String reason);
    }

    /**
     * Sabse pehle local cache check karta hai.
     * Agar file exist karti hai — turant callback (zero network).
     * Agar nahi hai — background mein download karke callback deta hai.
     */
    public static void get(Context ctx, String url, Callback cb) {
        if (ctx == null || url == null || url.isEmpty()) {
            if (cb != null) cb.onError("Invalid URL");
            return;
        }

        File cached = cacheFileFor(ctx, url);
        if (cached != null && cached.exists() && cached.length() > 0) {
            Log.d(TAG, "Cache HIT: " + cached.getName());
            if (cb != null) cb.onReady(cached);
            return;
        }

        Log.d(TAG, "Cache MISS — downloading: " + url);
        sPool.execute(() -> {
            File result = download(ctx, url);
            sMain.post(() -> {
                if (result != null) {
                    if (cb != null) cb.onReady(result);
                } else {
                    if (cb != null) cb.onError("Download failed");
                }
            });
        });
    }

    /**
     * Sirf check karo — download mat karo.
     * Returns null agar cached nahi hai.
     */
    public static File getCached(Context ctx, String url) {
        if (ctx == null || url == null || url.isEmpty()) return null;
        File f = cacheFileFor(ctx, url);
        return (f != null && f.exists() && f.length() > 0) ? f : null;
    }

    /**
     * Cache size check (bytes mein).
     */
    public static long getCacheSizeBytes(Context ctx) {
        File dir = cacheDir(ctx);
        if (dir == null) return 0;
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    /**
     * Poora media cache saaf karo.
     */
    public static void clearAll(Context ctx) {
        sPool.execute(() -> {
            File dir = cacheDir(ctx);
            if (dir == null) return;
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            Log.d(TAG, "Media cache cleared");
        });
    }

    private static File download(Context ctx, String urlStr) {
        HttpURLConnection conn = null;
        try {
            evictIfNeeded(ctx);

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + code + " for " + urlStr);
                return null;
            }

            File out = cacheFileFor(ctx, urlStr);
            if (out == null) return null;

            File tmp = new File(out.getParent(), out.getName() + ".tmp");
            try (InputStream in  = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                fos.flush();
            }

            if (tmp.exists() && tmp.length() > 0) {
                tmp.renameTo(out);
                Log.d(TAG, "Cached: " + out.getName() + " (" + out.length() + " bytes)");
                return out;
            }
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Download error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static File cacheFileFor(Context ctx, String url) {
        File dir = cacheDir(ctx);
        if (dir == null) return null;
        String ext = extensionFor(url);
        return new File(dir, md5(url) + ext);
    }

    private static File cacheDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir.exists() ? dir : null;
    }

    private static String extensionFor(String url) {
        try {
            String path = new URL(url).getPath();
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot).toLowerCase();
                if (ext.length() <= 5) return ext;
            }
        } catch (Exception ignored) {}
        return ".bin";
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private static void evictIfNeeded(Context ctx) {
        if (getCacheSizeBytes(ctx) < MAX_CACHE) return;
        File dir = cacheDir(ctx);
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long freed = 0;
        long target = MAX_CACHE / 4;
        for (File f : files) {
            freed += f.length();
            f.delete();
            if (freed >= target) break;
        }
        Log.d(TAG, "Evicted " + freed / 1024 + " KB from media cache");
    }
}
