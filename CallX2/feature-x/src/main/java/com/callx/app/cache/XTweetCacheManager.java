package com.callx.app.cache;

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
import com.callx.app.feed.XActivity;
import com.callx.app.feed.XHomeFragment;

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
        UnifiedVideoCacheManager.init(context);
        sSimpleCache = UnifiedVideoCacheManager.getSimpleCache();
        sCacheDataSourceFactory = UnifiedVideoCacheManager.getFactory(
            UnifiedVideoCacheManager.Module.X);
        sInitialized = true;
        Log.d(TAG, "XTweetCacheManager → UnifiedVideoCacheManager (X)");
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
        sSimpleCache            = null;
        sCacheDataSourceFactory = null;
        sInitialized            = false;
        Log.d(TAG, "XTweetCacheManager detached from UnifiedVideoCacheManager.");
    }

    public static boolean isInitialized() {
        return sInitialized;
    }
}
