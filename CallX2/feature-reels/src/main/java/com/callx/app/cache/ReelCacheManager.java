package com.callx.app.cache;

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
 *  ✅ 500 MB dedicated disk cache for reel videos
 *  ✅ LRU eviction — purane reels automatically remove hote hain
 *  ✅ CacheDataSource.Factory — ExoPlayer is connect karta hai cache se
 *  ✅ Ek hi instance puri app mein (singleton)
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

    private static final String TAG        = "ReelCacheManager";
    private static final long   CACHE_SIZE = 500L * 1024 * 1024; // 500 MB
    private static final String CACHE_DIR  = "reel_video_cache";

    private static SimpleCache            sSimpleCache;
    private static CacheDataSource.Factory sCacheDataSourceFactory;
    private static boolean                sInitialized = false;

    private ReelCacheManager() {}

    /**
     * Pehle init() call karo — Application.onCreate() ya ReelsFragment.onAttach() mein.
     * Double-call safe hai (sirf ek baar initialize hoga).
     */
    public static synchronized void init(Context context) {
        if (sInitialized) return;

        try {
            File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
            if (!cacheDir.exists()) cacheDir.mkdirs();

            // LRU Evictor: jab 500MB bhar jaye, sabse purani reel ka cache delete hoga
            LeastRecentlyUsedCacheEvictor evictor =
                new LeastRecentlyUsedCacheEvictor(CACHE_SIZE);

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
