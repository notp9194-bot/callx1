package com.callx.app.cache;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;

import com.callx.app.models.XTweet;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Set;

/**
 * XTweetMediaPreloader — Twitter/X-style tweet video pre-fetching.
 *
 * Reels ke ReelVideoPreloader ki tarah kaam karta hai, par X feed ke liye:
 *   1. User tweet N dekh raha hai (RecyclerView scroll)
 *   2. Hum background mein tweet N+1, N+2, N+3 ke video ke pehle 2MB download kar lete hain
 *   3. Jab user scroll karta hai → tweet video INSTANTLY start hoti hai
 *   4. XVideoPlayerActivity mein bhi is cached data ka use hoga
 *
 * Features:
 *  ✅ Sirf video wale tweets preload hote hain (mediaType == "video")
 *  ✅ Sirf agle PRELOAD_COUNT tweets preload (bandwidth waste nahi)
 *  ✅ Already cached/preloading URLs skip hote hain (duplicate downloads nahi)
 *  ✅ Background thread pool (2 threads) — main thread block nahi hota
 *  ✅ Cancel support — jab tab switch ya fragment destroy ho
 *
 * Usage (XHomeFragment mein):
 *   // Field:
 *   private XTweetMediaPreloader mediaPreloader;
 *
 *   // onViewCreated ke baad:
 *   mediaPreloader = new XTweetMediaPreloader(requireContext());
 *
 *   // RecyclerView scroll listener ya onBindViewHolder mein:
 *   mediaPreloader.preloadFrom(currentList, firstVisiblePosition);
 *
 *   // onDestroyView mein:
 *   mediaPreloader.shutdown();
 */
@OptIn(markerClass = UnstableApi.class)
public class XTweetMediaPreloader {

    private static final String TAG           = "XTweetMediaPreloader";
    private static final int    PRELOAD_COUNT = 3;               // Agle 3 tweets preload
    private static final long   PRELOAD_BYTES = 2 * 1024 * 1024L; // Pehle 2MB preload

    private final Context       mContext;
    private final ExecutorService mExecutor;

    // Kaunse URLs already preloading hain ya ho chuke hain
    private final Set<String>                          mPreloading  = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Future<?>> mActiveTasks = new ConcurrentHashMap<>();

    public XTweetMediaPreloader(Context context) {
        mContext  = context.getApplicationContext();
        mExecutor = Executors.newFixedThreadPool(2);
        XTweetCacheManager.init(mContext);
    }

    /**
     * Main method — position par se PRELOAD_COUNT aage ke video tweets preload karta hai.
     *
     * @param tweets   Current tweet list (adapter ki list)
     * @param position Current visible position (jo tweet abhi dikhayi ja raha hai)
     */
    public void preloadFrom(List<XTweet> tweets, int position) {
        if (tweets == null || tweets.isEmpty()) return;

        for (int i = position + 1; i <= position + PRELOAD_COUNT && i < tweets.size(); i++) {
            XTweet tweet = tweets.get(i);
            if (tweet == null) continue;
            // Sirf video tweets preload karo (image tweets ka preload zaroori nahi)
            if (!"video".equals(tweet.mediaType)) continue;
            if (tweet.mediaUrl == null || tweet.mediaUrl.isEmpty()) continue;
            preloadSingle(tweet.mediaUrl);
        }
    }

    /**
     * Ek tweet ka video preload karta hai background mein.
     * Already preloading ya cached hai to skip karta hai.
     */
    private void preloadSingle(String mediaUrl) {
        if (mPreloading.contains(mediaUrl)) {
            Log.d(TAG, "Already preloading/preloaded: " + shortUrl(mediaUrl));
            return;
        }

        mPreloading.add(mediaUrl);

        Future<?> task = mExecutor.submit(() -> {
            try {
                CacheDataSource.Factory factory = XTweetCacheManager.getCacheDataSourceFactory();
                CacheDataSource cacheDataSource = factory.createDataSource();

                // Sirf pehle PRELOAD_BYTES bytes download karo (2MB)
                DataSpec dataSpec = new DataSpec.Builder()
                    .setUri(Uri.parse(mediaUrl))
                    .setPosition(0)
                    .setLength(PRELOAD_BYTES)
                    .build();

                CacheWriter cacheWriter = new CacheWriter(
                    cacheDataSource,
                    dataSpec,
                    null,
                    (requestLength, bytesCached, newBytesCached) -> {
                        // Progress callback — optional
                    }
                );

                cacheWriter.cache();
                Log.d(TAG, "Preloaded tweet video: " + shortUrl(mediaUrl));

            } catch (Exception e) {
                Log.w(TAG, "Preload failed for " + shortUrl(mediaUrl) + ": " + e.getMessage());
                mPreloading.remove(mediaUrl); // Failed → retry allow karo
            } finally {
                mActiveTasks.remove(mediaUrl);
            }
        });

        mActiveTasks.put(mediaUrl, task);
    }

    /**
     * Tab switch ya feed reload par saare running preloads cancel karo.
     */
    public void cancelAll() {
        for (Future<?> task : mActiveTasks.values()) {
            task.cancel(true);
        }
        mActiveTasks.clear();
        mPreloading.clear();
        Log.d(TAG, "All X tweet preloads cancelled.");
    }

    /**
     * Fragment destroy hone par preloader band karo.
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
