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
 * GradientDrawable.
 */
public final class PlayRingGlowDrawable extends Drawable {

    // NOTE: previously cached a SHARED instance per size (same pattern
    // StoryRingGradientDrawable#withStrokeDp used to). Removed for the same
    // reason: a Drawable's bounds/callback are single mutable instance
    // state, so if two on-screen views ever bind the same fixed 76dp size
    // at once, the second one silently steals the first's callback and
    // overwrites its bounds — the first view is left showing (or animating)
    // stale state. Always returning a fresh wrapper here costs one small
    // object; the real expensive work (rasterizing the glow) is still
    // cached by size in PlayRingGlowCache, so this stays cheap.

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap glowBitmap;

    private PlayRingGlowDrawable() {}

    /** Convenience factory: pass the view's fixed size in dp + display density. */
    public static PlayRingGlowDrawable withSizeDp(float sizeDp, float density) {
        return new PlayRingGlowDrawable();
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
