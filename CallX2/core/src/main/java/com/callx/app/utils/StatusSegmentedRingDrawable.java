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
 * WhatsApp-style segmented status ring: one arc per status, gaps in between.
 * The first (total - unseen) segments are drawn flat "seen" colour, the rest use the
 * same story gradient as {@link StoryRingGradientDrawable}. Segments start at 12 o'clock, clockwise.
 */
public final class StatusSegmentedRingDrawable extends Drawable {

    /** Above this many segments the arcs get too tiny — callers should use the plain full ring. */
    public static final int MAX_SEGMENTS = 30;

    private final int total, unseen;
    private final float strokePx, gapPx;
    private final Paint seenPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unseenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final boolean solidUnseen;

    public StatusSegmentedRingDrawable(int total, int unseen, float strokePx, float gapPx, int seenColor) {
        this(total, unseen, strokePx, gapPx, seenColor, 0);
    }

    /** unseenColor != 0 draws unseen arcs in that flat colour (e.g. close-friends green) instead of the story gradient. */
    public StatusSegmentedRingDrawable(int total, int unseen, float strokePx, float gapPx, int seenColor, int unseenColor) {
        this.solidUnseen = unseenColor != 0;
        this.total    = Math.max(1, total);
        this.unseen   = Math.max(0, Math.min(unseen, this.total));
        this.strokePx = strokePx;
        this.gapPx    = gapPx;
        seenPaint.setStyle(Paint.Style.STROKE);
        seenPaint.setStrokeWidth(strokePx);
        seenPaint.setColor(seenColor);
        unseenPaint.setStyle(Paint.Style.STROKE);
        unseenPaint.setStrokeWidth(strokePx);
        unseenPaint.setColor(solidUnseen ? unseenColor : 0xFFFF1493); // gradient fallback until the shared shader is ready
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        super.onBoundsChange(b);
        float half = strokePx / 2f;
        oval.set(half, half, b.width() - half, b.height() - half);
        if (!solidUnseen && b.width() > 0 && b.height() > 0) {
            try {
                SweepGradient sg = StoryRingShaderCache.get(b.width(), b.height());
                if (sg != null) unseenPaint.setShader(sg);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float radius = oval.width() / 2f;
        float step = 360f / total;
        float gapDeg = total == 1 ? 0f : (float) Math.toDegrees(gapPx / Math.max(1f, radius));
        float sweep = Math.max(1f, step - gapDeg);
        int seenCount = total - unseen;
        int save = canvas.save();
        canvas.translate(b.left, b.top);
        for (int i = 0; i < total; i++) {
            float start = -90f + i * step + gapDeg / 2f;
            canvas.drawArc(oval, start, sweep, false, i < seenCount ? seenPaint : unseenPaint);
        }
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int a) { seenPaint.setAlpha(a); unseenPaint.setAlpha(a); }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { seenPaint.setColorFilter(cf); unseenPaint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
