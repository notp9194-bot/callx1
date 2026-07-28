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
 *   MODE_DOMINANT → a seamless sweep gradient built entirely from shades of
 *                   the chosen color (dark → base → light → base → dark), so
 *                   the picked color is dominant across the whole ring while
 *                   still reading as a "gradient ring" rather than a flat one.
 *                   Same palindrome trick as StoryRingGradientDrawable
 *                   (first color == last color) keeps the loop seamless.
 *
 * Rasterized to a small shared bitmap once per distinct (mode, color, size,
 * stroke) combo — same perf approach as {@link StoryRingBitmapCache} — so
 * repeat binds in the highlights row are a plain bitmap blit, not a fresh
 * shader evaluation.
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

        if (ringBitmap != null && !ringBitmap.isRecycled()) {
            canvas.drawBitmap(ringBitmap, bounds.left, bounds.top, bitmapPaint);
        } else {
            // Fallback: flat stroke in the base color so the ring never
            // silently disappears (e.g. bitmap cache OOM).
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

            CACHE.put(key, bmp);
            return bmp;
        }

        /** Seamless (palindrome) sweep gradient built purely from shades of
         *  {@code baseColor} — dark → base → light → base → dark — so the
         *  user's chosen color dominates the whole ring. */
        private static SweepGradient buildDominantShader(int w, int h, int baseColor) {
            float[] hsv = new float[3];
            Color.colorToHSV(baseColor, hsv);

            float[] darkHsv  = {hsv[0], hsv[1], clamp(hsv[2] * 0.55f)};
            float[] lightHsv = {hsv[0], Math.max(0f, hsv[1] * 0.65f), clamp(hsv[2] * 1.25f + 0.1f)};
            int dark  = Color.HSVToColor(darkHsv);
            int light = Color.HSVToColor(lightHsv);

            int[] colors = { dark, baseColor, light, baseColor, dark };
            float[] positions = { 0f, 0.28f, 0.5f, 0.72f, 1f };

            float cx = w / 2f;
            float cy = h / 2f;
            SweepGradient sg = new SweepGradient(cx, cy, colors, positions);
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(-90, cx, cy); // start at 12 o'clock, like the app default ring
            sg.setLocalMatrix(matrix);
            return sg;
        }

        private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    }
}
