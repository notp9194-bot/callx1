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
 *  ⚠️  THEN: fragment size = Long.MAX_VALUE fixed that, but silently caused a
 *          NEW bug — reel ka cache tabhi persistent index mein commit hota
 *          tha jab poora download close ho jaye; app kill mid-download pe
 *          sab discard ho jaata tha (0 MB after reopen).
 *  ✅ FIX: fragment size = 20MB (reels) — chhoti reels ab bhi ek fragment
 *          mein rehti hain (seek/extraction theek), lambi reels periodically
 *          checkpoint hoti hain (kill-safe).
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

    // Fragment (checkpoint) size for cache writes — see the FIX comment in
    // init() for why this can't be Long.MAX_VALUE. 20MB comfortably covers
    // a full short-form reel in one fragment (no seek/extraction issue);
    // anything longer gets periodic commit points instead of all-or-nothing.
    private static final long FRAGMENT_SIZE_REELS = 20L * 1024 * 1024;
    private static final long FRAGMENT_SIZE_OTHER = 12L * 1024 * 1024;

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

    // Retained for ReelCacheEvictionLog (Cache Status menu — explains WHY a
    // reel is missing from cache) and other diagnostics that need a Context
    // after init() has already returned.
    private static Context sAppContext;

    public enum Module { REELS, X, STATUS, CHAT }

    private UnifiedVideoCacheManager() {}

    public static synchronized void init(@NonNull Context context) {
        if (sInitialized) return;
        try {
            Context app = context.getApplicationContext();
            sAppContext = app;
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

            // ✅ ROOT-CAUSE FIX: fragment size WAS Long.MAX_VALUE ("one reel =
            // one contiguous cached file", fixed the seeking/extraction bug).
            // Side effect nobody caught: with an unbounded fragment, the
            // CacheDataSink only renames its temp file + commits the span
            // into the PERSISTENT (SQLite) index when that giant fragment
            // finishes writing and closes cleanly. If the app process is
            // killed (swipe-close from recents, OS kill) while a reel is
            // still downloading — even at 90% — nothing had been committed
            // yet, so the ENTIRE reel's cached bytes are discarded on next
            // launch. That's why "cache in use" grows while browsing but
            // resets toward 0 after a real app close+reopen.
            // FIX: bound it. Typical reels (well under FRAGMENT_SIZE_REELS)
            // still land in a single fragment — same seek/extraction
            // behaviour as before — but anything longer now checkpoints
            // periodically, so a kill mid-download only loses the
            // in-progress fragment, not everything watched so far.
            sReelsFactory  = buildFactory(httpFactory, sReelsCache,  FRAGMENT_SIZE_REELS);
            sXFactory      = buildFactory(httpFactory, sOtherCache,  FRAGMENT_SIZE_OTHER);
            sStatusFactory = buildFactory(httpFactory, sOtherCache,  FRAGMENT_SIZE_OTHER);
            sChatFactory   = buildFactory(httpFactory, sOtherCache,  FRAGMENT_SIZE_OTHER);

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
            if (sAppContext != null) {
                ReelCacheEvictionLog.recordTrim(sAppContext,
                    "Memory cleanup (OS low-memory signal / app swipe-closed)");
            }
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

    /**
     * Cache Status (3-dot menu) support: bytes currently cached on disk for
     * this exact URL, or 0 if nothing is cached for it. Uses the default
     * CacheKeyFactory behaviour (no custom key set in buildFactory()), which
     * keys spans by the request URI string — same key ExoPlayer's
     * CacheDataSource used when writing it.
     */
    public static long getCachedBytesForUrl(@Nullable String url) {
        if (url == null || sReelsCache == null) return 0;
        try {
            long total = 0;
            for (androidx.media3.datasource.cache.CacheSpan span : sReelsCache.getCachedSpans(url)) {
                total += span.length;
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Total expected size of the resource in bytes (from the HTTP response
     * that was cached), or -1 if unknown. Needed to tell "fully cached, safe
     * to replay with zero data use" apart from "just the 6MB autoplay
     * prewarm chunk is cached" — BUGFIX: getCachedBytesForUrl() alone can't
     * distinguish these, which previously showed a misleading "✅ Cached —
     * 0 MB stored" for reels that only had a small partial preload.
     */
    public static long getContentLengthForUrl(@Nullable String url) {
        if (url == null || sReelsCache == null) return -1;
        try {
            androidx.media3.datasource.cache.ContentMetadata metadata = sReelsCache.getContentMetadata(url);
            return androidx.media3.datasource.cache.ContentMetadata.getContentLength(metadata);
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isReelCached(@Nullable String url) {
        return getCachedBytesForUrl(url) > 0;
    }

    /** True only if the ENTIRE resource is on disk — replaying uses zero data. */
    public static boolean isReelFullyCached(@Nullable String url) {
        long cached = getCachedBytesForUrl(url);
        long total  = getContentLengthForUrl(url);
        return cached > 0 && total > 0 && cached >= total;
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
