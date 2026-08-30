package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * PlayRingGlowDrawable — draws the {@link PlayRingGlowCache} bitmap via a
 * plain texture blit, replacing bg_play_ring_glow.xml's procedural
 * GradientDrawable. Same shared-instance-per-size pattern as
 * {@link StoryRingGradientDrawable#withStrokeDp}, since this glow is always
 * bound at the exact same fixed dp size (76dp) wherever it's used.
 */
public final class PlayRingGlowDrawable extends Drawable {

    private static final java.util.concurrent.ConcurrentHashMap<Float, PlayRingGlowDrawable>
            SHARED = new java.util.concurrent.ConcurrentHashMap<>();

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap glowBitmap;

    private PlayRingGlowDrawable() {}

    /** Convenience factory: pass the view's fixed size in dp + display density. Returns a SHARED instance for this exact size — no allocation on repeat calls. */
    public static PlayRingGlowDrawable withSizeDp(float sizeDp, float density) {
        float sizePx = sizeDp * density;
        return SHARED.computeIfAbsent(sizePx, s -> new PlayRingGlowDrawable());
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        int w = bounds.width();
        int h = bounds.height();
        glowBitmap = (w <= 0 || h <= 0) ? null : PlayRingGlowCache.get(w, h);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        if (glowBitmap != null && !glowBitmap.isRecycled()) {
            canvas.drawBitmap(glowBitmap, bounds.left, bounds.top, bitmapPaint);
        }
    }

    @Override public void setAlpha(int alpha) { bitmapPaint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { bitmapPaint.setColorFilter(colorFilter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
