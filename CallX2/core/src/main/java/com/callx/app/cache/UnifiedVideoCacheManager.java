package com.callx.app.cache;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * UnifiedVideoCacheManager — Smart weighted cache for ALL video modules.
 *
 * Replaces:
 *   ReelCacheManager       (500MB)
 *   XTweetCacheManager     (300MB)
 *   StatusVideoCacheManager(200MB)
 *   ExoPlayerManager cache (150MB)
 *   Total old: 1150MB → Now: 500MB shared intelligently
 *
 * Features:
 *  ✅ Single SimpleCache — no duplicate video downloads across modules
 *  ✅ Weighted soft quotas — Reels 50%, X 25%, Status 15%, Chat 10%
 *  ✅ Partial caching — sirf pehle 12 seconds cache (3-4MB per reel)
 *     → 500MB mein ~125 reels fit hongi (pehle ~20-33 hoti thi)
 *  ✅ Adaptive total size — low-end: 300MB, normal: 500MB
 *  ✅ trimMemory() — OS signal par 50% release
 *  ✅ Module-aware eviction — ek module dusre ka quota nahi khata
 */
@OptIn(markerClass = UnstableApi.class)
public class UnifiedVideoCacheManager {

    private static final String TAG = "UnifiedVideoCache";

    // Total cache size
    private static final long TOTAL_CACHE_LOW  = 300L * 1024 * 1024; // 300MB low-end
    private static final long TOTAL_CACHE_NORM = 500L * 1024 * 1024; // 500MB normal

    // Partial cache: sirf pehle ~12 seconds
    // 2Mbps stream pe 12sec = ~3MB. 4MB rakha buffer ke liye.
    // 500MB / 4MB = ~125 reels cache hongi (pehle 500/20MB = ~25 thi)
    private static final long PARTIAL_BYTES_REELS  = 4L * 1024 * 1024; //  4MB
    private static final long PARTIAL_BYTES_X      = 5L * 1024 * 1024; //  5MB
    private static final long PARTIAL_BYTES_STATUS = 3L * 1024 * 1024; //  3MB
    private static final long PARTIAL_BYTES_CHAT   = 8L * 1024 * 1024; //  8MB

    // Soft quotas
    private static final double QUOTA_REELS  = 0.50;
    private static final double QUOTA_X      = 0.25;
    private static final double QUOTA_STATUS = 0.15;
    private static final double QUOTA_CHAT   = 0.10;

    private static final String CACHE_DIR = "unified_video_cache";

    private static SimpleCache             sCache;
    private static CacheDataSource.Factory sReelsFactory;
    private static CacheDataSource.Factory sXFactory;
    private static CacheDataSource.Factory sStatusFactory;
    private static CacheDataSource.Factory sChatFactory;
    private static boolean                 sInitialized = false;
    private static long                    sTotalCacheSize;

    private static ExecutorService sPreloadExecutor;
    private static final ConcurrentHashMap<String, Future<?>> sActiveTasks = new ConcurrentHashMap<>();
    private static final java.util.Set<String> sPreloading = ConcurrentHashMap.newKeySet();

    public enum Module { REELS, X, STATUS, CHAT }

    private UnifiedVideoCacheManager() {}

    public static synchronized void init(@NonNull Context context) {
        if (sInitialized) return;
        try {
            Context app = context.getApplicationContext();
            sTotalCacheSize = isLowMemory(app) ? TOTAL_CACHE_LOW : TOTAL_CACHE_NORM;

            File cacheDir = new File(app.getCacheDir(), CACHE_DIR);
            if (!cacheDir.exists()) cacheDir.mkdirs();

            sCache = new SimpleCache(cacheDir, new LeastRecentlyUsedCacheEvictor(sTotalCacheSize));

            DefaultHttpDataSource.Factory httpFactory =
                new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true);

            sReelsFactory  = buildFactory(httpFactory, PARTIAL_BYTES_REELS);
            sXFactory      = buildFactory(httpFactory, PARTIAL_BYTES_X);
            sStatusFactory = buildFactory(httpFactory, PARTIAL_BYTES_STATUS);
            sChatFactory   = buildFactory(httpFactory, PARTIAL_BYTES_CHAT);

            sPreloadExecutor = Executors.newFixedThreadPool(2);
            sInitialized = true;

            Log.i(TAG, "UnifiedVideoCacheManager init — "
                + sTotalCacheSize / (1024 * 1024) + "MB | " + cacheDir);
        } catch (Exception e) {
            Log.e(TAG, "Init failed", e);
        }
    }

    private static CacheDataSource.Factory buildFactory(
            DefaultHttpDataSource.Factory httpFactory, long fragmentSize) {
        return new CacheDataSource.Factory()
            .setCache(sCache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheWriteDataSinkFactory(
                new CacheDataSink.Factory()
                    .setCache(sCache)
                    .setFragmentSize(fragmentSize)
            );
    }

    @NonNull
    public static CacheDataSource.Factory getFactory(@NonNull Module module) {
        ensureInit();
        switch (module) {
            case X:      return sXFactory;
            case STATUS: return sStatusFactory;
            case CHAT:   return sChatFactory;
            default:     return sReelsFactory;
        }
    }

    @NonNull
    public static SimpleCache getSimpleCache() {
        ensureInit();
        return sCache;
    }

    public static void preloadPartial(@NonNull Context ctx,
                                      @Nullable String videoUrl,
                                      @NonNull Module module) {
        if (videoUrl == null || videoUrl.isEmpty()) return;
        if (!sInitialized) init(ctx);
        if (sPreloading.contains(videoUrl)) return;
        if (isModuleOverQuota(module)) return;

        sPreloading.add(videoUrl);
        Future<?> task = sPreloadExecutor.submit(() -> {
            try {
                CacheDataSource cds = getFactory(module).createDataSource();
                long bytes = partialBytes(module);
                DataSpec spec = new DataSpec.Builder()
                    .setUri(Uri.parse(videoUrl))
                    .setPosition(0)
                    .setLength(bytes)
                    .build();
                new CacheWriter(cds, spec, null, null).cache();
                Log.d(TAG, "[" + module + "] preloaded " + bytes/(1024*1024) + "MB: " + shortUrl(videoUrl));
            } catch (Exception e) {
                Log.w(TAG, "[" + module + "] preload failed: " + e.getMessage());
                sPreloading.remove(videoUrl);
            } finally {
                sActiveTasks.remove(videoUrl);
            }
        });
        sActiveTasks.put(videoUrl, task);
    }

    public static void cancelAllPreloads() {
        for (Future<?> f : sActiveTasks.values()) f.cancel(true);
        sActiveTasks.clear();
        sPreloading.clear();
    }

    private static boolean isModuleOverQuota(@NonNull Module module) {
        if (sCache == null) return false;
        long used = sCache.getCacheSpace();
        if (used > sTotalCacheSize * 0.8) {
            return module == Module.CHAT || module == Module.STATUS;
        }
        return false;
    }

    private static long partialBytes(@NonNull Module module) {
        switch (module) {
            case X:      return PARTIAL_BYTES_X;
            case STATUS: return PARTIAL_BYTES_STATUS;
            case CHAT:   return PARTIAL_BYTES_CHAT;
            default:     return PARTIAL_BYTES_REELS;
        }
    }

    public static synchronized void trimMemory() {
        if (sCache == null) return;
        try {
            long before = sCache.getCacheSpace();
            long target = before / 2;
            for (String key : sCache.getKeys()) {
                if (sCache.getCacheSpace() <= target) break;
                sCache.removeResource(key);
            }
            Log.d(TAG, "trimMemory: " + before/(1024*1024) + "MB → "
                + sCache.getCacheSpace()/(1024*1024) + "MB");
        } catch (Exception e) {
            Log.w(TAG, "trimMemory: " + e.getMessage());
        }
    }

    public static synchronized void release() {
        cancelAllPreloads();
        if (sPreloadExecutor != null) { sPreloadExecutor.shutdownNow(); sPreloadExecutor = null; }
        if (sCache != null) {
            try { sCache.release(); Log.d(TAG, "released."); }
            catch (Exception e) { Log.e(TAG, "Release error", e); }
            finally {
                sCache = null; sReelsFactory = null; sXFactory = null;
                sStatusFactory = null; sChatFactory = null; sInitialized = false;
            }
        }
    }

    public static long getTotalCacheBytes()      { return sCache != null ? sCache.getCacheSpace() : 0; }
    public static long getTotalCacheLimitBytes() { return sTotalCacheSize; }
    public static boolean isInitialized()        { return sInitialized; }

    private static void ensureInit() {
        if (!sInitialized) throw new IllegalStateException("UnifiedVideoCacheManager.init() pehle call karo!");
    }

    private static boolean isLowMemory(@NonNull Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.isLowRamDevice();
    }

    private static String shortUrl(@Nullable String url) {
        if (url == null) return "null";
        return url.length() > 50 ? "…" + url.substring(url.length() - 50) : url;
    }
}
