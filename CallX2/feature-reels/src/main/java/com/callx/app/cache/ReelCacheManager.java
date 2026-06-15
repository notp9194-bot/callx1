package com.callx.app.cache;

import com.callx.app.feed.ReelsFragment;
import com.callx.app.feed.ReelPlayerFragment;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import com.callx.app.cache.UnifiedVideoCacheManager;
import java.io.File;

/**
 * ReelCacheManager — Singleton SimpleCache specifically for Reels.
 *
 * v29 update:
 *  ✅ Dedicated 500MB cache — ONLY reels, not shared with X/Status/Chat
 *  ✅ Backed by UnifiedVideoCacheManager's separate reels SimpleCache
 *  ✅ Low-end devices: 300MB
 *  ✅ LRU eviction — purane reels automatically remove hote hain
 *  ✅ extractCachedVideoToFile() — duet compositor ke liye cached video file extract
 *  ✅ trimMemory() — OS low-memory signal par 50% cache release
 *
 * Usage:
 *   ReelCacheManager.init(context);
 *   CacheDataSource.Factory factory = ReelCacheManager.getCacheDataSourceFactory();
 *   ProgressiveMediaSource source = new ProgressiveMediaSource.Factory(factory)
 *       .createMediaSource(MediaItem.fromUri(videoUrl));
 *   ReelCacheManager.release(); // App close hone par
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelCacheManager {

    private static final String TAG = "ReelCacheManager";

    // Adaptive cache: low-end 300MB, normal 500MB
    private static final long CACHE_SIZE_LOW  = 300L * 1024 * 1024; // 300MB
    private static final long CACHE_SIZE_NORM = 500L * 1024 * 1024; // 500MB
    private static final String CACHE_DIR  = "reel_video_cache";

    private static SimpleCache            sSimpleCache;
    private static CacheDataSource.Factory sCacheDataSourceFactory;
    private static boolean                sInitialized = false;

    private ReelCacheManager() {}

    private static boolean isLowMemoryDevice(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.isLowRamDevice();
    }

    /**
     * Pehle init() call karo — Application.onCreate() ya ReelsFragment.onAttach() mein.
     * Double-call safe hai (sirf ek baar initialize hoga).
     */
    public static synchronized void init(Context context) {
        if (sInitialized) return;
        // Delegate to UnifiedVideoCacheManager
        UnifiedVideoCacheManager.init(context);
        sSimpleCache = UnifiedVideoCacheManager.getSimpleCache();
        sCacheDataSourceFactory = UnifiedVideoCacheManager.getFactory(
            UnifiedVideoCacheManager.Module.REELS);
        sInitialized = true;
        Log.d(TAG, "ReelCacheManager → UnifiedVideoCacheManager (REELS)");
    }

    /**
     * ExoPlayer ke liye CacheDataSource.Factory return karta hai.
     * Is factory se banaya MediaSource automatically cache use karega.
     */
    public static CacheDataSource.Factory getCacheDataSourceFactory() {
        if (!sInitialized) {
            throw new IllegalStateException("ReelCacheManager.init(context) pehle call karo!");
        }
        return sCacheDataSourceFactory;
    }

    /**
     * Raw SimpleCache access — ReelVideoPreloader ke liye.
     */
    public static SimpleCache getSimpleCache() {
        if (!sInitialized) {
            throw new IllegalStateException("ReelCacheManager.init(context) pehle call karo!");
        }
        return sSimpleCache;
    }

    /**
     * Kisi specific reel ka kitna data cache mein hai (bytes mein).
     * 0 = bilkul cache nahi, > 0 = partially ya fully cached.
     */
    public static long getCachedBytes(String videoUrl) {
        if (!sInitialized || sSimpleCache == null) return 0;
        try {
            return androidx.media3.datasource.cache.ContentMetadata.getContentLength(
                sSimpleCache.getContentMetadata(videoUrl));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * FIX #MEM-3B: trimMemory — CallxApp.onTrimMemory() se call hota hai.
     * OS low-memory signal aane par cached reel data ka ~50% release karo.
     * LRU order mein evict hoga — sabse purane / least-watched reels pehle hatenge.
     */
    public static synchronized void trimMemory() {
        if (!sInitialized || sSimpleCache == null) return;
        try {
            long before = sSimpleCache.getCacheSpace();
            long target = before / 2; // 50% release karo
            for (String key : sSimpleCache.getKeys()) {
                if (sSimpleCache.getCacheSpace() <= target) break;
                sSimpleCache.removeResource(key);
            }
            Log.d(TAG, "ReelCacheManager trimMemory: "
                + before / (1024 * 1024) + "MB → "
                + sSimpleCache.getCacheSpace() / (1024 * 1024) + "MB");
        } catch (Exception e) {
            Log.w(TAG, "trimMemory error: " + e.getMessage());
        }
    }

    /**
     * App close hone par cache release karo.
     * Application.onTerminate() ya CallxApp mein call karo.
     */
    public static synchronized void release() {
        // Shared cache — don't release here, UnifiedVideoCacheManager.release() handles it
        sSimpleCache            = null;
        sCacheDataSourceFactory = null;
        sInitialized            = false;
        Log.d(TAG, "ReelCacheManager detached from UnifiedVideoCacheManager.");
    }

    public static boolean isInitialized() {
        return sInitialized;
    }

    /**
     * SimpleCache se cached reel bytes ek local temp file mein dump karta hai.
     * Duet compositor network URL ki jagah local file use karta hai.
     *
     * @param context  Context for getCacheDir()
     * @param videoUrl Original reel URL (SimpleCache key)
     * @param reelId   Used for output filename
     * @return         Absolute path of extracted file, or null if not cached / failed
     */
    @androidx.annotation.Nullable
    public static String extractCachedVideoToFile(Context context,
                                                   String videoUrl, String reelId) {
        if (!sInitialized || sSimpleCache == null) return null;
        if (videoUrl == null || videoUrl.isEmpty())  return null;

        try {
            java.util.NavigableSet<androidx.media3.datasource.cache.CacheSpan> spans =
                sSimpleCache.getCachedSpans(videoUrl);
            if (spans == null || spans.isEmpty()) {
                Log.d(TAG, "extractCachedVideoToFile: nothing cached for " + reelId);
                return null;
            }

            // Total cached bytes
            long totalCached = 0;
            for (androidx.media3.datasource.cache.CacheSpan span : spans) {
                totalCached += span.length;
            }
            if (totalCached < 500_000) { // require at least 500KB
                Log.d(TAG, "extractCachedVideoToFile: too little cached (" + totalCached + ")");
                return null;
            }

            File outFile = new File(context.getCacheDir(),
                "duet_orig_" + reelId + ".mp4");
            // Reuse if already extracted with same size
            if (outFile.exists() && outFile.length() == totalCached) {
                Log.d(TAG, "extractCachedVideoToFile: reusing " + outFile.length() + " bytes");
                return outFile.getAbsolutePath();
            }

            // Write spans sequentially into one file
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
            byte[] buf = new byte[64 * 1024];
            long written = 0;
            for (androidx.media3.datasource.cache.CacheSpan span : spans) {
                if (span.isCached && span.file != null && span.file.exists()) {
                    java.io.FileInputStream fis = new java.io.FileInputStream(span.file);
                    int n;
                    while ((n = fis.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        written += n;
                    }
                    fis.close();
                }
            }
            fos.flush();
            fos.close();

            if (written < 500_000) {
                outFile.delete();
                Log.w(TAG, "extractCachedVideoToFile: wrote too little (" + written + ")");
                return null;
            }

            Log.d(TAG, "extractCachedVideoToFile: extracted " + written + " bytes → " + outFile);
            return outFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "extractCachedVideoToFile failed: " + e.getMessage(), e);
            return null;
        }
    }
}
