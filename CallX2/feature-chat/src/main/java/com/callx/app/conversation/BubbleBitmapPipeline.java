package com.callx.app.conversation;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * App-owned background bitmap decode pipeline for the chat media bubble.
 *
 * WHY THIS EXISTS
 * ---------------
 * Every Glide result used to be stored in the *_BITMAP_CACHE pools through
 * MessagePagingAdapter#putBitmapSafely(), which had to Bitmap.copy() it ON THE
 * MAIN THREAD, because a Glide bitmap belongs to Glide's BitmapPool and can be
 * recycled under us ("Cannot create BitmapShader for recycled bitmap"). That
 * copy doubled memory and cost a main-thread allocation per decode.
 *
 * A bitmap decoded here is created by BitmapFactory directly, Glide has never
 * seen it and will never recycle it, so it can go straight into the pools with
 * no copy (see {@link #isOwned}) and no recycle-crash risk. The decode itself
 * runs on a background executor, not on the main thread.
 *
 * WHAT IT DOES
 * ------------
 *  1. Downsampled decode (inSampleSize + exact residual scale, same
 *     CENTER_OUTSIDE sizing Glide's DownsampleStrategy.DEFAULT gave us), EXIF
 *     orientation applied for JPEG, never upscales.
 *  2. Bitmap.Config.HARDWARE (API 28+) for display-only photos: pixels live in
 *     GPU memory (no Java heap cost) and the texture upload happens here, on the
 *     decode thread, instead of on the first RenderThread draw. Falls back to
 *     software automatically on any failure. Opaque JPEG falls back to RGB_565.
 *  3. LIFO executor: the most recently bound (= on screen) bubble decodes
 *     first, and a decode whose holder was already recycled is skipped
 *     before any pixel work (stillWanted).
 *  4. A small inBitmap reuse pool for bitmaps that are provably unreferenced
 *     (EXIF intermediates, duplicate decodes that lost a race). Bitmaps that
 *     live in the LruCaches are NEVER recycled or reused on eviction, because a
 *     view can still be drawing them.
 */
public final class BubbleBitmapPipeline {

    private BubbleBitmapPipeline() {}

    /** Kill switch for Bitmap.Config.HARDWARE. */
    private static final boolean ENABLE_HARDWARE = true;
    /** HARDWARE bitmaps below this size are not worth the GPU allocation. */
    private static final int HW_MIN_DIM_PX = 128;

    public interface Callback {
        /** Main thread. The bitmap is app-owned: cache it as-is, no copy needed. */
        void onDecoded(@NonNull Bitmap owned);
        /** Main thread. Decode failed; caller should fall back to its legacy path. */
        void onFailed();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void postMain(@NonNull Runnable r) { MAIN.post(r); }

    // ── Ownership registry ───────────────────────────────────────────────
    // Identity-keyed, weak: marks bitmaps this pipeline created.
    private static final Map<Bitmap, Boolean> OWNED =
            Collections.synchronizedMap(new WeakHashMap<Bitmap, Boolean>());

    public static boolean isOwned(@Nullable Bitmap b) {
        return b != null && OWNED.containsKey(b);
    }

    private static void markOwned(@NonNull Bitmap b) { OWNED.put(b, Boolean.TRUE); }

    // ── Executors ────────────────────────────────────────────────────────
    private static final class NamedFactory implements ThreadFactory {
        private final String name;
        private final AtomicInteger n = new AtomicInteger();
        NamedFactory(String name) { this.name = name; }
        @Override public Thread newThread(final Runnable r) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND
                                + Process.THREAD_PRIORITY_MORE_FAVORABLE);
                    } catch (Throwable ignored) {}
                    r.run();
                }
            }, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /** Newest task first: the bubble bound last is the one on screen. */
    private static final class LifoQueue extends LinkedBlockingDeque<Runnable> {
        @Override public boolean offer(Runnable r) { return offerFirst(r); }
        @Override public boolean add(Runnable r) { addFirst(r); return true; }
    }

    private static final ThreadPoolExecutor VISIBLE_EXEC;
    static {
        VISIBLE_EXEC = new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS,
                new LifoQueue(), new NamedFactory("bubble-decode"));
        VISIBLE_EXEC.allowCoreThreadTimeOut(true);
    }

    private static final class PreloadTask implements Runnable {
        final String key;
        final Runnable body;
        PreloadTask(String key, Runnable body) { this.key = key; this.body = body; }
        @Override public void run() {
            try { body.run(); }
            catch (Throwable ignored) {}
            finally { PRELOAD_INFLIGHT.remove(key); }
        }
    }

    private static final ConcurrentHashMap<String, Boolean> PRELOAD_INFLIGHT = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor PRELOAD_EXEC;
    static {
        PRELOAD_EXEC = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), new NamedFactory("bubble-preload"),
                new java.util.concurrent.RejectedExecutionHandler() {
                    @Override public void rejectedExecution(Runnable r, ThreadPoolExecutor ex) {
                        // Queue full: drop the NEW task (it is the least urgent) and free its key.
                        if (r instanceof PreloadTask) PRELOAD_INFLIGHT.remove(((PreloadTask) r).key);
                    }
                });
        PRELOAD_EXEC.allowCoreThreadTimeOut(true);
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Returns the underlying local file for a File / file-scheme Uri / bare path Uri, else null. */
    @Nullable
    public static File asLocalFile(@Nullable Object src) {
        if (src instanceof File) return (File) src;
        if (src instanceof Uri) {
            Uri u = (Uri) src;
            String scheme = u.getScheme();
            if (scheme == null || "file".equals(scheme)) {
                String p = u.getPath();
                if (p != null && !p.isEmpty()) return new File(p);
            }
        }
        return null;
    }

    /**
     * Decodes {@code file} off the main thread and delivers an app-owned bitmap on the
     * main thread. If {@code stillWanted} returns false when the task reaches the head of
     * the queue, nothing is decoded and no callback fires.
     */
    public static void decodeFile(@NonNull final File file, final int targetW, final int targetH,
                                  @Nullable final BooleanSupplier stillWanted,
                                  @NonNull final Callback cb) {
        VISIBLE_EXEC.execute(new Runnable() {
            @Override public void run() {
                if (stillWanted != null && !stillWanted.getAsBoolean()) return;
                Bitmap b = null;
                try { b = decodeBlocking(file, targetW, targetH); } catch (Throwable ignored) {}
                final Bitmap result = b;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (result != null) cb.onDecoded(result); else cb.onFailed();
                    }
                });
            }
        });
    }

    /**
     * Low-priority background task for look-ahead warming. De-duplicated by {@code key};
     * silently dropped when the (small) queue is full.
     */
    public static void runPreload(@NonNull String key, @NonNull Runnable body) {
        if (PRELOAD_INFLIGHT.putIfAbsent(key, Boolean.TRUE) != null) return;
        PRELOAD_EXEC.execute(new PreloadTask(key, body));
    }

    /** Returns a bitmap this pipeline created, that ended up unused, to the inBitmap pool. */
    public static void recycleUnused(@Nullable Bitmap b) {
        if (b != null && isOwned(b)) REUSE.release(b);
    }

    // ── Decode ───────────────────────────────────────────────────────────

    /**
     * Blocking decode. Call from a background thread only. Returns an app-owned
     * bitmap sized like Glide's CENTER_OUTSIDE (>= target on both axes, never upscaled),
     * or null on any failure.
     */
    @Nullable
    public static Bitmap decodeBlocking(@NonNull File file, int targetW, int targetH) {
        // v612: named trace section so Macrobenchmark's TraceSectionMetric can sum decode time.
        android.os.Trace.beginSection("BubbleBitmap#decode");
        try {
            return decodeBlockingImpl(file, targetW, targetH);
        } finally {
            android.os.Trace.endSection();
        }
    }

    @Nullable
    private static Bitmap decodeBlockingImpl(@NonNull File file, int targetW, int targetH) {
        if (targetW <= 0 || targetH <= 0) return null;
        final String path = file.getAbsolutePath();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        final int srcW = bounds.outWidth, srcH = bounds.outHeight;
        if (srcW <= 0 || srcH <= 0) return null;

        final boolean jpeg = "image/jpeg".equals(bounds.outMimeType);
        final int orientation = jpeg ? readExifOrientation(path) : android.media.ExifInterface.ORIENTATION_NORMAL;
        final boolean swap = orientation == android.media.ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90
                || orientation == android.media.ExifInterface.ORIENTATION_TRANSVERSE
                || orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270;
        final int dispW = swap ? srcH : srcW;
        final int dispH = swap ? srcW : srcH;

        float scale = Math.max(targetW / (float) dispW, targetH / (float) dispH);
        if (scale > 1f) scale = 1f; // never upscale
        int sample = 1;
        final int maxSample = Math.max(1, (int) (1f / scale));
        while (sample * 2 <= maxSample) sample *= 2;
        final float residual = scale * sample;          // (0.5, 1]
        final boolean needScale = residual < 0.98f;

        Bitmap out = null;

        // 1) HARDWARE: display-only, upload happens on this thread.
        final boolean orientationNormal = orientation == android.media.ExifInterface.ORIENTATION_NORMAL
                || orientation == android.media.ExifInterface.ORIENTATION_UNDEFINED;
        if (ENABLE_HARDWARE && Build.VERSION.SDK_INT >= 28 && orientationNormal
                && Math.min(targetW, targetH) >= HW_MIN_DIM_PX) {
            out = decodeAttempt(path, sample, residual, needScale, Bitmap.Config.HARDWARE, null);
            if (out != null && (out.getWidth() <= 0 || out.getHeight() <= 0)) out = null;
        }

        // 2) Software: RGB_565 for opaque JPEG, ARGB_8888 otherwise, with inBitmap reuse.
        if (out == null) {
            final Bitmap.Config cfg = jpeg ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            final int bpp = cfg == Bitmap.Config.RGB_565 ? 2 : 4;
            // +2px safety margin on each axis: inBitmap needs allocation >= decoded size.
            final int estW = (int) Math.ceil(srcW * (double) scale) + 2;
            final int estH = (int) Math.ceil(srcH * (double) scale) + 2;
            final Bitmap reuse = orientationNormal ? REUSE.take(cfg, estW * estH * bpp) : null;
            out = decodeAttempt(path, sample, residual, needScale, cfg, reuse);
            if (out == null && reuse != null) {
                try { reuse.recycle(); } catch (Throwable ignored) {}
                out = decodeAttempt(path, sample, residual, needScale, cfg, null);
            }
        }
        if (out == null) return null;

        // 3) EXIF orientation (software bitmaps only; rotated results stay software).
        if (!orientationNormal) {
            Bitmap rotated = applyOrientation(out, orientation);
            if (rotated != out) {
                REUSE.release(out); // intermediate, never exposed
                out = rotated;
            }
        }
        markOwned(out);
        return out;
    }

    @Nullable
    private static Bitmap decodeAttempt(String path, int sample, float residual, boolean needScale,
                                        Bitmap.Config cfg, @Nullable Bitmap reuse) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = cfg;
        if (needScale) {
            o.inScaled = true;
            o.inDensity = 10000;
            o.inTargetDensity = Math.max(1, Math.round(10000f * residual));
        } else {
            o.inScaled = false;
        }
        // Software results are decoded mutable so they stay eligible for the reuse pool
        // (HARDWARE bitmaps must be immutable). Nothing ever writes pixels into them.
        if (!(Build.VERSION.SDK_INT >= 26 && cfg == Bitmap.Config.HARDWARE)) o.inMutable = true;
        if (reuse != null) o.inBitmap = reuse;
        try {
            return BitmapFactory.decodeFile(path, o);
        } catch (IllegalArgumentException e) {
            return null; // inBitmap not usable for this decode
        } catch (OutOfMemoryError e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readExifOrientation(String path) {
        try {
            return new android.media.ExifInterface(path).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable t) {
            return android.media.ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyOrientation(Bitmap src, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_180:      m.setRotate(180); break;
            case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:   m.setRotate(180); m.postScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_TRANSPOSE:       m.setRotate(90);  m.postScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_90:       m.setRotate(90);  break;
            case android.media.ExifInterface.ORIENTATION_TRANSVERSE:      m.setRotate(-90); m.postScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_270:      m.setRotate(-90); break;
            default: return src;
        }
        try {
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Throwable t) {
            return src; // OOM etc: show unrotated rather than nothing
        }
    }

    // ── inBitmap reuse pool ──────────────────────────────────────────────
    // Only ever fed bitmaps that no view / cache can hold (see class doc, point 4).
    private static final class ReusePool {
        private static final class Entry {
            final Bitmap bmp; final int size;
            Entry(Bitmap bmp, int size) { this.bmp = bmp; this.size = size; }
        }
        private final ArrayDeque<Entry> items = new ArrayDeque<>();
        private final int maxBytes;
        private int bytes;

        ReusePool(int maxBytes) { this.maxBytes = maxBytes; }

        @Nullable
        synchronized Bitmap take(Bitmap.Config cfg, int minBytes) {
            Iterator<Entry> it = items.iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (e.bmp.isRecycled()) { it.remove(); bytes -= e.size; continue; }
                if (e.bmp.getConfig() == cfg && e.size >= minBytes && e.size <= minBytes * 2) {
                    it.remove();
                    bytes -= e.size;
                    return e.bmp;
                }
            }
            return null;
        }

        synchronized void release(@Nullable Bitmap b) {
            if (b == null || b.isRecycled() || !b.isMutable()) return;
            Bitmap.Config c = b.getConfig();
            if (c == null || (Build.VERSION.SDK_INT >= 26 && c == Bitmap.Config.HARDWARE)) return;
            final int size = b.getAllocationByteCount();
            if (size > maxBytes / 2) return;
            items.addFirst(new Entry(b, size));
            bytes += size;
            while (bytes > maxBytes && !items.isEmpty()) {
                Entry old = items.removeLast();
                bytes -= old.size;
                try { old.bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static final ReusePool REUSE = new ReusePool(4 * 1024 * 1024);
}
