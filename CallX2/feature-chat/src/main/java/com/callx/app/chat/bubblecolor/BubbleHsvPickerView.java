package com.callx.app.chat.bubblecolor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * Custom colour picker: saturation/value square + hue strip, one canvas view.
 * The hue strip shader is built once per size; the SV shader only when the hue moves.
 */
final class BubbleHsvPickerView extends View {

    interface OnColor { void onColor(int argb); }

    private final Paint svPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint huePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sv = new RectF(), hueRect = new RectF();
    private final float[] hsv = {270f, 0.8f, 0.85f};
    private final float[] tmp = {0f, 1f, 1f};   // reused scratch (onDraw stays allocation-free)
    private final float d, knobR, radius;
    private OnColor listener;
    private int drag = 0; // 1 = sv, 2 = hue

    BubbleHsvPickerView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        knobR = 11f * d;
        radius = 14f * d;
        knobRing.setStyle(Paint.Style.STROKE);
        knobRing.setStrokeWidth(3f * d);
        knobRing.setColor(0xFFFFFFFF);
    }

    void setOnColor(OnColor l) { listener = l; }

    void setColor(int argb) {
        Color.colorToHSV(argb, hsv);
        rebuildSv();
        invalidate();
    }

    int getColor() { return Color.HSVToColor(hsv); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        final int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(w * 0.62f + 16f * d + 30f * d));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        final float side = 4f * d;
        sv.set(side, side, w - side, side + (w - side * 2f) * 0.62f);
        hueRect.set(side, sv.bottom + 16f * d, w - side, sv.bottom + 16f * d + 28f * d);
        final int[] cols = new int[7];
        for (int i = 0; i < 7; i++) cols[i] = Color.HSVToColor(new float[]{i * 60f, 1f, 1f});
        huePaint.setShader(new LinearGradient(hueRect.left, 0f, hueRect.right, 0f, cols, null, Shader.TileMode.CLAMP));
        rebuildSv();
    }

    private void rebuildSv() {
        if (sv.width() <= 0f) return;
        tmp[0] = hsv[0];
        final int pure = Color.HSVToColor(tmp);
        final Shader horiz = new LinearGradient(sv.left, 0f, sv.right, 0f, 0xFFFFFFFF, pure, Shader.TileMode.CLAMP);
        final Shader vert = new LinearGradient(0f, sv.top, 0f, sv.bottom, 0xFFFFFFFF, 0xFF000000, Shader.TileMode.CLAMP);
        svPaint.setShader(new ComposeShader(horiz, vert, PorterDuff.Mode.MULTIPLY));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRoundRect(sv, radius, radius, svPaint);
        c.drawRoundRect(hueRect, hueRect.height() / 2f, hueRect.height() / 2f, huePaint);
        // SV knob
        final float kx = sv.left + hsv[1] * sv.width();
        final float ky = sv.top + (1f - hsv[2]) * sv.height();
        knobFill.setColor(Color.HSVToColor(hsv));
        c.drawCircle(kx, ky, knobR, knobFill);
        c.drawCircle(kx, ky, knobR, knobRing);
        // hue knob
        final float hx = hueRect.left + hsv[0] / 360f * hueRect.width();
        tmp[0] = hsv[0];
        knobFill.setColor(Color.HSVToColor(tmp));
        c.drawCircle(hx, hueRect.centerY(), knobR, knobFill);
        c.drawCircle(hx, hueRect.centerY(), knobR, knobRing);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                drag = hueRect.contains(x, y + 12f * d) && y > sv.bottom ? 2 : (sv.contains(x, y) ? 1 : 0);
                if (drag == 0) return false;
                update(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drag != 0) update(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drag = 0;
                return true;
            default:
                return true;
        }
    }

    private void update(float x, float y) {
        if (drag == 2) {
            hsv[0] = clamp((x - hueRect.left) / hueRect.width()) * 360f;
            if (hsv[0] >= 360f) hsv[0] = 359.9f;
            rebuildSv();
        } else {
            hsv[1] = clamp((x - sv.left) / sv.width());
            hsv[2] = 1f - clamp((y - sv.top) / sv.height());
        }
        invalidate();
        if (listener != null) listener.onColor(Color.HSVToColor(hsv));
    }

    private static float clamp(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
