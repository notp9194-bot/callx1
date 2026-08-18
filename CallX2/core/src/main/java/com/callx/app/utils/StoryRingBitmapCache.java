package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * StoryRingBitmapCache — v41 "ulta advance" perf pass, v42 hardware-bitmap pass.
 *
 * WHY v40 WASN'T ENOUGH: {@link StoryRingShaderCache} stopped us from
 * re-allocating a SweepGradient/Matrix on every bind — good — but a
 * SweepGradient is still a *procedural* shader. Even a shared/cached shader
 * instance gets its gradient function re-evaluated PER PIXEL on every single
 * draw() call/frame. During a fast fling with many rings on screen
 * (chat list, status row, reel comments) that's real per-frame GPU/CPU cost,
 * repeated every frame those rings are visible — cached shader object or not.
 *
 * v41 FIX: pre-rasterize the ring exactly once per distinct (size,
 * strokeWidth) into a small shared ARGB_8888 Bitmap. After that, drawing a
 * ring is just `canvas.drawBitmap(...)` — a plain hardware texture blit. No
 * gradient math, no per-pixel shader evaluation, on any subsequent frame or
 * any other ring of the same size anywhere in the app.
 *
 * v42 FIX (this pass): on API 26+ the rasterized ARGB_8888 bitmap is
 * upgraded once to {@link Bitmap.Config#HARDWARE}. A HARDWARE bitmap lives
 * directly in GPU memory — every draw of it skips the normal CPU→GPU texture
 * upload/copy that a regular software bitmap needs on first use each frame
 * cycle, which matters when the same tiny bitmap is being blitted by dozens
 * of RecyclerView rows during a fling. On API 23-25 (this app's minSdk is
 * 23), HARDWARE bitmaps aren't available, so we transparently keep the
 * ARGB_8888 bitmap there — behavior is identical, just without the extra
 * GPU-residency win on very old devices.
 *
 * Cache is capped (few KB total — rings are tiny, e.g. 44-96px square) and
 * kept alive for the process lifetime, same pattern as
 * {@link StoryRingShaderCache}.
 */
public final class StoryRingBitmapCache {

    private StoryRingBitmapCache() {}

    private static final int MAX_CACHED_BITMAPS = 8;

    private static final Map<String, Bitmap> CACHE =
        new LinkedHashMap<String, Bitmap>(MAX_CACHED_BITMAPS, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                if (size() > MAX_CACHED_BITMAPS) {
                    Bitmap old = eldest.getValue();
                    if (old != null && !old.isRecycled()) old.recycle();
                    return true;
                }
                return false;
            }
        };

    private static String keyFor(int w, int h, float strokePx) {
        // Round stroke to 0.1px precision - plenty for a hairline ring,
        // keeps the key space (and therefore the cache) tiny.
        return w + "x" + h + "@" + Math.round(strokePx * 10f);
    }

    /**
     * Returns a shared, pre-rasterized bitmap of the seamless Instagram
     * gradient ring for the given local width/height/stroke. Built once per
     * distinct (size, stroke) combo, reused by every ring after that -
     * including across chat list, status feed, reels, and profile.
     *
     * On API 26+ the returned bitmap is GPU-resident (HARDWARE config); on
     * API 23-25 it's a regular ARGB_8888 bitmap. Callers just draw it with
     * {@code canvas.drawBitmap(...)} either way — no special-casing needed.
     */
    public static synchronized Bitmap get(int width, int height, float strokePx) {
        if (width <= 0 || height <= 0 || strokePx <= 0) return null;

        String key = keyFor(width, height, strokePx);
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap software;
        try {
            software = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            // Extremely defensive - ring bitmaps are tiny, but never let a
            // gradient ring OOM-crash the app. Caller falls back to
            // shader-only drawing when this returns null.
            return null;
        }

        Canvas canvas = new Canvas(software);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokePx);

        // Reuse the already-cached procedural shader just this ONE time,
        // to rasterize it - after this, nothing procedural is left.
        SweepGradient sg = StoryRingShaderCache.get(width, height);
        paint.setShader(sg);

        float half = strokePx / 2f;
        RectF oval = new RectF(half, half, width - half, height - half);
        canvas.drawOval(oval, paint);

        Bitmap finalBitmap = software;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Upload once to GPU memory. If this fails for any reason
            // (low-memory device quirks, etc.) we just keep the software
            // bitmap — still correct, just without the extra GPU-residency
            // win.
            try {
                Bitmap hw = software.copy(Bitmap.Config.HARDWARE, false);
                if (hw != null) {
                    software.recycle();
                    finalBitmap = hw;
                }
            } catch (Throwable ignored) {
                // Keep software bitmap as-is.
            }
        }

        CACHE.put(key, finalBitmap);
        return finalBitmap;
    }
}
