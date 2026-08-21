package com.callx.app.utils;

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
 * StoryRingRevealDrawable — one-shot "drawing itself in" reveal for a story
 * ring: starts from a single point at the top and sweeps clockwise,
 * progressively revealing the same seamless Instagram gradient used by the
 * static {@link StoryRingGradientDrawable}, up to whatever angle
 * {@link #setSweepDegrees} is currently at.
 *
 * USE CASE: the very first time a viewer opens a profile after a new story
 * was posted, the ring shouldn't just pop in statically — it plays this
 * sweep once (0° → 360° over ~2s), then the caller swaps back to the cheap
 * bitmap-cached {@link StoryRingGradientDrawable} for the normal, permanent
 * state. See UserReelsActivity's story-ring handling for the orchestration.
 *
 * PERF NOTE: this draws a live arc with a shader every frame, which is
 * intentionally NOT bitmap-cached like the static ring — because the sweep
 * angle changes every frame during the animation, a bitmap cache wouldn't
 * help (there's nothing repeated to reuse). That's fine here: this drawable
 * only exists for ~2 seconds, once, per new story per viewer — nowhere near
 * the RecyclerView-scale, every-frame-forever cost the static ring needed to
 * avoid. It still reuses the shared cached {@link SweepGradient} from
 * {@link StoryRingShaderCache} rather than building its own, so even this
 * transient animation allocates zero shader objects.
 */
public final class StoryRingRevealDrawable extends Drawable {

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float strokePx;

    private float sweepDegrees = 0f;

    public StoryRingRevealDrawable(float strokeWidthPx) {
        this.strokePx = strokeWidthPx;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(strokePx);
        ringPaint.setStrokeCap(Paint.Cap.ROUND); // nice rounded starting point
        ringPaint.setColor(0xFF7D00FF); // fallback before bounds/shader are ready (purple — ring start color)
    }

    /** 0f = nothing drawn yet (just about to start), 360f = full closed ring. */
    public void setSweepDegrees(float degrees) {
        if (degrees < 0f) degrees = 0f;
        if (degrees > 360f) degrees = 360f;
        if (degrees == sweepDegrees) return;
        sweepDegrees = degrees;
        invalidateSelf();
    }

    public float getSweepDegrees() { return sweepDegrees; }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0) return;
        // Same shared/cached shader the static ring uses — the reveal ends
        // up pixel-identical to the ring it settles into.
        SweepGradient sg = StoryRingShaderCache.get(w, h);
        if (sg != null) ringPaint.setShader(sg);
        float half = strokePx / 2f;
        oval.set(half, half, w - half, h - half);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (oval.width() <= 0 || oval.height() <= 0 || sweepDegrees <= 0f) return;
        Rect bounds = getBounds();
        int saveCount = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        // -90 = start at 12 o'clock, sweeping clockwise — matches the
        // shader's own -90° rotation so revealed colors line up exactly
        // with where they'll be in the final static ring.
        canvas.drawArc(oval, -90f, sweepDegrees, false, ringPaint);
        canvas.restoreToCount(saveCount);
    }

    @Override public void setAlpha(int alpha) { ringPaint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { ringPaint.setColorFilter(colorFilter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
