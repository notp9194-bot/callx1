package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * HighlightRingDrawable — user-customizable highlight ring.
 *
 * Lets a highlight album use a color the user picked instead of the fixed
 * app-wide Instagram rainbow ring ({@link StoryRingGradientDrawable}):
 *
 *   MODE_SOLID    → flat stroke in the exact color the user chose. No shader
 *                   at all, so there's zero risk of a gradient seam.
 *   MODE_DOMINANT → a seamless sweep gradient that mixes rainbow hue accents
 *                   into the ring, while the chosen ("dominant") color still
 *                   covers most of it — other colors are visibly present,
 *                   but the dominant one clearly reads as the ring's main
 *                   color. Same palindrome trick as StoryRingGradientDrawable
 *                   (first color == last color) keeps the loop seamless.
 *
 * Rasterized to a small shared bitmap once per distinct (mode, color, size,
 * stroke) combo — same perf approach as {@link StoryRingBitmapCache} — so
 * repeat binds in the highlights row are a plain bitmap blit, not a fresh
 * shader evaluation.
 *
 * PERF PASS ("ulta advance" — same pass already done for the default story
 * ring in {@link StoryRingBitmapCache}): on API 26+ the rasterized bitmap is
 * upgraded once to {@link Bitmap.Config#HARDWARE}, so it lives directly in
 * GPU memory and every subsequent blit (every row bind during a fling of the
 * Highlights row) skips the CPU→GPU texture upload a software bitmap needs.
 * draw() mirrors {@link StoryRingGradientDrawable}'s canvas checks so a
 * HARDWARE bitmap is never handed to a non-hardware-accelerated canvas
 * (which would throw) — it falls back to the flat-stroke paint instead.
 * On API 23-25 (this app's minSdk) HARDWARE bitmaps aren't available, so the
 * ARGB_8888 bitmap is kept as-is — same visual result, just without the
 * extra GPU-residency win on very old devices.
 */
public final class HighlightRingDrawable extends Drawable {

    public static final String MODE_SOLID    = "solid";
    public static final String MODE_DOMINANT = "dominant";

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float strokePx;
    private final int color;
    private final boolean dominant;

    private Bitmap ringBitmap;

    private HighlightRingDrawable(int color, boolean dominant, float strokePx) {
        this.color = color;
        this.dominant = dominant;
        this.strokePx = strokePx;
        fallbackPaint.setStyle(Paint.Style.STROKE);
        fallbackPaint.setStrokeWidth(strokePx);
        fallbackPaint.setColor(color);
    }

    /**
     * @param mode {@link #MODE_SOLID} or {@link #MODE_DOMINANT}; anything
     *             else falls back to solid.
     */
    public static HighlightRingDrawable withStrokeDp(int color, String mode,
                                                       float strokeWidthDp, float density) {
        return new HighlightRingDrawable(color, MODE_DOMINANT.equals(mode), strokeWidthDp * density);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0) {
            ringBitmap = null;
            return;
        }
        ringBitmap = HighlightRingBitmapCache.get(w, h, strokePx, color, dominant);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;

        if (ringBitmap != null && !ringBitmap.isRecycled() && canvas.isHardwareAccelerated()) {
            // Fast path: plain texture blit, no shader math per pixel — and
            // if the bitmap is HARDWARE-config, no CPU→GPU upload either.
            canvas.drawBitmap(ringBitmap, bounds.left, bounds.top, bitmapPaint);
        } else if (ringBitmap != null && !ringBitmap.isRecycled()
                && ringBitmap.getConfig() != Bitmap.Config.HARDWARE) {
            // Non-accelerated canvas but bitmap is safe (software) to draw.
            canvas.drawBitmap(ringBitmap, bounds.left, bounds.top, bitmapPaint);
        } else {
            // Rare fallback: HARDWARE bitmap on a non-accelerated canvas
            // (drawing it would throw), or bitmap cache OOM'd — draw a flat
            // stroke so the ring never silently disappears or crashes.
            float half = strokePx / 2f;
            RectF oval = new RectF(bounds.left + half, bounds.top + half,
                                    bounds.right - half, bounds.bottom - half);
            canvas.drawOval(oval, fallbackPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        bitmapPaint.setAlpha(alpha);
        fallbackPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        bitmapPaint.setColorFilter(colorFilter);
        fallbackPaint.setColorFilter(colorFilter);
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    // ── Shared bitmap cache, keyed by (mode, color, size, stroke) ──────────
    private static final class HighlightRingBitmapCache {
        private HighlightRingBitmapCache() {}

        private static final int MAX_CACHED = 16;
        private static final java.util.Map<String, Bitmap> CACHE =
            new java.util.LinkedHashMap<String, Bitmap>(MAX_CACHED, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> eldest) {
                    if (size() > MAX_CACHED) {
                        Bitmap old = eldest.getValue();
                        if (old != null && !old.isRecycled()) old.recycle();
                        return true;
                    }
                    return false;
                }
            };

        static synchronized Bitmap get(int w, int h, float strokePx, int color, boolean dominant) {
            if (w <= 0 || h <= 0 || strokePx <= 0) return null;
            String key = w + "x" + h + "@" + Math.round(strokePx * 10f)
                    + "#" + Integer.toHexString(color) + (dominant ? "d" : "s");
            Bitmap cached = CACHE.get(key);
            if (cached != null && !cached.isRecycled()) return cached;

            Bitmap bmp;
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError oom) {
                return null;
            }

            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokePx);

            if (dominant) {
                paint.setShader(buildDominantShader(w, h, color));
            } else {
                paint.setColor(color);
            }

            float half = strokePx / 2f;
            RectF oval = new RectF(half, half, w - half, h - half);
            canvas.drawOval(oval, paint);

            Bitmap finalBitmap = bmp;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // Upload once to GPU memory so every subsequent blit of this
                // ring (every scroll/bind of every row using this exact
                // color+mode+size+stroke) skips the normal CPU→GPU texture
                // upload a software bitmap needs each time. Same "ulta
                // advance" hardware-bitmap pass already applied to
                // StoryRingBitmapCache — kept as a best-effort upgrade so a
                // failure on odd devices just keeps the software bitmap.
                try {
                    Bitmap hw = bmp.copy(Bitmap.Config.HARDWARE, false);
                    if (hw != null) {
                        bmp.recycle();
                        finalBitmap = hw;
                    }
                } catch (Throwable ignored) {
                    // Keep software bitmap as-is.
                }
            }

            CACHE.put(key, finalBitmap);
            return finalBitmap;
        }

        /** Seamless (palindrome) sweep gradient that mixes full rainbow hues
         *  into the ring, while the user's chosen ("dominant") color still
         *  covers most of it: the ring is divided into equal slices around
         *  the circle, and within each slice the dominant color holds a wide
         *  plateau with only a brief rainbow-hue accent — so other colors
         *  are visibly present, but the dominant one clearly reads as the
         *  ring's main color. */
        private static SweepGradient buildDominantShader(int w, int h, int baseColor) {
            int[] accentHues = { 0, 40, 80, 150, 210, 270, 320 }; // red..magenta spread
            int n = accentHues.length;

            int[] colors = new int[1 + n * 2 + 1];
            float[] positions = new float[colors.length];
            int idx = 0;
            colors[idx] = baseColor; positions[idx] = 0f; idx++;
            for (int i = 0; i < n; i++) {
                float sliceStart = (float) i / n;
                float sliceSize = 1f / n;
                float accentPos = sliceStart + sliceSize * 0.55f; // accent appears well into the slice
                float returnPos = sliceStart + sliceSize * 0.72f; // short blip, then back to dominant

                int accent = Color.HSVToColor(new float[]{ accentHues[i], 0.85f, 0.95f });
                colors[idx] = accent; positions[idx] = accentPos; idx++;
                colors[idx] = baseColor; positions[idx] = returnPos; idx++;
            }
            colors[idx] = baseColor; positions[idx] = 1f;

            float cx = w / 2f;
            float cy = h / 2f;
            SweepGradient sg = new SweepGradient(cx, cy, colors, positions);
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(-90, cx, cy); // start at 12 o'clock, like the app default ring
            sg.setLocalMatrix(matrix);
            return sg;
        }
    }
}
