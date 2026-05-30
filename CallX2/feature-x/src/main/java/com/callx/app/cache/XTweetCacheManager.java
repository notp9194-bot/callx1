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
 * XTweetCacheManager — Singleton SimpleCache specifically for X (tweet) videos.
 *
 * Reels ke ReelCacheManager ki tarah hi kaam karta hai, par X feature ke liye:
 *  ✅ 300 MB dedicated disk cache for tweet videos/media
 *  ✅ LRU eviction — purane tweet videos automatically remove hote hain
 *  ✅ CacheDataSource.Factory — ExoPlayer/video player is cache se connect karta hai
 *  ✅ Ek hi instance puri app mein (singleton)
 *
 * Usage:
 *   // Application.onCreate() ya XActivity/XHomeFragment mein:
 *   XTweetCacheManager.init(context);
 *
 *   // Video player ke liye CacheDataSource.Factory:
 *   CacheDataSource.Factory factory = XTweetCacheManager.getCacheDataSourceFactory();
 *
 *   // App band hone par:
 *   XTweetCacheManager.release();
 */
@OptIn(markerClass = UnstableApi.class)
public class XTweetCacheManager {

    private static final String TAG        = "XTweetCacheManager";
    private static final long   CACHE_SIZE = 300L * 1024 * 1024; // 300 MB
    private static final String CACHE_DIR  = "x_tweet_video_cache";

    private static SimpleCache             sSimpleCache;
    private static CacheDataSource.Factory sCacheDataSourceFactory;
    private static boolean                 sInitialized = false;

    private XTweetCacheManager() {}

    /**
     * Pehle init() call karo — Application.onCreate() ya XHomeFragment.onAttach() mein.
     * Double-call safe hai (sirf ek baar initialize hoga).
     */
    public static synchronized void init(Context context) {
        if (sInitialized) return;

        try {
            File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
            if (!cacheDir.exists()) cacheDir.mkdirs();

            // LRU Evictor: jab 300MB bhar jaye, sabse purane tweet video ka cache delete hoga
            LeastRecentlyUsedCacheEvictor evictor =
                new LeastRecentlyUsedCacheEvictor(CACHE_SIZE);

            sSimpleCache = new SimpleCache(cacheDir, evictor);

            // HTTP DataSource — normal internet se media fetch karta hai
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
            Log.d(TAG, "XTweetCacheManager initialized. Cache dir: " + cacheDir.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize XTweetCacheManager", e);
        }
    }

    /**
     * ExoPlayer / VideoPlayer ke liye CacheDataSource.Factory return karta hai.
     * Is factory se banaya MediaSource automatically cache use karega.
     */
    public static CacheDataSource.Factory getCacheDataSourceFactory() {
        if (!sInitialized) {
            throw new IllegalStateException("XTweetCacheManager.init(context) pehle call karo!");
        }
        return sCacheDataSourceFactory;
    }

    /**
     * Raw SimpleCache access — XTweetMediaPreloader ke liye.
     */
    public static SimpleCache getSimpleCache() {
        if (!sInitialized) {
            throw new IllegalStateException("XTweetCacheManager.init(context) pehle call karo!");
        }
        return sSimpleCache;
    }

    /**
     * Kisi specific tweet media URL ka kitna data cache mein hai (bytes mein).
     * 0 = bilkul cache nahi, > 0 = partially ya fully cached.
     */
    public static long getCachedBytes(String mediaUrl) {
        if (!sInitialized || sSimpleCache == null) return 0;
        try {
            return androidx.media3.datasource.cache.ContentMetadata.getContentLength(
                sSimpleCache.getContentMetadata(mediaUrl));
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
                Log.d(TAG, "XTweetCacheManager released.");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing X cache", e);
            } finally {
                sSimpleCache            = null;
                sCacheDataSourceFactory = null;
                sInitialized            = false;
            }
        }
    }

    public static boolean isInitialized() {
        return sInitialized;
    }
}
