package com.callx.app.cache;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

/**
 * ReelCacheManager — Singleton SimpleCache specifically for Reels.
 *
 * Instagram-like caching:
 *  ✅ Adaptive disk cache — 200MB (low-end) / 350MB (normal) [FIX #MEM-3A]
 *  ✅ LRU eviction — purane reels automatically remove hote hain
 *  ✅ CacheDataSource.Factory — ExoPlayer is connect karta hai cache se
 *  ✅ Ek hi instance puri app mein (singleton)
 *  ✅ trimMemory() — OS low-memory signal par 50% cache release [FIX #MEM-3B]
 *
 * Usage:
 *   // Application.onCreate() ya ReelPlayerFragment mein:
 *   ReelCacheManager.init(context);
 *
 *   // ExoPlayer source banana:
 *   CacheDataSource.Factory factory = ReelCacheManager.getCacheDataSourceFactory();
 *   ProgressiveMediaSource source = new ProgressiveMediaSource.Factory(factory)
 *       .createMediaSource(MediaItem.fromUri(videoUrl));
 *
 *   // App band hone par (Application.onTerminate ya similar):
 *   ReelCacheManager.release();
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelCacheManager {

    private static final String TAG = "ReelCacheManager";

    // FIX #MEM-3A: 500MB → adaptive
    // Low-end phones mein 500MB bahut zyada tha — OOM crash hota tha
    private static final long CACHE_SIZE_LOW  = 200L * 1024 * 1024; // 200MB
    private static final long CACHE_SIZE_NORM = 350L * 1024 * 1024; // 350MB
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

        try {
            File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
            if (!cacheDir.exists()) cacheDir.mkdirs();

            // FIX #MEM-3A: Adaptive size — low-end 200MB, normal 350MB
            long cacheSize = isLowMemoryDevice(context) ? CACHE_SIZE_LOW : CACHE_SIZE_NORM;
            LeastRecentlyUsedCacheEvictor evictor =
                new LeastRecentlyUsedCacheEvictor(cacheSize);

            sSimpleCache = new SimpleCache(cacheDir, evictor);

            // HTTP DataSource — normal internet se video fetch karta hai
            DefaultHttpDataSource.Factory httpFactory =
                new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true);

            // CacheDataSource.Factory:
            //   1. Pehle cache check karta hai → instant play
            //   2. Cache miss → internet se fetch + automatically cache mein save
            sCacheDataSourceFactory = new CacheDataSource.Factory()
                .setCache(sSimpleCache)
                .setUpstreamDataSourceFactory(httpFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

            sInitialized = true;
            Log.d(TAG, "ReelCacheManager initialized. Cache dir: " + cacheDir.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ReelCacheManager", e);
        }
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
        if (sSimpleCache != null) {
            try {
                sSimpleCache.release();
                Log.d(TAG, "ReelCacheManager released.");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing cache", e);
            } finally {
                sSimpleCache        = null;
                sCacheDataSourceFactory = null;
                sInitialized        = false;
            }
        }
    }

    public static boolean isInitialized() {
        return sInitialized;
    }
}
