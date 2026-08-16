package com.callx.app.conversation.controllers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Vertical brush-width slider shaped like a teardrop / guitar pick — wide
 * rounded cap at the top, tapering to a fine point at the bottom (screenshot
 * 2). Drag up for a thicker brush, down for a thinner one. Overlaid on the
 * right edge of the media canvas while draw mode is active, mirroring the
 * Instagram/Snapchat-style markup width control.
 *
 * Reports a brush width directly in dp (2..44dp, matching the previous
 * SeekBar's range) so callers can plug it straight into
 * {@link DrawOverlayView#setActiveWidthDp(float)} with no extra mapping.
 */
public class TeardropWidthSlider extends View {

    public interface OnWidthChangeListener {
        void onWidthChanged(float widthDp);
    }

    private static final float MIN_WIDTH_DP = 2f;
    private static final float MAX_WIDTH_DP = 44f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  trackPath  = new Path();

    private float progress = (8f - MIN_WIDTH_DP) / (MAX_WIDTH_DP - MIN_WIDTH_DP); // 0..1, default ≈ 8dp
    private int   thumbColor = 0xFFFFFFFF;
    private OnWidthChangeListener listener;

    public TeardropWidthSlider(Context ctx) { super(ctx); init(); }
    public TeardropWidthSlider(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public TeardropWidthSlider(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        setClickable(true);
        setFocusable(true);
        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(0xB3222222);
        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setShadowLayer(dp(3), 0, dp(1), 0x66000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // shadow layer needs software rendering
    }

    public void setOnWidthChangeListener(OnWidthChangeListener l) {
        this.listener = l;
    }

    /** Sets the current width in dp without firing the listener. */
    public void setWidthDp(float widthDp) {
        float clamped = Math.max(MIN_WIDTH_DP, Math.min(MAX_WIDTH_DP, widthDp));
        this.progress = (clamped - MIN_WIDTH_DP) / (MAX_WIDTH_DP - MIN_WIDTH_DP);
        invalidate();
    }

    public float getWidthDp() {
        return MIN_WIDTH_DP + progress * (MAX_WIDTH_DP - MIN_WIDTH_DP);
    }

    /** Tints the thumb + tapers the track fill to reflect the active draw color. */
    public void setAccentColor(int color) {
        this.thumbColor = color;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        rebuildTrackPath();
    }

    private float capRadius() {
        return getWidth() * 0.42f;
    }

    private void rebuildTrackPath() {
        trackPath.reset();
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float capR = capRadius();
        float tipMargin = dp(6); // small flat tip instead of a mathematically perfect point

        RectF capOval = new RectF(cx - capR, 0, cx + capR, capR * 2f);

        trackPath.moveTo(cx - capR, capR);
        trackPath.arcTo(capOval, 180, 180, false); // rounded top → ends at (cx+capR, capR)
        trackPath.lineTo(cx + dp(1.5f), h - tipMargin);
        trackPath.lineTo(cx - dp(1.5f), h - tipMargin);
        trackPath.lineTo(cx - capR, capR);
        trackPath.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawPath(trackPath, trackPaint);

        float capR = capRadius();
        float bottom = h - dp(6);
        float thumbY = capR + (1f - progress) * (bottom - capR);
        float thumbR = dp(9);

        thumbPaint.setColor(thumbColor);
        canvas.drawCircle(w / 2f, thumbY, thumbR, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float h = getHeight();
        if (h <= 0) return false;
        float capR = capRadius();
        float bottom = h - dp(6);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                float y = Math.max(capR, Math.min(bottom, event.getY()));
                progress = 1f - (y - capR) / Math.max(1f, (bottom - capR));
                invalidate();
                if (listener != null) listener.onWidthChanged(getWidthDp());
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return false;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
