package com.callx.app.cache;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;

import com.bumptech.glide.Glide;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for the cache budgets that are owned by the app's
 * image/API cache stack.
 *
 * The old implementation reserved independent fixed amounts (200 MB for
 * Glide, 200 MB for DiskCache and small fixed avatar folders). That made the
 * same app look wasteful on a small phone and unnecessarily conservative on a
 * phone with plenty of free storage.
 *
 * This policy intentionally does not size the video caches. Video playback has
 * its own weighted policy and a separate eviction strategy. The budgets here
 * cover the image/API cache family shown by Storage & Cache.
 */
public final class DynamicCachePolicy {

    private static final String TAG = "DynamicCachePolicy";

    private static final long MB = 1024L * 1024L;
    private static final long MIN_TOTAL_BUDGET = 50L * MB;
    private static final long MAX_TOTAL_BUDGET = 1024L * MB;
    private static final long RESERVED_FREE_SPACE = 32L * MB;
    private static final int FREE_SPACE_PERCENT = 2;

    // These shares add up to 100%. They keep the central budget bounded while
    // preserving enough room for the two high-volume image stores.
    private static final int GLIDE_SHARE = 45;
    private static final int CORE_SHARE = 45;
    private static final int AVATAR_SHARE = 6;
    private static final int AVATAR_HTTP_SHARE = 3;
    private static final int NETWORK_HTTP_SHARE = 1;

    private static final Map<String, Integer> AVATAR_MODULE_SHARES = new HashMap<>();

    static {
        // Shares of the one central avatar L3 pool.
        AVATAR_MODULE_SHARES.put("chat", 18);
        AVATAR_MODULE_SHARES.put("reels", 22);
        AVATAR_MODULE_SHARES.put("x", 18);
        AVATAR_MODULE_SHARES.put("profile", 10);
        AVATAR_MODULE_SHARES.put("search", 10);
        AVATAR_MODULE_SHARES.put("misc", 8);
        AVATAR_MODULE_SHARES.put("calls", 5);
        AVATAR_MODULE_SHARES.put("status", 5);
        AVATAR_MODULE_SHARES.put("youtube", 4);
    }

    private DynamicCachePolicy() {}

    /**
     * Returns the total cache budget for image/API caches.
     *
     * It uses the free space on the app's cache filesystem, not the nominal
     * device capacity. A 32 MB safety reserve prevents the cache from
     * consuming the last usable storage. On very full devices the budget can
     * therefore be below the normal 50 MB floor, or become zero.
     */
    public static long getTotalDiskBudgetBytes(Context context) {
        long freeBytes = getAvailableBytes(context);
        if (freeBytes <= RESERVED_FREE_SPACE) return 0L;

        long percentageBudget = (freeBytes / 100L) * FREE_SPACE_PERCENT;
        long desired = Math.max(MIN_TOTAL_BUDGET, percentageBudget);
        desired = Math.min(MAX_TOTAL_BUDGET, desired);

        long safeAvailable = freeBytes - RESERVED_FREE_SPACE;
        return Math.max(0L, Math.min(desired, safeAvailable));
    }

    public static long getGlideDiskCacheBytes(Context context) {
        return configuredShare(getTotalDiskBudgetBytes(context), GLIDE_SHARE);
    }

    public static long getCoreDiskCacheBytes(Context context) {
        return configuredShare(getTotalDiskBudgetBytes(context), CORE_SHARE);
    }

    public static long getAvatarDiskCacheBytes(Context context) {
        return share(getTotalDiskBudgetBytes(context), AVATAR_SHARE);
    }

    public static long getAvatarModuleBudgetBytes(Context context, String module,
                                                  long fallbackBytes) {
        long avatarBudget = getAvatarDiskCacheBytes(context);
        Integer moduleShare = AVATAR_MODULE_SHARES.get(module);
        if (avatarBudget <= 0L || moduleShare == null) {
            return avatarBudget <= 0L ? 0L : fallbackBytes;
        }
        return share(avatarBudget, moduleShare);
    }

    public static long getAvatarHttpCacheBytes(Context context) {
        return configuredShare(getTotalDiskBudgetBytes(context), AVATAR_HTTP_SHARE);
    }

    public static long getNetworkHttpCacheBytes(Context context) {
        return configuredShare(getTotalDiskBudgetBytes(context), NETWORK_HTTP_SHARE);
    }

    /**
     * True when storage is low enough that the cache should immediately make
     * room, even if Android has not sent ACTION_DEVICE_STORAGE_LOW yet.
     */
    public static boolean isStorageLow(Context context) {
        long freeBytes = getAvailableBytes(context);
        if (freeBytes <= 0L) return false;
        long totalBytes = getTotalBytes(context);
        return freeBytes <= 128L * MB
                || (totalBytes > 0L && freeBytes * 100L <= totalBytes * 5L);
    }

    /**
     * Called from a worker/background thread on a low-storage broadcast or
     * periodic StatFs check. Glide's journal is cleared through its public API;
     * the custom and avatar folders are trimmed oldest-first.
     */
    public static void trimForLowStorage(Context context) {
        Context app = context.getApplicationContext();
        try {
            long total = getTotalDiskBudgetBytes(app);
            DiskCache.getInstance(app).trimToSizeBytes(total / 2L);
            trimDirectory(new File(app.getCacheDir(), "avatar_l3"),
                    getAvatarDiskCacheBytes(app) / 2L);

            // Glide owns a journaled DiskCache. Never delete its files by hand.
            Glide.get(app).clearDiskCache();
            AvatarHttpCache.evictAll();
            NetworkCacheHelper.evict(app);
            Log.w(TAG, "Low-storage trim completed; budget=" + total / MB + " MB");
        } catch (Throwable t) {
            Log.w(TAG, "Low-storage trim failed: " + t.getMessage());
        }
    }

    /**
     * Size of the cache family represented in Storage & Cache. Video caches
     * and the Room database are intentionally reported separately by the UI.
     */
    public static long getManagedDiskUsageBytes(Context context) {
        Context app = context.getApplicationContext();
        long total = DiskCache.getInstance(app).getCacheSizeBytes();
        total += directorySize(new File(app.getCacheDir(), "image_manager_disk_cache"));
        total += directorySize(new File(app.getCacheDir(), "avatar_l3"));
        total += directorySize(new File(app.getCacheDir(), "avatar_http_cache"));
        total += directorySize(new File(app.getCacheDir(), "http_cache"));
        return total;
    }

    private static long share(long total, int percent) {
        return (total * percent) / 100L;
    }

    private static long configuredShare(long total, int percent) {
        // Glide/OkHttp reject a zero-sized journal. Keep a minimal 1 MB
        // configured journal when the device is critically full; the
        // low-storage trim immediately clears it and normal devices still use
        // the exact proportional share above.
        return Math.max(1L * MB, share(total, percent));
    }

    private static long getAvailableBytes(Context context) {
        try {
            StatFs stat = new StatFs(context.getApplicationContext().getCacheDir().getPath());
            return stat.getAvailableBytes();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read available storage: " + t.getMessage());
            return 0L;
        }
    }

    private static long getTotalBytes(Context context) {
        try {
            StatFs stat = new StatFs(context.getApplicationContext().getCacheDir().getPath());
            return stat.getTotalBytes();
        } catch (Throwable ignored) {
            return 0L;
        }
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

    private static void trimDirectory(File dir, long maxBytes) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        long total = 0L;
        for (File file : files) {
            total += file.isDirectory() ? directorySize(file) : file.length();
        }
        if (total <= maxBytes) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (total <= maxBytes) break;
            long size = file.isDirectory() ? directorySize(file) : file.length();
            deleteRecursively(file);
            total -= size;
        }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }
}