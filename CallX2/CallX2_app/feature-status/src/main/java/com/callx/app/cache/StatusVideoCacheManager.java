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
import com.callx.app.viewer.StatusViewerActivity;
import com.callx.app.feed.StatusFragment;
/**
 * StatusVideoCacheManager — Singleton ExoPlayer cache specifically for Status videos.
 *
 * Exactly Reels jaisa pattern (ReelCacheManager mirror):
 *   ✅ 200 MB dedicated disk cache (Status videos chhote hote hain)
 *   ✅ LRU eviction — purane status videos automatically remove hote hain
 *   ✅ CacheDataSource.Factory — ExoPlayer pehle cache check karta hai, phir internet
 *   ✅ Singleton — ek hi instance pure app mein
 *
 * Flow:
 *   1. StatusMediaPreloader: StatusFragment open hone par contacts ke video
 *      statuses ka pehla 2MB background mein download ho jaata hai.
 *   2. StatusViewerActivity.showVideoStatus(): is factory se ExoPlayer banao →
 *      cache hit = instant play, no buffering.
 */
@OptIn(markerClass = UnstableApi.class)
public class StatusVideoCacheManager {
    private static final String TAG        = "StatusVideoCache";
    private static final long   CACHE_SIZE = 200L * 1024 * 1024; // 200 MB
    private static final String CACHE_DIR  = "status_video_cache";
    private static SimpleCache              sSimpleCache;
    private static CacheDataSource.Factory  sCacheDataSourceFactory;
    private static boolean                  sInitialized = false;
    private StatusVideoCacheManager() {}
    /**
     * Init — CallxApp.onCreate() mein call karo.
     * Double-call safe (idempotent).
     */
    public static synchronized void init(Context context) {
        if (sInitialized) return;
        try {
            File cacheDir = new File(context.getCacheDir(), CACHE_DIR);
            if (!cacheDir.exists()) cacheDir.mkdirs();
            LeastRecentlyUsedCacheEvictor evictor =
                new LeastRecentlyUsedCacheEvictor(CACHE_SIZE);
            sSimpleCache = new SimpleCache(cacheDir, evictor);
            DefaultHttpDataSource.Factory httpFactory =
                new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true);
            // Cache-first: local cache → internet + auto-save
            sCacheDataSourceFactory = new CacheDataSource.Factory()
                .setCache(sSimpleCache)
                .setUpstreamDataSourceFactory(httpFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
            sInitialized = true;
            Log.d(TAG, "StatusVideoCacheManager init. Dir: " + cacheDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to init StatusVideoCacheManager", e);
        }
    }
    /** ExoPlayer ke liye CacheDataSource.Factory */
    public static CacheDataSource.Factory getCacheDataSourceFactory() {
        if (!sInitialized) {
            throw new IllegalStateException("StatusVideoCacheManager.init(context) pehle call karo!");
        }
        return sCacheDataSourceFactory;
    }
    /** StatusMediaPreloader ke liye raw SimpleCache */
    public static SimpleCache getSimpleCache() {
        if (!sInitialized) {
            throw new IllegalStateException("StatusVideoCacheManager.init(context) pehle call karo!");
        }
        return sSimpleCache;
    }
    /** Kitna data cache mein hai is URL ka (bytes). 0 = not cached. */
    public static long getCachedBytes(String videoUrl) {
        if (!sInitialized || sSimpleCache == null) return 0;
        try {
            return androidx.media3.datasource.cache.ContentMetadata.getContentLength(
                sSimpleCache.getContentMetadata(videoUrl));
        } catch (Exception e) {
            return 0;
        }
    }
    /** App terminate par release karo — CallxApp.onTerminate() mein */
    public static synchronized void release() {
        if (sSimpleCache != null) {
            try {
                sSimpleCache.release();
                Log.d(TAG, "StatusVideoCacheManager released.");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing", e);
            } finally {
                sSimpleCache            = null;
                sCacheDataSourceFactory = null;
                sInitialized            = false;
            }
        }
    }
    public static boolean isInitialized() { return sInitialized; }
}