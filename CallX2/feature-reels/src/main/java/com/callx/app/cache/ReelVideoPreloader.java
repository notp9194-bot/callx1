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
    /** Duet originals: 50MB — compositor needs the full video for rendering */
    private static final long   PRELOAD_BYTES_DUET = UnifiedVideoCacheManager.PARTIAL_BYTES_DUET;

    private final Context     mContext;
    private final ExecutorService mExecutor;

    // Kaunse URLs already preloading hain ya ho chuke hain
    private final Set<String>              mPreloading = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Future<?>> mActiveTasks = new ConcurrentHashMap<>();

    public ReelVideoPreloader(Context context) {
        mContext  = context.getApplicationContext();
        // 3 background threads — next 3 reels parallel preload karo
        mExecutor = Executors.newFixedThreadPool(3);
        ReelCacheManager.init(mContext);
    }

    /**
     * Main method — position par se PRELOAD_COUNT aage ke reels preload karta hai.
     *
     * @param reels    Current reel list (adapter ki list)
     * @param position Current visible position (jo reel ab dekhi ja rahi hai)
     */
    public void preloadFrom(List<ReelModel> reels, int position) {
        if (reels == null || reels.isEmpty()) return;

        for (int i = position + 1; i <= position + PRELOAD_COUNT && i < reels.size(); i++) {
            ReelModel reel = reels.get(i);
            if (reel == null) continue;

            // Main reel video
            if (reel.videoUrl != null && !reel.videoUrl.isEmpty()) {
                preloadSingle(reel.videoUrl, PRELOAD_BYTES);
            }

            // Duet original — compositor needs large chunk for rendering
            // Use 50MB preload so DuetVideoCompositor can use cached file directly
            if (reel.duetOriginalUrl != null && !reel.duetOriginalUrl.isEmpty()) {
                preloadSingle(reel.duetOriginalUrl, PRELOAD_BYTES_DUET);
                Log.d(TAG, "Duet original preloading (50MB): " + shortUrl(reel.duetOriginalUrl));
            }
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
