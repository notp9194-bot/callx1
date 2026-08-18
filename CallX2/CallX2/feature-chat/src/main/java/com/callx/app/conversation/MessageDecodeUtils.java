package com.callx.app.conversation;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * PERF #6 — Async large-image decode via ImageDecoder (API 28+) /
 * BitmapFactory (pre-28 fallback).
 *
 * Why this exists:
 *   Glide handles thumbnail-sized message bubbles perfectly, but MediaViewerActivity
 *   and any code that wants a full-resolution image without Glide overhead can use
 *   this utility to:
 *     1. Decode large local Files on a background thread (never on the UI thread).
 *     2. Downsample to a target size using ImageDecoder.setTargetSize() (API 28+),
 *        which applies the downsample inside the codec before any pixels are
 *        allocated — smaller peak memory than BitmapFactory + inSampleSize which
 *        still reads the full encoded file into a native buffer first.
 *     3. Fall back to BitmapFactory + calculated inSampleSize on older APIs.
 *
 * Usage:
 *   MessageDecodeUtils.decodeAsync(file, targetW, targetH, (bitmap) -> {
 *       // called on the MAIN thread; bitmap may be null on error
 *       imageView.setImageBitmap(bitmap);
 *   });
 */
public final class MessageDecodeUtils {

    private static final Executor sDecodePool = Executors.newFixedThreadPool(2);
    private static final Handler  sMain       = new Handler(Looper.getMainLooper());

    public interface DecodeCallback {
        void onDecoded(Bitmap bitmap);
    }

    private MessageDecodeUtils() {}

    // ── PERF #7: Bitmap pool for inBitmap reuse ──────────────────────────────
    // Why: decodeWithBitmapFactory() used to call BitmapFactory.decodeFile()
    // with a fresh Options every time, which means a brand-new pixel buffer
    // allocation on EVERY decode — even though message-bubble decodes happen
    // constantly during a fling and the resulting Bitmap is usually discarded
    // within a frame or two (replaced by the next bubble, or by Glide's own
    // thumbnail once it loads). Each of those allocations is a native buffer
    // the GC eventually has to reclaim, which shows up as GC pauses/jank on
    // scroll-heavy chats with lots of local images.
    //
    // BitmapFactory.Options.inBitmap lets the decoder write pixels into an
    // already-allocated Bitmap instead of asking the allocator for a new
    // buffer. Since API 19, the reuse rule is just "allocationByteCount of
    // the supplied bitmap >= byte count the decode will need" — dimensions
    // don't have to match, BitmapFactory reconfigures the buffer internally.
    // We always decode into RGB_565 here (see decodeWithBitmapFactory), so
    // every pooled bitmap has a consistent, always-compatible config.
    //
    // Design: a tiny fixed-capacity pool (4 slots), thread-safe since decodes
    // run on a 2-thread pool. Callers that are done displaying a bitmap
    // decoded through this path (e.g. MediaViewerActivity closing, or a
    // bubble holder swapping in a different image) can call
    // releaseBitmapToPool() to make its buffer available for the next
    // decode instead of leaving it purely to the GC. Never calling it is
    // safe — the pool is a cache, not something anything depends on for
    // correctness.
    private static final int POOL_CAPACITY = 4;
    private static final java.util.ArrayDeque<Bitmap> sBitmapPool =
            new java.util.ArrayDeque<>(POOL_CAPACITY);
    private static final Object sPoolLock = new Object();

    /**
     * Returns a bitmap previously decoded via this class to the reuse pool.
     * Safe to call with any bitmap (immutable/recycled/null ones are simply
     * ignored). Once handed to this method the caller must not keep drawing
     * with the bitmap — it may be mutated by a subsequent decode at any time.
     */
    public static void releaseBitmapToPool(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || !bitmap.isMutable()) return;
        synchronized (sPoolLock) {
            if (sBitmapPool.size() >= POOL_CAPACITY) {
                Bitmap evicted = sBitmapPool.pollFirst();
                if (evicted != null && !evicted.isRecycled()) evicted.recycle();
            }
            sBitmapPool.addLast(bitmap);
        }
    }

    /** Pulls the first pooled bitmap big enough to hold {@code requiredBytes}, or null. */
    private static Bitmap acquirePoolCandidate(long requiredBytes) {
        synchronized (sPoolLock) {
            java.util.Iterator<Bitmap> it = sBitmapPool.iterator();
            while (it.hasNext()) {
                Bitmap b = it.next();
                if (b.isRecycled()) {
                    it.remove();
                    continue;
                }
                if (b.getAllocationByteCount() >= requiredBytes) {
                    it.remove();
                    return b;
                }
            }
            return null;
        }
    }

    /**
     * Decodes {@code file} asynchronously, downsampling to fit within
     * {@code targetW × targetH} pixels, then delivers the result to
     * {@code callback} on the main thread.
     *
     * @param file    Local file to decode (must exist and be readable).
     * @param targetW Maximum output width in pixels.
     * @param targetH Maximum output height in pixels.
     * @param callback Invoked on the main thread; {@code bitmap} is null on error.
     */
    public static void decodeAsync(File file, int targetW, int targetH, DecodeCallback callback) {
        if (file == null || !file.exists() || targetW <= 0 || targetH <= 0) {
            if (callback != null) sMain.post(() -> callback.onDecoded(null));
            return;
        }
        sDecodePool.execute(() -> {
            Bitmap result = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // API 28+: ImageDecoder — downsampling happens inside the codec
                    result = decodeWithImageDecoder(file, targetW, targetH);
                }
                if (result == null) {
                    // Pre-28 fallback (or if ImageDecoder threw)
                    result = decodeWithBitmapFactory(file, targetW, targetH);
                }
            } catch (Exception ignored) {
                result = null;
            }
            final Bitmap finalResult = result;
            if (callback != null) sMain.post(() -> callback.onDecoded(finalResult));
        });
    }

    // API 28+: use ImageDecoder for in-codec downscaling (lower peak RAM)
    @android.annotation.TargetApi(Build.VERSION_CODES.P)
    private static Bitmap decodeWithImageDecoder(File file, int targetW, int targetH)
            throws java.io.IOException {
        android.graphics.ImageDecoder.Source src =
                android.graphics.ImageDecoder.createSource(file);
        return android.graphics.ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
            android.util.Size original = info.getSize();
            int origW = original.getWidth();
            int origH = original.getHeight();
            if (origW > targetW || origH > targetH) {
                // Scale down to fit, preserving aspect ratio
                float scale = Math.min((float) targetW / origW, (float) targetH / origH);
                decoder.setTargetSize(
                        Math.max(1, Math.round(origW * scale)),
                        Math.max(1, Math.round(origH * scale)));
            }
            // Allocate into software memory so the bitmap can be used
            // on a Picture-recording Canvas (hardware bitmaps cannot be
            // drawn onto a software Canvas / Picture).
            decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    // Pre-28 fallback: BitmapFactory with inSampleSize
    private static Bitmap decodeWithBitmapFactory(File file, int targetW, int targetH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize;
        opts.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory of ARGB_8888 for opaque images
        opts.inMutable = true; // so this decode's own result is poolable later too

        // PERF #7: try to reuse a pooled buffer instead of a fresh allocation.
        // Rough (over-)estimate of the post-sample decode size — BitmapFactory
        // will reject inBitmap and fall back internally only if this estimate
        // undershoots, which the +1 row/col padding below guards against.
        int estW = (bounds.outWidth  + sampleSize - 1) / sampleSize;
        int estH = (bounds.outHeight + sampleSize - 1) / sampleSize;
        long requiredBytes = (long) (estW + 1) * (estH + 1) * 2L; // RGB_565 = 2 bytes/px
        Bitmap candidate = acquirePoolCandidate(requiredBytes);
        if (candidate != null) {
            opts.inBitmap = candidate;
            try {
                return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            } catch (IllegalArgumentException e) {
                // Candidate turned out incompatible (shouldn't happen given the
                // byteCount check, but the config/mutability contract isn't
                // 100% guaranteed across OEM skins) — recycle it and decode fresh.
                if (!candidate.isRecycled()) candidate.recycle();
                opts.inBitmap = null;
            }
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    /**
     * Returns the largest power-of-2 inSampleSize such that the decoded image
     * is at least {@code targetW × targetH} pixels in both dimensions.
     */
    private static int calculateInSampleSize(int origW, int origH, int targetW, int targetH) {
        int inSampleSize = 1;
        if (origW > targetW || origH > targetH) {
            int halfW = origW / 2;
            int halfH = origH / 2;
            while ((halfW / inSampleSize) >= targetW && (halfH / inSampleSize) >= targetH) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
