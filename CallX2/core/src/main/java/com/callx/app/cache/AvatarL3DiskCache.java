package com.callx.app.cache;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AvatarL3DiskCache — tiny disk-backed tier UNDER AvatarL2MemoryCache, for
 * the one gap L2 can't cover: process death. L2 is in-process memory only
 * (WeakReference<Bitmap>), so a swiped-away/killed process loses it
 * completely — the very first paint after a genuine cold start still hit
 * network+decode even for an avatar the user saw ten seconds earlier.
 * This tier persists the already-resized, already-decoded bitmap bytes to
 * a small per-module disk folder so a cold start can paint straight from
 * disk instead — no network round-trip at all.
 *
 * Keyed the same way as L2 — an MD5 of the resolved URL (tier + ?v=
 * already baked in by AvatarUrlBuilder), so a real avatar version bump is
 * automatically a different filename, never a stale hit. WEBP_LOSSY on
 * API 30+ (falls back to JPEG below that) at quality 80 — these are tiny
 * SMALL/TINY-tier avatar tiles, not full photos, so the whole cache
 * folder stays a few hundred KB even full. Plain last-modified-time LRU,
 * swept on write; deliberately not a real DB — this is a best-effort
 * accelerator, not a source of truth (Glide's own disk cache still is).
 *
 * ALL I/O is dispatched off the calling thread via a dedicated
 * single-thread executor — reads/writes here would otherwise trip
 * StrictMode's detectAll() disk policy if called from onResourceReady
 * (main thread) or a bind() fast path. Callbacks land back on the main
 * thread via a Handler(Looper.getMainLooper()).
 */
public final class AvatarL3DiskCache {

    private static final String TAG = "AvatarL3DiskCache";

    private final File dir;
    private final long maxBytes;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public AvatarL3DiskCache(Context ctx, String subdir, long maxBytes) {
        this.dir = new File(ctx.getApplicationContext().getCacheDir(), "avatar_l3/" + subdir);
        this.maxBytes = maxBytes;
        io.execute(() -> dir.mkdirs());
    }

    public interface Callback {
        void onResult(Bitmap bitmap); // null = miss; always called on main thread
    }

    /** Async disk lookup — never call BitmapFactory.decodeFile on the calling thread directly. */
    public void getAsync(String url, Callback callback) {
        if (url == null || url.isEmpty()) {
            callback.onResult(null);
            return;
        }
        io.execute(() -> {
            Bitmap result = null;
            File f = fileFor(url);
            if (f.exists()) {
                f.setLastModified(System.currentTimeMillis()); // LRU touch
                try {
                    result = BitmapFactory.decodeFile(f.getAbsolutePath());
                } catch (Throwable t) {
                    Log.w(TAG, "decode failed: " + t.getMessage());
                }
            }
            Bitmap finalResult = result;
            main.post(() -> callback.onResult(finalResult));
        });
    }

    /** Async disk write — safe to call from a Glide onResourceReady() (main thread) callback. */
    public void put(String url, Bitmap bitmap) {
        if (url == null || url.isEmpty() || bitmap == null || bitmap.isRecycled()) return;
        io.execute(() -> {
            File f = fileFor(url);
            try (FileOutputStream out = new FileOutputStream(f)) {
                Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.JPEG;
                bitmap.compress(fmt, 80, out);
            } catch (Throwable t) {
                Log.w(TAG, "write failed: " + t.getMessage());
                return;
            }
            trimIfNeeded();
        });
    }

    // ── internal, always runs on the io executor thread ──────────────────

    private File fileFor(String url) {
        return new File(dir, hash(url) + ".img");
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private void trimIfNeeded() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= maxBytes) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified)); // oldest-touched first
        for (File f : files) {
            if (total <= maxBytes) break;
            total -= f.length();
            f.delete();
        }
    }
}
