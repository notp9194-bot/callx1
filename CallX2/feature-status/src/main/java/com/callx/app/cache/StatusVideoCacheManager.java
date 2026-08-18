package com.callx.app.cache;

import android.content.Context;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;

/**
 * StatusVideoCacheManager — delegates to UnifiedVideoCacheManager (STATUS module).
 * Kept for backward compatibility with StatusViewerActivity + StatusMediaPreloader.
 */
@OptIn(markerClass = UnstableApi.class)
public class StatusVideoCacheManager {

    private static final String TAG = "StatusVideoCache";

    private static SimpleCache             sSimpleCache;
    private static CacheDataSource.Factory sCacheDataSourceFactory;
    private static boolean                 sInitialized = false;

    private StatusVideoCacheManager() {}

    public static synchronized void init(Context context) {
        if (sInitialized) return;
        try {
            UnifiedVideoCacheManager.init(context);
            sSimpleCache            = UnifiedVideoCacheManager.getSimpleCache();
            sCacheDataSourceFactory = UnifiedVideoCacheManager.getFactory(
                UnifiedVideoCacheManager.Module.STATUS);
            sInitialized = true;
            Log.d(TAG, "StatusVideoCacheManager → UnifiedVideoCacheManager (STATUS)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to init StatusVideoCacheManager", e);
        }
    }

    public static CacheDataSource.Factory getCacheDataSourceFactory() {
        if (!sInitialized) {
            throw new IllegalStateException("StatusVideoCacheManager.init() pehle call karo!");
        }
        return sCacheDataSourceFactory;
    }

    public static SimpleCache getSimpleCache() {
        if (!sInitialized) {
            throw new IllegalStateException("StatusVideoCacheManager.init() pehle call karo!");
        }
        return sSimpleCache;
    }

    public static long getCachedBytes(String videoUrl) {
        if (!sInitialized || sSimpleCache == null) return 0;
        try {
            return androidx.media3.datasource.cache.ContentMetadata.getContentLength(
                sSimpleCache.getContentMetadata(videoUrl));
        } catch (Exception e) {
            return 0;
        }
    }

    public static synchronized void release() {
        // Shared cache — UnifiedVideoCacheManager.release() handles actual release
        sSimpleCache            = null;
        sCacheDataSourceFactory = null;
        sInitialized            = false;
        Log.d(TAG, "StatusVideoCacheManager detached.");
    }

    public static boolean isInitialized() { return sInitialized; }
}
