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
import com.callx.app.viewer.StatusViewerActivity;
import com.callx.app.feed.StatusFragment;
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
    private static final long   VIDEO_PRELOAD_BYTES = 2L * 1024 * 1024;  // 2MB per video on mobile data
    private static final long   VIDEO_FULL_BYTES    = 24L * 1024 * 1024; // whole video (size-capped) on Wi-Fi / unmetered
    private static final int    MAX_FULL_PER_SESSION = 12;               // tray: first N videos go full, the rest only warm 2MB
    private static final long   EST_VIDEO_BYTES     = 8L * 1024 * 1024;  // planning size for quota checks
    // url → true if cached FULL, false if only the 2MB warm-up (lets a later Wi-Fi call upgrade a partial one)
    private final ConcurrentHashMap<String, Boolean> mMode = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger mFullQueued = new java.util.concurrent.atomic.AtomicInteger();
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
    /** Public entry for the viewer: warm the first ~2MB of ONE video (the next status) into the player cache. */
    public void preloadVideoUrl(String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) return;
        preloadVideo(videoUrl, 0L);
    }

    // ── Internal ──────────────────────────────────────────────────────────
    private void preloadSingle(StatusItem item) {
        if (item == null) return;
        if ("video".equals(item.type) && item.mediaUrl != null && !item.mediaUrl.isEmpty()) {
            preloadVideo(item.mediaUrl, item.effectiveExpiry());
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
    /** Unmetered (Wi-Fi) → cache the WHOLE video; metered → only the first 2MB. */
    public static boolean isUnmetered(Context c) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && !cm.isActiveNetworkMetered();
        } catch (Exception e) { return false; }
    }

    /** Lets a background worker abort a long download when WorkManager stops it. */
    public interface CancelCheck { boolean isCancelled(); }

    public static long cacheVideoBlocking(Context ctx, String url, boolean full, long expiryMs) throws Exception {
        return cacheVideoBlocking(ctx, url, full, expiryMs, null);
    }

    /**
     * Blocking video cache fill (call off the main thread). full=true → whole file up to VIDEO_FULL_BYTES, else first 2MB.
     * Skips when already cached; records expiry + size in StatusMediaManifest (eviction + quota).
     * @return bytes newly added to the cache by this call
     */
    public static long cacheVideoBlocking(Context ctx, String url, boolean full, long expiryMs, final CancelCheck cancel) throws Exception {
        final Context app = ctx.getApplicationContext();
        StatusVideoCacheManager.init(app);
        final androidx.media3.datasource.cache.SimpleCache cache = StatusVideoCacheManager.getSimpleCache();
        final long cap = full ? VIDEO_FULL_BYTES : VIDEO_PRELOAD_BYTES;
        final long len = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(url)); // -1 = unknown
        final long target = len > 0 ? Math.min(cap, len) : cap;
        long cached = 0;
        try { cached = cache.getCachedBytes(url, 0, target); } catch (Exception ignored) {}
        StatusMediaManifest.record(app, url, expiryMs);
        if (len > 0 ? cached >= target : (!full && cached >= target)) { // already there
            StatusMediaManifest.record(app, url, expiryMs, cached);
            return 0;
        }
        if (cancel != null && cancel.isCancelled()) return 0;
        final CacheDataSource ds = StatusVideoCacheManager.getCacheDataSourceFactory().createDataSource();
        final DataSpec spec = new DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setPosition(0)
                .setLength(full ? androidx.media3.common.C.LENGTH_UNSET : VIDEO_PRELOAD_BYTES)
                .build();
        final java.util.concurrent.atomic.AtomicReference<CacheWriter> ref = new java.util.concurrent.atomic.AtomicReference<>();
        final CacheWriter w = new CacheWriter(ds, spec, null, (requestLength, bytesCached, newBytesCached) -> {
            if ((full && bytesCached > VIDEO_FULL_BYTES) || (cancel != null && cancel.isCancelled())) { // size cap / worker stopped
                final CacheWriter c = ref.get();
                if (c != null) c.cancel();
            }
        });
        ref.set(w);
        try { w.cache(); } catch (java.io.InterruptedIOException stopped) { /* cap reached or cancelled — keep what we have */ }
        final long len2 = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(url));
        long after = 0;
        try { after = len2 > 0 ? Math.min(cap, len2) : cache.getCachedBytes(url, 0, cap); } catch (Exception ignored) {}
        StatusMediaManifest.record(app, url, expiryMs, after);
        return Math.max(0L, after - cached);
    }

    /** Blocking image fill into Glide's disk cache (same DATA key the viewer reads). Call off the main thread. */
    public static void cacheImageBlocking(Context ctx, String url) throws Exception {
        Glide.with(ctx.getApplicationContext()).downloadOnly().load(url)
                .submit().get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Video → whole file on Wi-Fi (if the user allows it, status quota has room and this session's full-video budget
     * isn't used up), else first 2MB. A partial one is upgraded to full on a later Wi-Fi call. Cache hit → skip.
     */
    private void preloadVideo(final String videoUrl, final long expiryMs) {
        if (mActiveTasks.containsKey(videoUrl)) return;
        boolean full = isUnmetered(mContext) && StatusCachePrefs.isAutoDownloadOnWifi(mContext);
        final Boolean prev = mMode.get(videoUrl);
        if (prev != null && (prev || !full)) return;               // already done at the same or better quality
        if (full) {
            if (mFullQueued.get() >= MAX_FULL_PER_SESSION || !StatusMediaManifest.hasRoom(mContext, EST_VIDEO_BYTES)) full = false;
            else mFullQueued.incrementAndGet();
        }
        if (prev != null && !full) return;                          // nothing to upgrade
        final boolean fullF = full;
        Future<?> task = mExecutor.submit(() -> {
            try {
                cacheVideoBlocking(mContext, videoUrl, fullF, expiryMs);
                mMode.put(videoUrl, fullF);
                StatusMediaManifest.trimToQuota(mContext);
                Log.d(TAG, "Video preloaded (" + (fullF ? "full" : "2MB") + "): " + shortUrl(videoUrl));
            } catch (Exception e) {
                Log.w(TAG, "Video preload failed " + shortUrl(videoUrl) + ": " + e.getMessage());
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
        mMode.clear();
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