package com.callx.app.cache;

import com.callx.app.feed.ReelsFragment;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.DataSpec;

import com.callx.app.cache.UnifiedVideoCacheManager;
import com.callx.app.models.ReelModel;
import com.callx.app.player.AdaptiveStreamingManager;
import com.callx.app.player.NetworkQualityMonitor;
import com.callx.app.player.ReelThermalManager;
import com.callx.app.utils.VideoUploader;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReelVideoPreloader — Instagram-style reel pre-fetching.
 *
 * Kaam kaise karta hai (exactly Instagram ki tarah):
 *   1. User reel N dekh raha hai
 *   2. Hum background mein reel N+1, N+2, N+3 ke pehle 3MB download kar lete hain
 *   3. Jab user scroll karta hai → video INSTANTLY start hoti hai (buffering nahi)
 *   4. Pehle se cache mein bytes hain → ExoPlayer turant play karta hai
 *
 * Features:
 *  ✅ Sirf agle PRELOAD_COUNT reels preload hote hain (bandwidth waste nahi)
 *  ✅ Already cached reels ko dobara download nahi karta
 *  ✅ Currently preloading urls track karta hai (duplicate downloads nahi)
 *  ✅ Network-aware: sirf WiFi par aggressive preload (optional — commented)
 *  ✅ Background thread pool (2 threads) — main thread block nahi hota
 *  ✅ Cancel support — jab feed switch ho to purane preloads cancel
 *
 * Usage (ReelsFragment mein):
 *   // Field:
 *   private ReelVideoPreloader preloader;
 *
 *   // onCreateView ke baad:
 *   preloader = new ReelVideoPreloader(requireContext());
 *
 *   // onPageSelected callback mein:
 *   preloader.preloadFrom(currentList, position);
 *
 *   // onDestroyView mein:
 *   preloader.cancelAll();
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelVideoPreloader {

    private static final String TAG           = "ReelVideoPreloader";
    // FIX: PRELOAD_COUNT 4→2. offscreenPageLimit=1 ke baad N+2/N+3 fragments
    // exist nahi karte — unke liye 10MB download karna pure waste tha.
    // Sirf N+1 aur N+2 ke liye preload — N+1 hamesha exist karta hai,
    // N+2 tab banata hai jab user N+1 par hota hai. Practically safer.
    private static final int    PRELOAD_COUNT = 2;
    // FIX: PRELOAD_BYTES 10MB→4MB. 10MB per reel × 4 reels = 40MB concurrent
    // downloads tha. Ab 4MB × 2 = 8MB — smooth autoplay ke liye kaafi hai
    // (ExoPlayer ke 1-2s buffer ke liye sirf ~2-3MB chahiye typical 720p reel mein).
    private static final long   PRELOAD_BYTES = 4 * 1024 * 1024L; // Pehle 4MB preload
    private static final long   PRELOAD_BYTES_WIFI = 4 * 1024 * 1024L; // WiFi/5G: 4MB
    private static final long   PRELOAD_BYTES_4G   =  3 * 1024 * 1024L; // 4G: 3MB
    private static final long   PRELOAD_BYTES_3G   =  1 * 1024 * 1024L; // 3G: 1MB
    private static final long   PRELOAD_BYTES_2G   =    256 * 1024L;    // 2G/slow: 256KB
    /** Duet originals: 50MB — compositor needs the full video for rendering */
    private static final long   PRELOAD_BYTES_DUET = UnifiedVideoCacheManager.PARTIAL_BYTES_DUET;

    private final Context     mContext;
    private final ExecutorService mExecutor;

    // Kaunse URLs already preloading hain ya ho chuke hain
    private final Set<String>              mPreloading = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Future<?>> mActiveTasks = new ConcurrentHashMap<>();

    /** Optional quality hint — set from ReelPlayerController so preloader caches the right URL */
    private AdaptiveStreamingManager.QualityCap mCurrentCap = AdaptiveStreamingManager.QualityCap.AUTO;

    public ReelVideoPreloader(Context context) {
        mContext  = context.getApplicationContext();
        // FIX: threads 3→2. Pehle 3 threads × 10MB = 30MB ek waqt download
        // ho raha tha + ExoPlayer ka apna network = phone ki battery aur
        // CPU dono hit. 2 threads kaafi hain next 2 reels ke liye.
        mExecutor = Executors.newFixedThreadPool(2);
        ReelCacheManager.init(mContext);
    }

    /** Call from ReelPlayerController whenever currentCap changes */
    public void setQualityCap(AdaptiveStreamingManager.QualityCap cap) {
        mCurrentCap = cap != null ? cap : AdaptiveStreamingManager.QualityCap.AUTO;
    }

    /**
     * Main method — position par se PRELOAD_COUNT aage ke reels preload karta hai.
     *
     * @param reels    Current reel list (adapter ki list)
     * @param position Current visible position (jo reel ab dekhi ja rahi hai)
     */
    public void preloadFrom(List<ReelModel> reels, int position) {
        preloadFrom(reels, position, -1f);
    }

    /**
     * @param scrollVelocityPxPerMs current ViewPager2 scroll velocity (see
     *                              ReelsFragment#lastScrollVelocity), used to
     *                              size the AvatarPrefetcher lookahead depth.
     *                              Pass -1 (or use the 2-arg overload) if unknown.
     */
    public void preloadFrom(List<ReelModel> reels, int position, float scrollVelocityPxPerMs) {
        preloadFrom(reels, position, scrollVelocityPxPerMs, -1);
    }

    /**
     * @param estimatedLandingReels see ReelsFragment#lastFlingLandingReels /
     *                              FlingLandingEstimator — a physics-based
     *                              predicted landing reel when available (-1
     *                              otherwise), passed straight through to
     *                              AvatarPrefetcher.
     */
    public void preloadFrom(List<ReelModel> reels, int position, float scrollVelocityPxPerMs, int estimatedLandingReels) {
        if (reels == null || reels.isEmpty()) return;

        // Owner-avatar prefetch — velocity-aware depth (fast fling skips
        // entirely, slow scroll goes deeper than the video byte-preload
        // window below since a cached avatar bitmap is orders of magnitude
        // cheaper than a video byte-range), upgraded to a precise predicted
        // landing reel when a fling deceleration estimate is available. See
        // AvatarPrefetcher / FlingLandingEstimator class docs.
        AvatarPrefetcher.prefetch(mContext, reels, position + 1, scrollVelocityPxPerMs, estimatedLandingReels);

        // ── Instagram-level thermal gate ──────────────────────────────────────
        // If device is HOT (severe thermal / low battery / power-save), skip byte
        // preloading entirely. The current reel plays fine — we just don't prefetch
        // the next ones until the device cools down. ReelThermalManager notifies
        // ReelsFragment which cancels any in-flight downloads, so this early-return
        // also prevents new ones from starting during a throttle event.
        ReelThermalManager thermal = ReelThermalManager.get(mContext);
        if (!thermal.canBytePreload()) {
            Log.d(TAG, "preloadFrom: skipped — thermal=" + thermal.getLevel());
            return;
        }

        // Network-aware bytes: WiFi aggressive, 2G minimal
        NetworkQualityMonitor monitor = NetworkQualityMonitor.get(mContext);
        NetworkQualityMonitor.Quality netQuality = monitor.currentQuality();
        // Reduce bytes on MODERATE thermal even when preloading is allowed
        long bytesToPreload = thermal.canReducedBytePreload()
            ? networkBytes(netQuality)
            : Math.min(networkBytes(netQuality), 2 * 1024 * 1024L); // 2MB cap on moderate

        // Quality-aware URL: preload the URL the player will actually use
        AdaptiveStreamingManager.QualityCap cap = mCurrentCap;

        // Reduce preload count on slow networks to save data
        int preloadCount = netQuality == NetworkQualityMonitor.Quality.CELLULAR_2G ? 1
            : netQuality == NetworkQualityMonitor.Quality.CELLULAR_3G ? 2
            : PRELOAD_COUNT;

        for (int i = position + 1; i <= position + preloadCount && i < reels.size(); i++) {
            ReelModel reel = reels.get(i);
            if (reel == null) continue;

            // ✅ HLS reels: skip this byte-range preloader entirely. It was
            // built to warm-cache a chunk of a single progressive MP4 URL —
            // preloading N raw bytes of a .m3u8 TEXT manifest doesn't cache
            // any actual video segments (those are separate .ts/.m4s URLs
            // only known once ExoPlayer parses the manifest). ExoPlayer's
            // own prepare()-ahead-of-visibility + CacheDataSource already
            // handle segment-level prefetch correctly for HLS; duplicating
            // that here would just waste data on the manifest text itself.
            if (reel.hlsManifestUrl != null && !reel.hlsManifestUrl.isEmpty()) {
                // Duet original still benefits from a legacy byte-range warm
                // — it's always progressive MP4, HLS or not.
                if (reel.duetOriginalUrl != null && !reel.duetOriginalUrl.isEmpty()) {
                    preloadSingle(reel.duetOriginalUrl, PRELOAD_BYTES_DUET);
                }
                continue;
            }

            // Pick quality URL matching current player cap
            String preloadUrl = pickQualityUrl(reel, cap);
            if (preloadUrl != null && !preloadUrl.isEmpty()) {
                preloadSingle(preloadUrl, bytesToPreload);
            }

            // Duet original — compositor needs large chunk for rendering
            if (reel.duetOriginalUrl != null && !reel.duetOriginalUrl.isEmpty()) {
                preloadSingle(reel.duetOriginalUrl, PRELOAD_BYTES_DUET);
                Log.d(TAG, "Duet original preloading (50MB): " + shortUrl(reel.duetOriginalUrl));
            }

            // NOTE (advance #7 — "preload audio track separately"): deliberately
            // NOT byte-preloading reel.musicUrl here via CacheDataSource. Photo
            // reels' background music plays through android.media.MediaPlayer
            // (ReelPlayerController.startPhotoAudio()), which uses its own
            // native HTTP stack (NuPlayer/libstagefright) — it never reads from
            // the CacheDataSource cache this class warms for ExoPlayer. Warming
            // bytes here would silently do nothing for MediaPlayer playback.
            // The real fix is ReelPlayerController.prewarmPhotoAudio(), which
            // pre-creates and prepareAsync()'s the actual MediaPlayer instance
            // ahead of visibility — see ReelPlayerFragment.prewarmPlayer().
        }
    }

    /**
     * Pick the same quality URL the player would choose for this cap.
     *
     * BUGFIX: must also apply CodecSupport.applyToUrl() — ReelPlayerController
     * appends a vc_<codec> transform to the URL it actually hands ExoPlayer.
     * If this preloader caches bytes under the plain (untransformed) URL,
     * CacheDataSource never finds a hit when playback requests the
     * codec-transformed URL, so every reel silently downloads twice: once
     * wasted here, once again for real playback. Always keep this in sync
     * with ReelPlayerController.pickQualityUrl().
     */
    private String pickQualityUrl(ReelModel reel, AdaptiveStreamingManager.QualityCap cap) {
        String url480  = reel.video480  != null && !reel.video480.isEmpty()  ? reel.video480  : null;
        String url720  = reel.video720  != null && !reel.video720.isEmpty()  ? reel.video720  : null;
        String url1080 = reel.video1080 != null && !reel.video1080.isEmpty() ? reel.video1080 : null;
        String fallback = reel.videoUrl != null ? reel.videoUrl : "";

        String chosen;
        switch (cap) {
            case Q480P:  chosen = url480  != null ? url480  : fallback; break;
            case Q720P:  chosen = url720  != null ? url720  : fallback; break;
            case Q1080P: chosen = url1080 != null ? url1080 : fallback; break;
            case Q360P:  chosen = url480  != null ? url480  : fallback; break;
            case AUTO:
            default:
                chosen = url1080 != null ? url1080 : (url720 != null ? url720 : fallback);
        }
        return com.callx.app.utils.CodecSupport.applyToUrl(chosen);
    }

    /** Bytes to preload based on network quality */
    private long networkBytes(NetworkQualityMonitor.Quality q) {
        switch (q) {
            case WIFI:
            case ETHERNET:
            case CELLULAR_5G: return PRELOAD_BYTES_WIFI;
            case CELLULAR_4G: return PRELOAD_BYTES_4G;
            case CELLULAR_3G: return PRELOAD_BYTES_3G;
            case CELLULAR_2G:
            case NONE:
            default:          return PRELOAD_BYTES_2G;
        }
    }

    /**
     * Ek reel ka video preload karta hai background mein.
     * Already preloading ya cached hai to skip karta hai.
     */
    private void preloadSingle(String videoUrl) {
        preloadSingle(videoUrl, PRELOAD_BYTES);
    }

    private void preloadSingle(String videoUrl, long bytesToPreload) {
        // Already preload ho raha hai ya ho chuka hai — skip
        if (mPreloading.contains(videoUrl)) {
            Log.d(TAG, "Already preloading/preloaded: " + shortUrl(videoUrl));
            return;
        }

        mPreloading.add(videoUrl);

        Future<?> task = mExecutor.submit(() -> {
            try {
                CacheDataSource.Factory factory = ReelCacheManager.getCacheDataSourceFactory();
                CacheDataSource cacheDataSource = factory.createDataSource();

                // Sirf pehle bytesToPreload bytes download karo
                DataSpec dataSpec = new DataSpec.Builder()
                    .setUri(Uri.parse(videoUrl))
                    .setPosition(0)
                    .setLength(bytesToPreload)
                    .build();

                CacheWriter cacheWriter = new CacheWriter(
                    cacheDataSource,
                    dataSpec,
                    null,  // isCanceled
                    (requestLength, bytesCached, newBytesCached) -> {
                        // Progress callback — optional logging
                        // Log.v(TAG, shortUrl(videoUrl) + " → " + bytesCached + " bytes cached");
                    }
                );

                cacheWriter.cache();
                Log.d(TAG, "Preloaded: " + shortUrl(videoUrl));

            } catch (Exception e) {
                Log.w(TAG, "Preload failed for " + shortUrl(videoUrl) + ": " + e.getMessage());
            } finally {
                // ✅ FIX: Remove from mPreloading in finally (not just on error).
                // Previously: mPreloading.remove() was called ONLY in the catch block,
                // so successful preloads kept the URL in mPreloading for the rest of the
                // session. Consequence: if the LRU evictor later flushed those bytes
                // (low storage), the preloader would never re-warm them (mPreloading still
                // contained the URL → early return at the top of preloadSingle). The reel
                // would then buffer from network instead of cache on next open.
                // Now: always remove, so a future preloadFrom() call re-evaluates the
                // actual cache state via CacheWriter (which skips already-cached ranges
                // automatically — no redundant network bytes spent).
                mPreloading.remove(videoUrl);
                mActiveTasks.remove(videoUrl);
            }
        });

        mActiveTasks.put(videoUrl, task);
    }

    /**
     * Feed switch ya fragment destroy par saare running preloads cancel karo.
     */
    public void cancelAll() {
        for (Future<?> task : mActiveTasks.values()) {
            task.cancel(true);
        }
        mActiveTasks.clear();
        mPreloading.clear();
        Log.d(TAG, "All preloads cancelled.");
    }

    /**
     * Preloader band karo (Fragment destroy hone par).
     * Iske baad is instance ko use mat karo.
     */
    public void shutdown() {
        cancelAll();
        mExecutor.shutdownNow();
    }

    private String shortUrl(String url) {
        if (url == null) return "null";
        return url.length() > 50 ? "..." + url.substring(url.length() - 50) : url;
    }
}
