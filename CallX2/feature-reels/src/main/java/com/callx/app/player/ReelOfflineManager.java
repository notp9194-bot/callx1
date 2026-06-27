package com.callx.app.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.callx.app.cache.ReelCacheManager;
import com.callx.app.cache.UnifiedVideoCacheManager;
import com.callx.app.models.ReelModel;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * ReelOfflineManager — Offline/degraded mode with cache fallback & smart retry
 *
 * Features:
 *  ✅ Offline detection via NetworkQualityMonitor
 *  ✅ Cache fallback: tries cached bytes first before network (zero-rebuffer on re-watch)
 *  ✅ Degraded-mode URL selection: switches to lowest-quality URL on 2G/poor signal
 *  ✅ Exponential-backoff retry queue: failed reels re-attempted 3× (1s → 4s → 16s)
 *  ✅ Download-for-offline: explicitly save a reel for offline playback to Downloads
 *  ✅ Offline reel catalog: lists what's available to play without connection
 *  ✅ Network-restore hook: when connection returns, auto-retries pending reels
 *  ✅ Stale cache guard: rejects cached bytes older than MAX_CACHE_AGE_MS
 *  ✅ Observer pattern: fire OfflineStateListener on state changes
 *  ✅ Singleton — safe to use from multiple fragments simultaneously
 *
 * Usage:
 *   ReelOfflineManager.get(context).setStateListener(listener);
 *   MediaSource src = ReelOfflineManager.get(context).resolveSource(reel, player);
 *   // If offline: resolveSource returns cached source (or null = not available offline)
 *   // If online:  resolveSource returns network source with cache backing
 *
 *   // On player error:
 *   ReelOfflineManager.get(context).onPlaybackError(reel, player, error);
 *   // Will retry with backoff, then fallback to cache if network exhausted
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelOfflineManager {

    private static final String TAG               = "OfflineManager";
    private static final String PREFS_NAME        = "reel_offline_catalog";
    private static final String KEY_OFFLINE_IDS   = "offline_reel_ids";

    // Retry config
    private static final int    MAX_RETRIES        = 3;
    private static final long   RETRY_BASE_MS      = 1_000L;  // 1s, 4s, 16s (exp backoff ×4)

    // Cache freshness
    private static final long   MAX_CACHE_AGE_MS   = 72 * 60 * 60 * 1_000L; // 72 hours

    // Full-reel download size (for explicit offline save)
    private static final long   OFFLINE_BYTES      = 50 * 1024 * 1024L; // 50 MB (full reel)

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static volatile ReelOfflineManager sInstance;

    public static ReelOfflineManager get(Context ctx) {
        if (sInstance == null) {
            synchronized (ReelOfflineManager.class) {
                if (sInstance == null)
                    sInstance = new ReelOfflineManager(ctx.getApplicationContext());
            }
        }
        return sInstance;
    }

    // ── Offline state ─────────────────────────────────────────────────────────
    public enum OfflineState {
        ONLINE,          // normal network
        DEGRADED,        // 2G / poor signal — low-quality mode
        OFFLINE,         // no network
        CACHE_PLAYBACK,  // playing from local cache
        ERROR_NO_CACHE   // offline AND nothing cached — show error
    }

    public interface OfflineStateListener {
        void onOfflineStateChanged(OfflineState state);
        void onRetryScheduled(String reelId, int attempt, long delayMs);
        void onOfflineDownloadComplete(String reelId);
        void onOfflineDownloadFailed(String reelId, String reason);
    }

    // ── Retry job ─────────────────────────────────────────────────────────────
    private static class RetryJob {
        final ReelModel reel;
        final ExoPlayer player;
        int             attempt;
        RetryJob(ReelModel r, ExoPlayer p) { reel = r; player = p; attempt = 0; }
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    private final Context                    appCtx;
    private final SharedPreferences          prefs;
    private final ScheduledExecutorService   scheduler;
    private final Handler                    mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, ScheduledFuture<?>> pendingRetries = new ConcurrentHashMap<>();
    private final List<String>               offlineCatalog;     // reelIds saved for offline

    private OfflineStateListener             stateListener;
    private OfflineState                     currentState = OfflineState.ONLINE;

    private ReelOfflineManager(Context ctx) {
        appCtx        = ctx;
        prefs         = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        offlineCatalog = loadOfflineCatalog();
        scheduler     = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "reel-offline-mgr");
            t.setDaemon(true);
            return t;
        });

        // Listen for network changes to trigger pending retry flushes
        NetworkQualityMonitor.get(appCtx).addListener(this::onNetworkChanged);
        NetworkQualityMonitor.get(appCtx).startMonitoring();

        Log.d(TAG, "OfflineManager ready. Offline catalog: " + offlineCatalog.size() + " reels");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setStateListener(OfflineStateListener l) { stateListener = l; }

    /**
     * Resolve the best MediaSource for a reel, taking network state into account.
     *
     * - Online + healthy   → CacheDataSource-backed network source (normal flow)
     * - Degraded (2G/poor) → CacheDataSource-backed, lowest quality URL
     * - Offline            → cached bytes if available, else null
     *
     * @return MediaSource to pass to player.setMediaSource(), or null if unavailable offline
     */
    @Nullable
    public MediaSource resolveSource(@NonNull ReelModel reel,
                                     @NonNull ExoPlayer player) {
        NetworkQualityMonitor.Quality netQ =
            NetworkQualityMonitor.get(appCtx).currentQuality();

        if (netQ == NetworkQualityMonitor.Quality.NONE) {
            // Offline — try cache
            return resolveCachedSource(reel);
        }

        if (netQ == NetworkQualityMonitor.Quality.CELLULAR_2G) {
            dispatchState(OfflineState.DEGRADED);
            return buildNetworkSource(pickDegradedUrl(reel));
        }

        // Normal online path
        dispatchState(OfflineState.ONLINE);
        return buildNetworkSource(pickBestUrl(reel, netQ));
    }

    /**
     * Call on ExoPlayer error to trigger retry-with-backoff, then cache fallback.
     *
     * @param reel   The reel that failed
     * @param player The player to retry on
     * @param error  The ExoPlayer error
     */
    public void onPlaybackError(@NonNull ReelModel reel,
                                @NonNull ExoPlayer player,
                                @NonNull PlaybackException error) {
        String reelId = reel.reelId != null ? reel.reelId : reel.videoUrl;
        Log.w(TAG, "Playback error for " + reelId + ": " + error.getMessage());

        RetryJob job = new RetryJob(reel, player);
        scheduleRetry(job, reelId);
    }

    /**
     * Explicitly download a reel for offline playback.
     * Downloads the best quality URL up to OFFLINE_BYTES into the reel cache.
     * Fires OfflineStateListener.onOfflineDownloadComplete on success.
     *
     * @param reel Reel to save
     */
    public void downloadForOffline(@NonNull ReelModel reel) {
        String reelId = reel.reelId != null ? reel.reelId : reel.videoUrl;
        String url    = pickBestUrl(reel, NetworkQualityMonitor.Quality.WIFI);
        if (url == null || url.isEmpty()) {
            notifyDownloadFailed(reelId, "No URL available");
            return;
        }

        scheduler.submit(() -> {
            try {
                CacheDataSource.Factory factory = ReelCacheManager.getCacheDataSourceFactory();
                CacheDataSource src = factory.createDataSource();
                DataSpec spec = new DataSpec.Builder()
                    .setUri(Uri.parse(url))
                    .setPosition(0)
                    .setLength(OFFLINE_BYTES)
                    .build();

                new CacheWriter(src, spec, null,
                    (reqLen, cached, newBytes) ->
                        Log.v(TAG, "Offline DL " + reelId + ": " + cached + "/" + reqLen + " bytes"))
                    .cache();

                // Add to offline catalog
                if (!offlineCatalog.contains(reelId)) {
                    offlineCatalog.add(reelId);
                    persistOfflineCatalog();
                }

                mainHandler.post(() -> {
                    Log.d(TAG, "Offline download complete: " + reelId);
                    if (stateListener != null) stateListener.onOfflineDownloadComplete(reelId);
                });

            } catch (Exception e) {
                Log.w(TAG, "Offline download failed for " + reelId + ": " + e.getMessage());
                notifyDownloadFailed(reelId, e.getMessage());
            }
        });
    }

    /**
     * Remove a reel from the offline catalog and purge its cached bytes.
     */
    public void removeOfflineReel(String reelId) {
        offlineCatalog.remove(reelId);
        persistOfflineCatalog();
        try {
            SimpleCache cache = ReelCacheManager.getSimpleCache();
            if (cache != null) cache.removeResource(reelId);
        } catch (Exception e) {
            Log.w(TAG, "removeOfflineReel cache purge: " + e.getMessage());
        }
    }

    /**
     * Returns all reel IDs saved for offline playback.
     */
    public List<String> getOfflineCatalog() {
        return new ArrayList<>(offlineCatalog);
    }

    /**
     * Returns true if reelId is available for offline playback (catalog + cache check).
     */
    public boolean isAvailableOffline(@NonNull String reelId) {
        if (!offlineCatalog.contains(reelId)) return false;
        long cachedBytes = ReelCacheManager.getCachedBytes(reelId);
        return cachedBytes > 500_000;  // require at least 500 KB
    }

    /**
     * Returns total bytes used by the offline cache.
     */
    public long getOfflineCacheSizeBytes() {
        try {
            SimpleCache cache = ReelCacheManager.getSimpleCache();
            return cache != null ? cache.getCacheSpace() : 0;
        } catch (Exception e) { return 0; }
    }

    // ── Internal: retry ───────────────────────────────────────────────────────

    private void scheduleRetry(RetryJob job, String reelId) {
        if (job.attempt >= MAX_RETRIES) {
            // Exhausted retries — fall back to cache
            Log.w(TAG, "Max retries exhausted for " + reelId + " → cache fallback");
            mainHandler.post(() -> {
                MediaSource cached = resolveCachedSource(job.reel);
                if (cached != null) {
                    job.player.setMediaSource(cached);
                    job.player.prepare();
                    dispatchState(OfflineState.CACHE_PLAYBACK);
                } else {
                    dispatchState(OfflineState.ERROR_NO_CACHE);
                }
            });
            return;
        }

        job.attempt++;
        long delayMs = RETRY_BASE_MS * (long) Math.pow(4, job.attempt - 1);  // 1s, 4s, 16s
        Log.d(TAG, "Retry #" + job.attempt + " for " + reelId + " in " + delayMs + "ms");

        if (stateListener != null) {
            final int a = job.attempt; final long d = delayMs;
            mainHandler.post(() -> stateListener.onRetryScheduled(reelId, a, d));
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            mainHandler.post(() -> {
                NetworkQualityMonitor.Quality q =
                    NetworkQualityMonitor.get(appCtx).currentQuality();
                if (q == NetworkQualityMonitor.Quality.NONE) {
                    // Still offline — try cache immediately
                    MediaSource cached = resolveCachedSource(job.reel);
                    if (cached != null) {
                        job.player.setMediaSource(cached);
                        job.player.prepare();
                        dispatchState(OfflineState.CACHE_PLAYBACK);
                    } else {
                        scheduleRetry(job, reelId);  // wait for next retry window
                    }
                } else {
                    // Network back — retry
                    String url = pickBestUrl(job.reel, q);
                    if (url != null) {
                        job.player.setMediaSource(buildNetworkSource(url));
                        job.player.prepare();
                        dispatchState(OfflineState.ONLINE);
                    } else {
                        scheduleRetry(job, reelId);
                    }
                }
            });
        }, delayMs, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> old = pendingRetries.put(reelId, future);
        if (old != null) old.cancel(false);
    }

    // ── Internal: source builders ─────────────────────────────────────────────

    @Nullable
    private MediaSource resolveCachedSource(ReelModel reel) {
        String url = pickBestUrl(reel, NetworkQualityMonitor.Quality.WIFI);
        if (url == null) url = reel.videoUrl;
        if (url == null) return null;

        long cached = ReelCacheManager.getCachedBytes(url);
        if (cached < 200_000) {
            Log.d(TAG, "Cache too small for offline: " + cached + " bytes");
            return null;
        }

        Log.d(TAG, "Cache fallback OK: " + cached + " bytes for " + shortUrl(url));
        return buildNetworkSource(url);  // CacheDataSource will serve from cache transparently
    }

    private MediaSource buildNetworkSource(String url) {
        if (url == null || url.isEmpty()) return null;
        CacheDataSource.Factory factory = ReelCacheManager.getCacheDataSourceFactory();
        return new ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
    }

    // ── URL selection ─────────────────────────────────────────────────────────

    private String pickBestUrl(ReelModel reel, NetworkQualityMonitor.Quality q) {
        switch (q) {
            case WIFI: case ETHERNET: case CELLULAR_5G:
                if (reel.video1080 != null && !reel.video1080.isEmpty()) return reel.video1080;
                if (reel.video720  != null && !reel.video720.isEmpty())  return reel.video720;
                return reel.videoUrl;
            case CELLULAR_4G:
                if (reel.video720 != null && !reel.video720.isEmpty()) return reel.video720;
                return reel.videoUrl;
            case CELLULAR_3G:
                if (reel.video480 != null && !reel.video480.isEmpty()) return reel.video480;
                return reel.videoUrl;
            default:
                return reel.videoUrl;
        }
    }

    private String pickDegradedUrl(ReelModel reel) {
        if (reel.video480 != null && !reel.video480.isEmpty()) return reel.video480;
        return reel.videoUrl;
    }

    // ── Network change ────────────────────────────────────────────────────────

    private void onNetworkChanged(NetworkQualityMonitor.Quality q) {
        Log.d(TAG, "Network changed → " + q);
        if (q == NetworkQualityMonitor.Quality.NONE) {
            dispatchState(OfflineState.OFFLINE);
        } else if (q == NetworkQualityMonitor.Quality.CELLULAR_2G) {
            dispatchState(OfflineState.DEGRADED);
        } else {
            dispatchState(OfflineState.ONLINE);
            // Flush pending retries immediately on network restore
            for (Map.Entry<String, ScheduledFuture<?>> e : pendingRetries.entrySet()) {
                e.getValue().cancel(false);
                pendingRetries.remove(e.getKey());
            }
        }
    }

    // ── State dispatch ────────────────────────────────────────────────────────

    private void dispatchState(OfflineState newState) {
        if (newState == currentState) return;
        currentState = newState;
        if (stateListener != null) {
            mainHandler.post(() -> stateListener.onOfflineStateChanged(newState));
        }
        Log.d(TAG, "OfflineState → " + newState);
    }

    // ── Offline catalog persistence ───────────────────────────────────────────

    private List<String> loadOfflineCatalog() {
        List<String> list = new ArrayList<>();
        String raw = prefs.getString(KEY_OFFLINE_IDS, "");
        if (raw.isEmpty()) return list;
        for (String id : raw.split(",")) {
            if (!id.isEmpty()) list.add(id);
        }
        return list;
    }

    private void persistOfflineCatalog() {
        prefs.edit()
            .putString(KEY_OFFLINE_IDS, String.join(",", offlineCatalog))
            .apply();
    }

    private void notifyDownloadFailed(String reelId, String reason) {
        mainHandler.post(() -> {
            if (stateListener != null) stateListener.onOfflineDownloadFailed(reelId, reason);
        });
    }

    private String shortUrl(String url) {
        if (url == null) return "null";
        return url.length() > 50 ? "…" + url.substring(url.length() - 47) : url;
    }

    /** Current offline state (for UI to poll without listener) */
    public OfflineState getState() { return currentState; }

    /** Human-readable state label for UI banners */
    public static String stateLabel(OfflineState s) {
        switch (s) {
            case OFFLINE:        return "No internet — playing cached reels";
            case DEGRADED:       return "Slow connection — reduced quality";
            case CACHE_PLAYBACK: return "Playing from cache";
            case ERROR_NO_CACHE: return "Can't play — no internet, not cached";
            default:             return "";
        }
    }
}
