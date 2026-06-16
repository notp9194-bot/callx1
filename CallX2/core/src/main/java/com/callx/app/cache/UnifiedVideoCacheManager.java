package com.callx.app.cache;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
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
 * UnifiedVideoCacheManager — Persistent video cache for all modules.
 *
 * ROOT CAUSE FIX (v29.1):
 *  ❌ OLD: SimpleCache bina DatabaseProvider ke — cache index NEVER persisted to disk.
 *          App close ho → sab kuch bhool jaata, har baar re-download.
 *  ✅ FIX: StandaloneDatabaseProvider diya → cache index SQLite DB mein save hoti hai.
 *          Ab app restart ke baad bhi cached videos seedha play honge.
 *
 *  ❌ OLD: REELS_CACHE_DIR = "reel_video_cache_v2" — naam badalne se purana cache waste
 *  ✅ FIX: "reel_video_cache" — original naam, existing disk data reuse hogi
 *
 *  ❌ OLD: CacheDataSink fragment size = 6MB → reel 100+ small spans mein toot jaati thi
 *  ✅ FIX: fragment size = Long.MAX_VALUE → ek reel = ek contiguous cached file
 *
 * Cache budget:
 *   Reels : 500MB dedicated (own SimpleCache + own DB)
 *   Others: 300MB shared (X + Status + Chat)
 */
@OptIn(markerClass = UnstableApi.class)
public class UnifiedVideoCacheManager {

    private static final String TAG = "UnifiedVideoCache";

    // ── Reels: dedicated 500MB cache ─────────────────────────────────────────
    private static final long REELS_CACHE_NORM = 500L * 1024 * 1024;
    private static final long REELS_CACHE_LOW  = 300L * 1024 * 1024;
    private static final String REELS_CACHE_DIR = "reel_video_cache";   // original naam — don't rename
    private static final String REELS_DB_NAME   = "reel_cache.db";

    // ── Other modules: shared 300MB cache ────────────────────────────────────
    private static final long OTHER_CACHE_NORM = 300L * 1024 * 1024;
    private static final long OTHER_CACHE_LOW  =  80L * 1024 * 1024;
    private static final String OTHER_CACHE_DIR = "other_video_cache";
    private static final String OTHER_DB_NAME   = "other_cache.db";

    // Preload bytes per module
    private static final long PARTIAL_BYTES_REELS  =  6L * 1024 * 1024; // 6MB — smooth autoplay
    private static final long PARTIAL_BYTES_X      =  5L * 1024 * 1024;
    private static final long PARTIAL_BYTES_STATUS =  3L * 1024 * 1024;
    private static final long PARTIAL_BYTES_CHAT   =  8L * 1024 * 1024;
    /** Duet originals — compositor needs large chunk */
    public  static final long PARTIAL_BYTES_DUET   = 50L * 1024 * 1024;

    // ── Two separate SimpleCache instances ───────────────────────────────────
    private static SimpleCache             sReelsCache;
    private static SimpleCache             sOtherCache;

    private static StandaloneDatabaseProvider sReelsDb;
    private static StandaloneDatabaseProvider sOtherDb;

    private static CacheDataSource.Factory sReelsFactory;
    private static CacheDataSource.Factory sXFactory;
    private static CacheDataSource.Factory sStatusFactory;
    private static CacheDataSource.Factory sChatFactory;
    private static boolean                 sInitialized = false;
    private static long                    sReelsCacheSize;

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
            long otherSize  = lowMem ? OTHER_CACHE_LOW  : OTHER_CACHE_NORM;

            // ── Reels cache (500MB) with persistent DB ──────────────────────
            File reelsCacheDir = new File(app.getCacheDir(), REELS_CACHE_DIR);
            if (!reelsCacheDir.exists()) reelsCacheDir.mkdirs();
            // StandaloneDatabaseProvider: cache index SQLite mein save hoti hai
            // Bina iske app restart pe cache empty maan li jaati thi → re-download!
            sReelsDb    = new StandaloneDatabaseProvider(app);
            sReelsCache = new SimpleCache(reelsCacheDir,
                    new LeastRecentlyUsedCacheEvictor(sReelsCacheSize),
                    sReelsDb);

            // ── Other cache (300MB) with persistent DB ──────────────────────
            File otherCacheDir = new File(app.getCacheDir(), OTHER_CACHE_DIR);
            if (!otherCacheDir.exists()) otherCacheDir.mkdirs();
            sOtherDb    = new StandaloneDatabaseProvider(app);
            sOtherCache = new SimpleCache(otherCacheDir,
                    new LeastRecentlyUsedCacheEvictor(otherSize),
                    sOtherDb);

            DefaultHttpDataSource.Factory httpFactory =
                new DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(20_000)
                    .setAllowCrossProtocolRedirects(true);

            // fragment size = Long.MAX_VALUE: ek reel contiguous cached rehti hai
            // Chhote fragments se seeking + extraction dono broken ho jaati thi
            sReelsFactory  = buildFactory(httpFactory, sReelsCache,  Long.MAX_VALUE);
            sXFactory      = buildFactory(httpFactory, sOtherCache,  Long.MAX_VALUE);
            sStatusFactory = buildFactory(httpFactory, sOtherCache,  Long.MAX_VALUE);
            sChatFactory   = buildFactory(httpFactory, sOtherCache,  Long.MAX_VALUE);

            sPreloadExecutor = Executors.newFixedThreadPool(2);
            sInitialized = true;

            Log.i(TAG, "UnifiedVideoCacheManager init OK:"
                + " reels=" + sReelsCacheSize / (1024 * 1024) + "MB"
                + " other=" + otherSize / (1024 * 1024) + "MB"
                + " dir=" + reelsCacheDir.getAbsolutePath()
                + " dbReady=" + (sReelsDb != null));

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

    /** Preload partial bytes. Use isDuetOriginal=true for 50MB duet preload. */
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
        catch (Exception e) { Log.e(TAG, "Release reels cache", e); }
        try { if (sOtherCache != null) { sOtherCache.release(); sOtherCache = null; } }
        catch (Exception e) { Log.e(TAG, "Release other cache", e); }
        sReelsFactory = null; sXFactory = null; sStatusFactory = null; sChatFactory = null;
        sReelsDb = null; sOtherDb = null;
        sInitialized = false;
        Log.d(TAG, "released.");
    }

    public static long getReelsCacheBytes()      { return sReelsCache != null ? sReelsCache.getCacheSpace() : 0; }
    public static long getReelsCacheLimitBytes() { return sReelsCacheSize; }
    public static long getTotalCacheBytes()      { long r = getReelsCacheBytes(); long o = sOtherCache != null ? sOtherCache.getCacheSpace() : 0; return r + o; }
    public static long getTotalCacheLimitBytes() { return sReelsCacheSize + OTHER_CACHE_NORM; }
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
