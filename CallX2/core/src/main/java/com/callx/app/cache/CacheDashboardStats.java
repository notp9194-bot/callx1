package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App-wide cache telemetry used by CacheStatsActivity.
 *
 * Glide, the chat decoded-bitmap pool, avatar pools, and CacheManager do not
 * share the same storage implementation. This class deliberately records the
 * outcome of each real chat asset load instead of pretending that one cache
 * implementation owns all of them.
 */
public final class CacheDashboardStats {

    private static final String GLIDE_DISK_DIR = "image_manager_disk_cache";
    private static final long GLIDE_DISK_MAX_BYTES = 200L * 1024 * 1024;
    private static final String CHAT_AVATAR_L3_DIR = "avatar_l3/chat";
    private static final long CHAT_AVATAR_L3_MAX_BYTES = 1L * 1024 * 1024;
    private static final String PREFS_NAME = "cache_dashboard_stats";
    private static final String KEY_MEMORY_HITS = "memory_hits";
    private static final String KEY_MEMORY_MISSES = "memory_misses";
    private static final String KEY_DISK_HITS = "disk_hits";
    private static final String KEY_DISK_MISSES = "disk_misses";
    private static final long PERSIST_DELAY_MS = 1000L;

    private static volatile CacheDashboardStats sInstance;

    private final Context appContext;
    private final Handler persistHandler = new Handler(Looper.getMainLooper());
    private final Object persistLock = new Object();
    private boolean persistScheduled;
    private long mutationVersion;
    private final AtomicLong memoryHits = new AtomicLong();
    private final AtomicLong memoryMisses = new AtomicLong();
    private final AtomicLong diskHits = new AtomicLong();
    private final AtomicLong diskMisses = new AtomicLong();
    private long lastObservedAppMemoryHits;
    private long lastObservedAppMemoryMisses;
    // Bounded bookkeeping only; the real ownership remains with Glide/L2.
    // Keeping every URL ever seen here would turn a long chat session into a
    // slow memory leak in the dashboard itself.
    private long memoryBytes;
    private final LruCache<String, Long> memoryEntries = new LruCache<String, Long>(512) {
        @Override
        protected void entryRemoved(boolean evicted, String key, Long oldValue, Long newValue) {
            if (oldValue != null) memoryBytes -= oldValue;
        }
    };

    private CacheDashboardStats(Context context) {
        appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        memoryHits.set(prefs.getLong(KEY_MEMORY_HITS, 0L));
        memoryMisses.set(prefs.getLong(KEY_MEMORY_MISSES, 0L));
        diskHits.set(prefs.getLong(KEY_DISK_HITS, 0L));
        diskMisses.set(prefs.getLong(KEY_DISK_MISSES, 0L));
    }

    public static CacheDashboardStats getInstance(Context context) {
        CacheDashboardStats instance = sInstance;
        if (instance == null) {
            synchronized (CacheDashboardStats.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new CacheDashboardStats(context);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    public void recordMemoryHit(@Nullable String key) {
        memoryHits.incrementAndGet();
        schedulePersist();
    }

    public void recordMemoryMiss(@Nullable String key) {
        memoryMisses.incrementAndGet();
        schedulePersist();
    }

    public void recordDiskHit(@Nullable String key) {
        diskHits.incrementAndGet();
        schedulePersist();
    }

    public void recordDiskMiss(@Nullable String key) {
        diskMisses.incrementAndGet();
        schedulePersist();
    }

    /**
     * The original CacheManager.MemoryCache is also part of the dashboard,
     * but its LruCache counters only live for one process. Observe deltas so
     * those counters become part of the same persistent totals without
     * changing MemoryCache's existing implementation.
     */
    public synchronized void observeAppMemoryCounters(long currentHits, long currentMisses) {
        long hitDelta = currentHits >= lastObservedAppMemoryHits
                ? currentHits - lastObservedAppMemoryHits : currentHits;
        long missDelta = currentMisses >= lastObservedAppMemoryMisses
                ? currentMisses - lastObservedAppMemoryMisses : currentMisses;
        lastObservedAppMemoryHits = currentHits;
        lastObservedAppMemoryMisses = currentMisses;
        if (hitDelta > 0) memoryHits.addAndGet(hitDelta);
        if (missDelta > 0) memoryMisses.addAndGet(missDelta);
        if (hitDelta > 0 || missDelta > 0) schedulePersist();
    }

    /** Records a bitmap/drawable currently mirrored by a chat cache. */
    public synchronized void recordMemoryEntry(@Nullable String key, long bytes) {
        if (key == null || key.isEmpty()) return;
        long safeBytes = Math.max(1L, bytes);
        memoryEntries.put(key, safeBytes);
        memoryBytes += safeBytes;
    }

    public synchronized long getMemoryBytes() {
        return Math.max(0L, memoryBytes);
    }

    public Snapshot snapshot() {
        persistCounters();
        return new Snapshot(
                memoryHits.get(),
                memoryMisses.get(),
                diskHits.get(),
                diskMisses.get(),
                getMemoryBytes());
    }

    public synchronized void clearMemory() {
        memoryEntries.evictAll();
        memoryBytes = 0L;
    }

    public void clearDisk() {
        // Disk usage is read from the actual directories on demand.
    }

    private void schedulePersist() {
        synchronized (persistLock) {
            mutationVersion++;
            if (persistScheduled) return;
            persistScheduled = true;
            persistHandler.postDelayed(this::runScheduledPersist, PERSIST_DELAY_MS);
        }
    }

    private void runScheduledPersist() {
        final long versionAtStart;
        synchronized (persistLock) {
            versionAtStart = mutationVersion;
        }
        persistCounters();
        synchronized (persistLock) {
            if (mutationVersion != versionAtStart) {
                persistHandler.postDelayed(this::runScheduledPersist, PERSIST_DELAY_MS);
            } else {
                persistScheduled = false;
            }
        }
    }

    private void persistCounters() {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_MEMORY_HITS, memoryHits.get())
                .putLong(KEY_MEMORY_MISSES, memoryMisses.get())
                .putLong(KEY_DISK_HITS, diskHits.get())
                .putLong(KEY_DISK_MISSES, diskMisses.get())
                .apply();
    }

    /**
     * Listener for Glide requests that records the real serving tier. A
     * MEMORY_CACHE result is a memory hit; disk/local results are a disk hit
     * and a memory miss; remote results miss both caches.
     */
    public void recordGlideResult(@Nullable String key, @Nullable DataSource dataSource,
                                  @Nullable Object resource) {
        if (dataSource == DataSource.MEMORY_CACHE) {
            recordMemoryHit(key);
        } else {
            recordMemoryMiss(key);
            if (dataSource == DataSource.RESOURCE_DISK_CACHE
                    || dataSource == DataSource.DATA_DISK_CACHE
                    || dataSource == DataSource.LOCAL) {
                recordDiskHit(key);
            } else {
                recordDiskMiss(key);
            }
        }
        if (resource != null) recordMemoryEntry(key, estimateBytes(resource));
    }

    public <T> RequestListener<T> glideListener(@Nullable String key) {
        CacheDashboardStats stats = this;
        return new RequestListener<T>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                        Target<T> target, boolean isFirstResource) {
                stats.recordMemoryMiss(key);
                stats.recordDiskMiss(key);
                return false;
            }

            @Override
            public boolean onResourceReady(T resource, Object model, Target<T> target,
                                           DataSource dataSource, boolean isFirstResource) {
                stats.recordGlideResult(key, dataSource, resource);
                return false;
            }
        };
    }

    public static <T> RequestListener<T> glideListener(Context context, String key) {
        return getInstance(context).glideListener(key);
    }

    private static long estimateBytes(Object resource) {
        if (resource instanceof Bitmap) {
            return Math.max(1, ((Bitmap) resource).getByteCount());
        }
        if (resource instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) resource).getBitmap();
            return Math.max(1, bitmap.getByteCount());
        }
        if (resource instanceof Drawable) {
            Drawable drawable = (Drawable) resource;
            long width = Math.max(1, drawable.getIntrinsicWidth());
            long height = Math.max(1, drawable.getIntrinsicHeight());
            return width * height * 4L;
        }
        return 1L;
    }

    /**
     * Glide's InternalCacheDiskCacheFactory uses this stable app-cache
     * directory. Reading its files gives the dashboard the actual disk usage
     * for avatars, wallpaper, GIFs, stickers, and thumbnails.
     */
    public static long getGlideDiskCacheSizeBytes(Context context) {
        File dir = new File(context.getApplicationContext().getCacheDir(), GLIDE_DISK_DIR);
        return directorySize(dir);
    }

    public static long getGlideDiskMaxSizeBytes() {
        return GLIDE_DISK_MAX_BYTES;
    }

    /**
     * Exact live Glide resource-cache usage, not an estimate based on item count.
     * BUG FIX: Glide's public API does not expose MemoryCache — Glide#getMemoryCache()
     * does not exist (it never did; the field is private with no getter), which made
     * this fail to compile. There is no supported public accessor, so we read the
     * private field via reflection and degrade gracefully to a zeroed snapshot if
     * Glide's internals ever change shape, rather than crashing the cache dashboard.
     */
    public static GlideMemorySnapshot getGlideMemorySnapshot(Context context) {
        long usedBytes = 0L;
        long maxBytes = 0L;
        try {
            Glide glide = Glide.get(context.getApplicationContext());
            java.lang.reflect.Field field = Glide.class.getDeclaredField("memoryCache");
            field.setAccessible(true);
            com.bumptech.glide.load.engine.cache.MemoryCache cache =
                    (com.bumptech.glide.load.engine.cache.MemoryCache) field.get(glide);
            if (cache != null) {
                usedBytes = cache.getCurrentSize();
                maxBytes = cache.getMaxSize();
            }
        } catch (Exception e) {
            // Reflection failed (Glide internals changed) — fall back to zeroed snapshot
            // instead of crashing the cache dashboard.
        }
        return new GlideMemorySnapshot(usedBytes, maxBytes);
    }

    public static long getChatAvatarDiskCacheSizeBytes(Context context) {
        File dir = new File(context.getApplicationContext().getCacheDir(), CHAT_AVATAR_L3_DIR);
        return directorySize(dir);
    }

    public static long getChatAvatarDiskCacheMaxSizeBytes() {
        return CHAT_AVATAR_L3_MAX_BYTES;
    }

    private static long directorySize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        File[] files = dir.listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) {
            total += file.isDirectory() ? directorySize(file) : file.length();
        }
        return total;
    }

    public static final class Snapshot {
        public final long memoryHits;
        public final long memoryMisses;
        public final long diskHits;
        public final long diskMisses;
        public final long memoryBytes;

        private Snapshot(long memoryHits, long memoryMisses, long diskHits,
                         long diskMisses, long memoryBytes) {
            this.memoryHits = memoryHits;
            this.memoryMisses = memoryMisses;
            this.diskHits = diskHits;
            this.diskMisses = diskMisses;
            this.memoryBytes = memoryBytes;
        }
    }

    public static final class GlideMemorySnapshot {
        public final long usedBytes;
        public final long maxBytes;

        private GlideMemorySnapshot(long usedBytes, long maxBytes) {
            this.usedBytes = usedBytes;
            this.maxBytes = maxBytes;
        }
    }
}