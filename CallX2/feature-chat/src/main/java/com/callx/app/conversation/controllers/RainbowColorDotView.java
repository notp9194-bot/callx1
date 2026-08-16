package com.callx.app.conversation.controllers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/**
 * The leftmost button in the draw tools row (screenshot 1) — a small circle
 * with a full rainbow ring around it and the currently selected draw color
 * filled in the center. Tapping it is wired up by MediaEditActivity to open
 * {@link com.callx.app.utils.RainbowStripColorPickerBottomSheet} (the
 * shared "core" color sheet), matching every other color pick surface in
 * the app.
 */
public class RainbowColorDotView extends View {

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect  = new RectF();
    private int currentColor = Color.WHITE;

    public RainbowColorDotView(Context ctx) { super(ctx); init(); }
    public RainbowColorDotView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public RainbowColorDotView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        setClickable(true);
        setFocusable(true);
        ringPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    /** Updates the center swatch to reflect the active draw color. */
    public void setCurrentColor(int color) {
        this.currentColor = color;
        invalidate();
    }

    public int getCurrentColor() {
        return currentColor;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w <= 0 || h <= 0) return;

        float strokeW = w * 0.16f;
        ringPaint.setStrokeWidth(strokeW);
        ringRect.set(strokeW / 2f, strokeW / 2f, w - strokeW / 2f, h - strokeW / 2f);

        // Full-spectrum rainbow — matches the "point anywhere" picker sheet.
        int[] hues = new int[13];
        for (int i = 0; i < hues.length; i++) {
            hues[i] = Color.HSVToColor(new float[]{(i * 360f) / (hues.length - 1), 1f, 1f});
        }
        ringPaint.setShader(new SweepGradient(w / 2f, h / 2f, hues, null));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (ringRect.width() <= 0 || ringRect.height() <= 0) return;

        canvas.drawOval(ringRect, ringPaint);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float fillR = (getWidth() / 2f) - ringPaint.getStrokeWidth() - dp(2.5f);
        fillPaint.setColor(currentColor);
        canvas.drawCircle(cx, cy, Math.max(fillR, dp(4)), fillPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
