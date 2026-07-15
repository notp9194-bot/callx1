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
    private static final int    PRELOAD_COUNT = 4;            // Agle 4 reels preload karo
    private static final long   PRELOAD_BYTES = 10 * 1024 * 1024L; // Pehle 10MB preload (guaranteed smooth playback)
    private static final long   PRELOAD_BYTES_WIFI = 10 * 1024 * 1024L; // WiFi/5G: 10MB
    private static final long   PRELOAD_BYTES_4G   =  5 * 1024 * 1024L; // 4G: 5MB
    private static final long   PRELOAD_BYTES_3G   =  2 * 1024 * 1024L; // 3G: 2MB
    private static final long   PRELOAD_BYTES_2G   =    512 * 1024L;    // 2G/slow: 512KB
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
        // 3 background threads — next 3 reels parallel preload karo
        mExecutor = Executors.newFixedThreadPool(3);
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
        if (reels == null || reels.isEmpty()) return;

        // Network-aware bytes: WiFi aggressive, 2G minimal
        NetworkQualityMonitor monitor = NetworkQualityMonitor.get(mContext);
        NetworkQualityMonitor.Quality netQuality = monitor.currentQuality();
        long bytesToPreload = networkBytes(netQuality);

        // Quality-aware URL: preload the URL the player will actually use
        AdaptiveStreamingManager.QualityCap cap = mCurrentCap;

        // Reduce preload count on slow networks to save data
        int preloadCount = netQuality == NetworkQualityMonitor.Quality.CELLULAR_2G ? 1
            : netQuality == NetworkQualityMonitor.Quality.CELLULAR_3G ? 2
            : PRELOAD_COUNT;

        for (int i = position + 1; i <= position + preloadCount && i < reels.size(); i++) {
            ReelModel reel = reels.get(i);
            if (reel == null) continue;

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
                mPreloading.remove(videoUrl); // Failed → retry allow karo
            } finally {
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
