package com.callx.app.cache;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tier-2: Disk cache for media files (images, videos, voice notes).
 * Max size: 200 MB. Files older than 7 days are auto-cleaned.
 *
 * FIX #4 (MEDIUM): mCachedTotalBytes changed from plain long → AtomicLong.
 *
 *   Old: private long mCachedTotalBytes = -1;
 *   → Multiple executor threads call save(), delete(), cleanExpired()
 *     concurrently. Each reads and updates mCachedTotalBytes.
 *   → On 32-bit ARM devices, 64-bit long reads/writes are NOT atomic —
 *     the JVM may see a "torn" value (high 32 bits from one write,
 *     low 32 bits from another).
 *   → Torn read → incorrect size tracking:
 *     a) Size appears too small → enforceMaxSize never runs → disk fills past 200 MB
 *     b) Size appears negative/huge → enforceMaxSize deletes everything → cache wipe
 *
 *   Fix: AtomicLong — compareAndSet / addAndGet operations are atomic on
 *     all architectures including 32-bit ARM. No locks needed.
 *
 * Previously fixed (v10):
 *   - SHA-256 hash filename (no URL collision)
 *   - Atomic write via temp-file + rename (no partial-write corruption)
 *   - Lazy enforceMaxSize (no O(n log n) sort on every save)
 */
public class DiskCache {

    private static final String TAG          = "DiskCache";
    private static final long   MAX_SIZE     = 200L * 1024 * 1024; // 200 MB
    private static final long   MAX_AGE_MS   = 7L * 24 * 60 * 60 * 1000;
    private static final String CACHE_SUBDIR = "callx_media";

    private static DiskCache sInstance;
    private final File mCacheDir;

    // FIX #4: AtomicLong — thread-safe on all architectures including 32-bit ARM
    private final AtomicLong mCachedTotalBytes = new AtomicLong(-1L);

    private DiskCache(Context ctx) {
        mCacheDir = new File(ctx.getCacheDir(), CACHE_SUBDIR);
        if (!mCacheDir.exists()) mCacheDir.mkdirs();
    }

    public static synchronized DiskCache getInstance(Context ctx) {
        if (sInstance == null) sInstance = new DiskCache(ctx.getApplicationContext());
        return sInstance;
    }

    // ─────────────────────────────────────────────────────────────
    // WRITE — atomic temp-file + rename (from v10)
    // ─────────────────────────────────────────────────────────────

    public boolean save(String key, byte[] data) {
        if (key == null || data == null) return false;
        String fileName = hashKey(key);
        File   target   = new File(mCacheDir, fileName);
        File   tmp      = new File(mCacheDir, fileName + ".tmp");

        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
                fos.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(data);
                    fos.getFD().sync();
                }
                tmp.delete();
            }
            // FIX #4: atomic add — safe from concurrent threads
            long current = mCachedTotalBytes.get();
            if (current >= 0) mCachedTotalBytes.addAndGet(data.length);
            enforceMaxSizeLazy();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "save failed for key=" + key + ": " + e.getMessage());
            tmp.delete();
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────────

    public File get(String key) {
        if (key == null) return null;
        File f = new File(mCacheDir, hashKey(key));
        if (!f.exists() || f.getName().endsWith(".tmp")) return null;
        if (System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) {
            // FIX #4: atomic subtract
            long len = f.length();
            if (f.delete()) {
                long cur = mCachedTotalBytes.get();
                if (cur >= 0) mCachedTotalBytes.addAndGet(-len);
            }
            return null;
        }
        f.setLastModified(System.currentTimeMillis());
        return f;
    }

    public boolean exists(String key) {
        return get(key) != null;
    }

    public void delete(String key) {
        if (key == null) return;
        File f = new File(mCacheDir, hashKey(key));
        if (f.exists()) {
            long len = f.length();
            if (f.delete()) {
                long cur = mCachedTotalBytes.get();
                // FIX #4: atomic subtract
                if (cur >= 0) mCachedTotalBytes.addAndGet(-len);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CLEANUP
    // ─────────────────────────────────────────────────────────────

    public void cleanExpired() {
        File[] files = mCacheDir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        long freed  = 0;
        for (File f : files) {
            if (f.getName().endsWith(".tmp") || f.lastModified() < cutoff) {
                freed += f.length();
                f.delete();
            }
        }
        if (freed > 0) {
            long cur = mCachedTotalBytes.get();
            // FIX #4: atomic subtract
            if (cur >= 0) mCachedTotalBytes.addAndGet(-freed);
        }
        Log.d(TAG, "cleanExpired: freed " + freed / 1024 + " KB");
    }

    // ─────────────────────────────────────────────────────────────
    // SIZE MANAGEMENT (lazy from v11)
    // ─────────────────────────────────────────────────────────────

    private void enforceMaxSizeLazy() {
        // FIX #4: atomic get — no torn read on 32-bit
        if (mCachedTotalBytes.get() < MAX_SIZE) return;
        enforceMaxSize();
    }

    private void enforceMaxSize() {
        File[] files = mCacheDir.listFiles(f -> !f.getName().endsWith(".tmp"));
        if (files == null) return;

        long total = 0;
        for (File f : files) total += f.length();
        mCachedTotalBytes.set(total); // FIX #4: atomic set

        if (total <= MAX_SIZE) return;

        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_SIZE) break;
            total -= f.length();
            f.delete();
        }
        mCachedTotalBytes.set(total); // FIX #4: atomic set after eviction
    }

    // ─────────────────────────────────────────────────────────────
    // STATS
    // ─────────────────────────────────────────────────────────────

    public long getCacheSizeBytes() {
        long cur = mCachedTotalBytes.get(); // FIX #4: atomic read
        if (cur >= 0) return cur;
        // First call — scan and cache
        File[] files = mCacheDir.listFiles(f -> !f.getName().endsWith(".tmp"));
        if (files == null) { mCachedTotalBytes.set(0); return 0; }
        long total = 0;
        for (File f : files) total += f.length();
        mCachedTotalBytes.set(total);
        return total;
    }

    public File getDiskCacheDir()  { return mCacheDir; }
    public long getMaxSizeBytes()  { return MAX_SIZE; }

    // ─────────────────────────────────────────────────────────────
    // SHA-256 FILENAME HASH (from v10 — no URL collision)
    // ─────────────────────────────────────────────────────────────

    private static String hashKey(String key) {
        try {
            MessageDigest md   = MessageDigest.getInstance("SHA-256");
            byte[]        hash = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb   = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return key.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        }
    }
}
