package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
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
 * StoryRingGradientDrawable — seamless Instagram-style story ring.
 *
 * WHY THIS EXISTS (v39 fix):
 * The old `story_ring_insta_gradient.xml` / `bg_story_ring.xml` used Android's
 * XML {@code <gradient android:type="sweep">}, which only supports 3 color
 * stops: startColor / centerColor / endColor. That gradient sweeps
 * start(0°) → center(180°) → end(360°) and then jumps hard back to
 * startColor — it is NOT a closed loop. The result is a visible seam: a
 * sudden edge where the last color (orange) meets the first color (purple)
 * instead of blending, which is exactly the "separate purple/orange band"
 * bug reported against the story ring.
 *
 * This draws the same multi-stop {@link SweepGradient} already used
 * correctly by {@code ChatListStoryRingView} (chat list story rings) — the
 * color array is a palindrome (first color == last color), so the sweep
 * blends back into itself with zero seam, matching Instagram's real ring.
 * Kept as a shared Drawable (rather than a custom View) so it can be applied
 * via {@code setBackground()/setImageDrawable()} on any existing ImageView.
 *
 * v40 PERF PASS: shader instance shared via {@link StoryRingShaderCache}.
 *
 * v41 PERF PASS (bitmap blit): drawing no longer strokes a live shader at
 * all. {@link StoryRingBitmapCache} pre-rasterizes the ring to a shared
 * bitmap once per size; draw() is now a single {@code drawBitmap} texture
 * blit — zero per-pixel gradient evaluation on every frame/scroll.
 */
public final class StoryRingGradientDrawable extends Drawable {

    // Only used as an instant fallback color before the bitmap is ready.
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Paint used purely for the bitmap blit (carries alpha/colorFilter).
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float strokePx;

    private Bitmap ringBitmap;

    public StoryRingGradientDrawable(float strokeWidthPx) {
        this.strokePx = strokeWidthPx;
        fallbackPaint.setStyle(Paint.Style.STROKE);
        fallbackPaint.setStrokeWidth(strokePx);
        fallbackPaint.setColor(0xFF7D00FF); // fallback before bounds/bitmap are ready (purple — ring's start color)
    }

    /** Convenience factory: pass a dp value + the view's display density. */
    public static StoryRingGradientDrawable withStrokeDp(float strokeWidthDp, float density) {
        return new StoryRingGradientDrawable(strokeWidthDp * density);
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
        // Pre-rasterized, shared across every ring of this exact size —
        // built once, then it's a plain cache lookup from here on.
        ringBitmap = StoryRingBitmapCache.get(w, h, strokePx);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;

        if (ringBitmap != null && !ringBitmap.isRecycled()
                && canvas.isHardwareAccelerated()) {
            // Fast path: plain texture blit, no shader math per pixel.
            canvas.drawBitmap(ringBitmap, bounds.left, bounds.top, bitmapPaint);
        } else if (ringBitmap != null && !ringBitmap.isRecycled()
                && ringBitmap.getConfig() != Bitmap.Config.HARDWARE) {
            // Non-accelerated canvas but bitmap is safe (software) to draw.
            canvas.drawBitmap(ringBitmap, bounds.left, bounds.top, bitmapPaint);
        } else {
            // Rare fallback: HARDWARE bitmap on a non-accelerated canvas
            // (would throw), or bitmap cache OOM'd — draw a flat stroke so
            // the ring never silently disappears or crashes.
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
}
