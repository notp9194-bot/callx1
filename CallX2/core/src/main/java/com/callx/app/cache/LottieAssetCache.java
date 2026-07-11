package com.callx.app.cache;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Gap #1 fix: dedicated PERMANENT cache for lottie/emoji JSON — deliberately
 * separate from {@link DiskCache} (media, 200MB / 7-day TTL, LRU-evicted).
 *
 * Why separate:
 *   - Heavy photo/video traffic through DiskCache was able to evict emoji
 *     packs that had just been downloaded, forcing a re-download next time
 *     the empty-chat state (or a future sticker) was shown. Telegram never
 *     evicts sticker packs like that — once downloaded, they stay until the
 *     user clears app storage.
 *   - Lives under getFilesDir() (not getCacheDir()) — the OS is allowed to
 *     wipe cacheDir under storage pressure without asking, but filesDir is
 *     only cleared on app uninstall/explicit "clear storage". That matches
 *     "permanently local disk cache" from the plan.
 *   - No TTL eviction at all. A soft size budget (SOFT_CAP_BYTES) exists
 *     only as a safety valve against a runaway/malicious manifest; under
 *     normal use (a few dozen KB-sized emoji JSONs) it will never trigger.
 *   - Keys prefixed with DEFAULT_KEY_PREFIX are never evicted by the soft
 *     cap — the bundled default emoji must always be available offline.
 */
public class LottieAssetCache {

    private static final String TAG = "LottieAssetCache";
    private static final String CACHE_SUBDIR = "callx_lottie_permanent";
    private static final long SOFT_CAP_BYTES = 80L * 1024 * 1024; // 80MB safety valve, not a real budget

    /** Matches EmojiPackDownloadWorker.CACHE_KEY_PREFIX + "wave_default" exactly —
     *  the one key that must never be evicted by the soft-cap trim below. */
    private static final String PROTECTED_DEFAULT_KEY = "lottie_emoji:wave_default";

    private static LottieAssetCache sInstance;
    private final File mCacheDir;

    private LottieAssetCache(Context ctx) {
        mCacheDir = new File(ctx.getFilesDir(), CACHE_SUBDIR);
        if (!mCacheDir.exists()) mCacheDir.mkdirs();
    }

    public static synchronized LottieAssetCache getInstance(Context ctx) {
        if (sInstance == null) sInstance = new LottieAssetCache(ctx.getApplicationContext());
        return sInstance;
    }

    // ─────────────────────────────────────────────────────────────
    // READ — no TTL check, ever. If it's on disk, it's valid.
    // ─────────────────────────────────────────────────────────────

    public File get(String key) {
        if (key == null) return null;
        File f = new File(mCacheDir, hashKey(key));
        return (f.exists() && !f.getName().endsWith(".tmp")) ? f : null;
    }

    public boolean exists(String key) {
        return get(key) != null;
    }

    // ─────────────────────────────────────────────────────────────
    // WRITE — atomic temp-file + rename, same pattern as DiskCache
    // ─────────────────────────────────────────────────────────────

    /** Store already-decompressed, plain-text lottie JSON bytes. */
    public boolean savePlain(String key, byte[] plainJsonBytes) {
        if (key == null || plainJsonBytes == null) return false;
        String fileName = hashKey(key);
        File target = new File(mCacheDir, fileName);
        File tmp = new File(mCacheDir, fileName + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(plainJsonBytes);
                fos.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(plainJsonBytes);
                    fos.getFD().sync();
                }
                tmp.delete();
            }
            trimIfOverBudget();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "savePlain failed for key=" + key + ": " + e.getMessage());
            tmp.delete();
            return false;
        }
    }

    /**
     * Gap #2: TGS-style transport — server sends gzip-compressed JSON bytes
     * (70-80% smaller on the wire). Decompress here once, then persist the
     * plain JSON — AXrLottieDrawable.fromFile() needs a plain-JSON path, the
     * native side doesn't gunzip for us.
     *
     * @return the decompressed bytes on success (caller uses them for the
     *         sha256 integrity check against the manifest), or null on any
     *         failure (corrupt gzip, truncated download, etc).
     */
    public byte[] decompressGzip(byte[] gzipBytes) {
        if (gzipBytes == null) return null;
        try (GZIPInputStream gis = new GZIPInputStream(
                new java.io.ByteArrayInputStream(gzipBytes));
             ByteArrayOutputStream bos = new ByteArrayOutputStream(gzipBytes.length * 4)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            Log.w(TAG, "gzip decompress failed: " + e.getMessage());
            return null;
        }
    }

    public void delete(String key) {
        if (key == null) return;
        new File(mCacheDir, hashKey(key)).delete();
    }

    // ─────────────────────────────────────────────────────────────
    // SAFETY-VALVE TRIM — soft cap only, never touches default: keys
    // ─────────────────────────────────────────────────────────────

    private void trimIfOverBudget() {
        File[] files = mCacheDir.listFiles(f -> !f.getName().endsWith(".tmp"));
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= SOFT_CAP_BYTES) return;

        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= SOFT_CAP_BYTES) break;
            // Default emoji filename is a fixed hash we can recognize directly.
            if (f.getName().equals(hashKey(PROTECTED_DEFAULT_KEY))) continue;
            total -= f.length();
            f.delete();
        }
        Log.w(TAG, "soft cap exceeded, trimmed old entries (non-default only)");
    }

    public long getCacheSizeBytes() {
        File[] files = mCacheDir.listFiles(f -> !f.getName().endsWith(".tmp"));
        if (files == null) return 0;
        long total = 0;
        for (File f : files) total += f.length();
        return total;
    }

    public File getDir() { return mCacheDir; }

    private static String hashKey(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return key.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        }
    }
}
