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
 * Cache budget:
 *   Reels  : 500MB dedicated (user requirement — only reels get 500MB)
 *   X      : 150MB
 *   Status : 100MB
 *   Chat   :  50MB
 *   Total  : 800MB (low-end: 500MB reels only, rest 100MB shared)
 *
 * Key changes v29:
 *  ✅ Reels get their own 500MB SimpleCache — totally isolated, no quota sharing
 *  ✅ X / Status / Chat share a separate 300MB pool
 *  ✅ Reels cache NEVER evicted for other modules
 *  ✅ Partial caching: Reels 6MB (longer buffer → smoother playback)
 *  ✅ Duet originals get full-video preload (up to 50MB) via dedicated method
 */
@OptIn(markerClass = UnstableApi.class)
public class UnifiedVideoCacheManager {

    private static final String TAG = "UnifiedVideoCache";

    // ── Reels: dedicated 500MB cache ─────────────────────────────────────────
    private static final long REELS_CACHE_NORM = 500L * 1024 * 1024; // 500MB
    private static final long REELS_CACHE_LOW  = 300L * 1024 * 1024; // 300MB low-end
    private static final String REELS_CACHE_DIR = "reel_video_cache_v2";

    // ── Other modules: shared 300MB cache ────────────────────────────────────
    private static final long OTHER_CACHE_NORM = 300L * 1024 * 1024; // 300MB
    private static final long OTHER_CACHE_LOW  = 100L * 1024 * 1024; // 100MB low-end
    private static final String OTHER_CACHE_DIR = "other_video_cache";

    // Partial cache bytes per module
    private static final long PARTIAL_BYTES_REELS  = 6L * 1024 * 1024; //  6MB per reel (smooth)
    private static final long PARTIAL_BYTES_X      = 5L * 1024 * 1024; //  5MB
    private static final long PARTIAL_BYTES_STATUS = 3L * 1024 * 1024; //  3MB
    private static final long PARTIAL_BYTES_CHAT   = 8L * 1024 * 1024; //  8MB
    /** Duet originals — full video preload (compositor needs full file) */
    public  static final long PARTIAL_BYTES_DUET   = 50L * 1024 * 1024; // 50MB

    // ── Two separate SimpleCache instances ───────────────────────────────────
    private static SimpleCache             sReelsCache;   // 500MB — reels only
    private static SimpleCache             sOtherCache;   // 300MB — X, Status, Chat

    private static CacheDataSource.Factory sReelsFactory;
    private static CacheDataSource.Factory sXFactory;
    private static CacheDataSource.Factory sStatusFactory;
    private static CacheDataSource.Factory sChatFactory;
    private static boolean                 sInitialized = false;
    private static long                    sReelsCacheSize;
    private static long                    sOtherCacheSize;

    private static ExecutorService sPreloadExecutor;
    private static final ConcurrentHashMap<String, Future<?>> sActiveTasks = new ConcurrentHashMap<>();
    private static final java.util.Set<String> sPreloading = ConcurrentHashMap.newKeySet();

    public enum Module { REELS, X, STATUS, CHAT }

    private UnifiedVideoCacheManager() {}

    public static synchronized void init(@NonNull Context context) {
        if (sInitialized) return;
        try {
            Context app = context.getApplicationContext();
            boolean lowMem = isLowMemory(app);

            sReelsCacheSize = lowMem ? REELS_CACHE_LOW : REELS_CACHE_NORM;
            sOtherCacheSize = lowMem ? OTHER_CACHE_LOW : OTHER_CACHE_NORM;

            // Reels: dedicated cache
            File reelsCacheDir = new File(app.getCacheDir(), REELS_CACHE_DIR);
            if (!reelsCacheDir.exists()) reelsCacheDir.mkdirs();
            sReelsCache = new SimpleCache(reelsCacheDir,
                    new LeastRecentlyUsedCacheEvictor(sReelsCacheSize));

            // X / Status / Chat: shared cache
            File otherCacheDir = new File(app.getCacheDir(), OTHER_CACHE_DIR);
            if (!otherCacheDir.exists()) otherCacheDir.mkdirs();
            sOtherCache = new SimpleCache(otherCacheDir,
                    new LeastRecentlyUsedCacheEvictor(sOtherCacheSize));

            DefaultHttpDataSource.Factory httpFactory =
                new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true);

            sReelsFactory  = buildFactory(httpFactory, sReelsCache,  PARTIAL_BYTES_REELS);
            sXFactory      = buildFactory(httpFactory, sOtherCache,  PARTIAL_BYTES_X);
            sStatusFactory = buildFactory(httpFactory, sOtherCache,  PARTIAL_BYTES_STATUS);
            sChatFactory   = buildFactory(httpFactory, sOtherCache,  PARTIAL_BYTES_CHAT);

            sPreloadExecutor = Executors.newFixedThreadPool(3); // 3 threads: 2 reels + 1 other
            sInitialized = true;

            Log.i(TAG, "UnifiedVideoCacheManager init:"
                + " Reels=" + sReelsCacheSize / (1024 * 1024) + "MB"
                + " Other=" + sOtherCacheSize / (1024 * 1024) + "MB");
        } catch (Exception e) {
            Log.e(TAG, "Init failed", e);
        }
    }

    private static CacheDataSource.Factory buildFactory(
            DefaultHttpDataSource.Factory httpFactory,
            SimpleCache cache,
            long fragmentSize) {
        return new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheWriteDataSinkFactory(
                new CacheDataSink.Factory()
                    .setCache(cache)
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

    /** Returns the Reels-dedicated SimpleCache. Used by ReelCacheManager. */
    @NonNull
    public static SimpleCache getSimpleCache() {
        ensureInit();
        return sReelsCache;
    }

    /** Returns the Other-modules SimpleCache (X, Status, Chat). */
    @NonNull
    public static SimpleCache getOtherSimpleCache() {
        ensureInit();
        return sOtherCache;
    }

    /**
     * Preload partial bytes for a reel video.
     * Normal reels: 6MB. Duet originals: pass isDuetOriginal=true → 50MB.
     */
    public static void preloadPartial(@NonNull Context ctx,
                                      @Nullable String videoUrl,
                                      @NonNull Module module) {
        preloadPartial(ctx, videoUrl, module, false);
    }

    public static void preloadPartial(@NonNull Context ctx,
                                      @Nullable String videoUrl,
                                      @NonNull Module module,
                                      boolean isDuetOriginal) {
        if (videoUrl == null || videoUrl.isEmpty()) return;
        if (!sInitialized) init(ctx);
        if (sPreloading.contains(videoUrl)) return;

        sPreloading.add(videoUrl);
        final long bytes = isDuetOriginal ? PARTIAL_BYTES_DUET : partialBytes(module);

        Future<?> task = sPreloadExecutor.submit(() -> {
            try {
                CacheDataSource cds = getFactory(module).createDataSource();
                DataSpec spec = new DataSpec.Builder()
                    .setUri(Uri.parse(videoUrl))
                    .setPosition(0)
                    .setLength(bytes)
                    .build();
                new CacheWriter(cds, spec, null, null).cache();
                Log.d(TAG, "[" + module + (isDuetOriginal ? "/duet" : "") + "] preloaded "
                    + bytes / (1024 * 1024) + "MB: " + shortUrl(videoUrl));
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

    private static long partialBytes(@NonNull Module module) {
        switch (module) {
            case X:      return PARTIAL_BYTES_X;
            case STATUS: return PARTIAL_BYTES_STATUS;
            case CHAT:   return PARTIAL_BYTES_CHAT;
            default:     return PARTIAL_BYTES_REELS;
        }
    }

    public static synchronized void trimMemory() {
        try {
            if (sReelsCache != null) {
                long before = sReelsCache.getCacheSpace();
                long target = before / 2;
                for (String key : sReelsCache.getKeys()) {
                    if (sReelsCache.getCacheSpace() <= target) break;
                    sReelsCache.removeResource(key);
                }
                Log.d(TAG, "trimMemory reels: " + before/(1024*1024) + "MB → "
                    + sReelsCache.getCacheSpace()/(1024*1024) + "MB");
            }
            if (sOtherCache != null) {
                long before = sOtherCache.getCacheSpace();
                long target = before / 2;
                for (String key : sOtherCache.getKeys()) {
                    if (sOtherCache.getCacheSpace() <= target) break;
                    sOtherCache.removeResource(key);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "trimMemory: " + e.getMessage());
        }
    }

    public static synchronized void release() {
        cancelAllPreloads();
        if (sPreloadExecutor != null) { sPreloadExecutor.shutdownNow(); sPreloadExecutor = null; }
        try { if (sReelsCache != null) { sReelsCache.release(); sReelsCache = null; } }
        catch (Exception e) { Log.e(TAG, "Release reels cache error", e); }
        try { if (sOtherCache != null) { sOtherCache.release(); sOtherCache = null; } }
        catch (Exception e) { Log.e(TAG, "Release other cache error", e); }
        sReelsFactory = null; sXFactory = null;
        sStatusFactory = null; sChatFactory = null;
        sInitialized = false;
        Log.d(TAG, "released.");
    }

    public static long getReelsCacheBytes()      { return sReelsCache != null ? sReelsCache.getCacheSpace() : 0; }
    public static long getReelsCacheLimitBytes() { return sReelsCacheSize; }
    public static long getOtherCacheBytes()      { return sOtherCache != null ? sOtherCache.getCacheSpace() : 0; }
    public static long getTotalCacheBytes()      { return getReelsCacheBytes() + getOtherCacheBytes(); }
    public static long getTotalCacheLimitBytes() { return sReelsCacheSize + sOtherCacheSize; }
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
