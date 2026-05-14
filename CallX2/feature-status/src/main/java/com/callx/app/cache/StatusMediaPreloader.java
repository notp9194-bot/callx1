package com.callx.app.cache;

import android.content.Context;
import android.util.Log;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.DataSpec;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.models.StatusItem;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * StatusMediaPreloader — WhatsApp/Instagram style status media pre-fetching.
 *
 * Reels ke ReelVideoPreloader ka exact mirror — Status ke liye:
 *
 *   VIDEO statuses:
 *     → ExoPlayer SimpleCache mein pehle 2MB preload (background thread)
 *     → StatusViewerActivity mein play karte waqt: instant start, no buffering
 *
 *   IMAGE statuses:
 *     → Glide disk cache mein preload (async)
 *     → StatusViewerActivity mein Glide se load: instant from cache
 *
 *   TEXT statuses:
 *     → Koi download nahi (text already hai)
 *
 * Usage (StatusFragment mein):
 *   // Field:
 *   private StatusMediaPreloader preloader;
 *
 *   // onStart() / onCreateView():
 *   preloader = new StatusMediaPreloader(requireContext());
 *
 *   // Jab contact status list click hone wali ho (ya onStart par):
 *   preloader.preloadContactStatuses(statusItems);
 *
 *   // onStop() / onDestroyView():
 *   preloader.shutdown();
 */
@OptIn(markerClass = UnstableApi.class)
public class StatusMediaPreloader {

    private static final String TAG           = "StatusPreloader";
    private static final long   VIDEO_PRELOAD_BYTES = 2L * 1024 * 1024; // 2MB per video

    private final Context       mContext;
    private final ExecutorService mExecutor;
    private final RequestOptions  mImageOptions;

    // Track in-progress / completed URLs to avoid duplicates
    private final java.util.Set<String>              mPreloading  = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Future<?>> mActiveTasks = new ConcurrentHashMap<>();

    public StatusMediaPreloader(Context context) {
        mContext   = context.getApplicationContext();
        mExecutor  = Executors.newFixedThreadPool(2); // 2 background threads — enough
        mImageOptions = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop();
        StatusVideoCacheManager.init(mContext);
    }

    /**
     * Ek contact ke saare StatusItems preload karo.
     * Call karo jab user status list mein kisi contact par hover ya click karne wala ho.
     * Ya StatusFragment.onStart() mein sab contacts ke liye call karo.
     *
     * @param items Is contact ke saare active StatusItems
     */
    public void preloadContactStatuses(List<StatusItem> items) {
        if (items == null || items.isEmpty()) return;
        for (StatusItem item : items) {
            preloadSingle(item);
        }
    }

    /**
     * Multiple contacts ke statuses preload karo (StatusFragment.onStart use case).
     *
     * @param allStatuses ownerUid → List<StatusItem> map se values
     */
    public void preloadAll(java.util.Collection<List<StatusItem>> allStatuses) {
        if (allStatuses == null) return;
        for (List<StatusItem> items : allStatuses) {
            preloadContactStatuses(items);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private void preloadSingle(StatusItem item) {
        if (item == null) return;

        if ("video".equals(item.type) && item.mediaUrl != null && !item.mediaUrl.isEmpty()) {
            preloadVideo(item.mediaUrl);
        } else if ("image".equals(item.type) && item.mediaUrl != null && !item.mediaUrl.isEmpty()) {
            preloadImage(item.mediaUrl);
            // Thumbnail bhi preload karo agar alag hai
            if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()
                    && !item.thumbnailUrl.equals(item.mediaUrl)) {
                preloadImage(item.thumbnailUrl);
            }
        } else if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
            // Any type with thumbnail (reel_story, link preview etc.)
            preloadImage(item.thumbnailUrl);
        }
        // text type: nothing to preload
    }

    /**
     * Video ka pehla VIDEO_PRELOAD_BYTES ExoPlayer cache mein download karo.
     * Cache hit hone par skip karta hai.
     */
    private void preloadVideo(String videoUrl) {
        if (mPreloading.contains(videoUrl)) {
            Log.v(TAG, "Already preloading video: " + shortUrl(videoUrl));
            return;
        }

        // Agar already kaafi data cache mein hai to skip
        long cached = StatusVideoCacheManager.getCachedBytes(videoUrl);
        if (cached >= VIDEO_PRELOAD_BYTES) {
            Log.v(TAG, "Video already cached (" + cached + " bytes): " + shortUrl(videoUrl));
            return;
        }

        mPreloading.add(videoUrl);

        Future<?> task = mExecutor.submit(() -> {
            try {
                CacheDataSource.Factory factory = StatusVideoCacheManager.getCacheDataSourceFactory();
                CacheDataSource cacheDataSource = factory.createDataSource();

                DataSpec dataSpec = new DataSpec.Builder()
                    .setUri(Uri.parse(videoUrl))
                    .setPosition(0)
                    .setLength(VIDEO_PRELOAD_BYTES)
                    .build();

                CacheWriter cacheWriter = new CacheWriter(
                    cacheDataSource,
                    dataSpec,
                    null,
                    (requestLength, bytesCached, newBytesCached) -> {
                        // Progress — optional
                    }
                );

                cacheWriter.cache();
                Log.d(TAG, "Video preloaded: " + shortUrl(videoUrl));

            } catch (Exception e) {
                Log.w(TAG, "Video preload failed " + shortUrl(videoUrl) + ": " + e.getMessage());
                mPreloading.remove(videoUrl); // failed → retry allowed
            } finally {
                mActiveTasks.remove(videoUrl);
            }
        });

        mActiveTasks.put(videoUrl, task);
    }

    /**
     * Image Glide disk cache mein preload karo.
     */
    private void preloadImage(String imageUrl) {
        if (mPreloading.contains(imageUrl)) return;
        mPreloading.add(imageUrl);

        // Glide.preload() is async internally — no need for our executor
        Glide.with(mContext)
            .load(imageUrl)
            .apply(mImageOptions)
            .preload();

        Log.v(TAG, "Image preload queued: " + shortUrl(imageUrl));
    }

    /** Saare running preloads cancel karo */
    public void cancelAll() {
        for (Future<?> task : mActiveTasks.values()) {
            task.cancel(true);
        }
        mActiveTasks.clear();
        mPreloading.clear();
        Log.d(TAG, "All status preloads cancelled.");
    }

    /** Fragment destroy hone par call karo */
    public void shutdown() {
        cancelAll();
        mExecutor.shutdownNow();
    }

    private String shortUrl(String url) {
        if (url == null) return "null";
        return url.length() > 50 ? "..." + url.substring(url.length() - 50) : url;
    }
}
