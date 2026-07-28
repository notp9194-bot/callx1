package com.callx.app.highlights;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.Nullable;

/**
 * RainbowColorPickerView — "poora box rainbow hota hai, jahan point karoge
 * wahi color apply hoga": the whole box is a rainbow. Hue sweeps left→right
 * (0°..360°) and every column fades white (top) → pure hue (middle) → black
 * (bottom) — the classic saturation/value color square. Tap or drag anywhere
 * and that exact point's color is picked, analytically (no bitmap pixel
 * read needed, so it works the same on hardware-accelerated canvases).
 */
public class RainbowColorPickerView extends View {

    public interface OnColorPickListener { void onColorPicked(int color); }

    private final Paint huePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whiteOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blackOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF boxRect = new RectF();

    private OnColorPickListener listener;
    private float markerX = -1f, markerY = -1f;
    private int lastColor = Color.RED;

    public RainbowColorPickerView(Context ctx) { super(ctx); init(); }
    public RainbowColorPickerView(Context ctx, @Nullable AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        markerRingPaint.setStyle(Paint.Style.STROKE);
        markerRingPaint.setStrokeWidth(dp(2.5f));
        markerRingPaint.setColor(Color.WHITE);
        setWillNotDraw(false);
    }

    public void setOnColorPickListener(OnColorPickListener l) { this.listener = l; }

    /** Places the marker at the box position matching {@code color} (best
     *  effort — used just to show where an already-picked color sits when
     *  the sheet opens) without firing the listener. */
    public void setInitialColor(int color) {
        lastColor = color;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        int w = getWidth(), h = getHeight();
        if (w > 0 && h > 0) {
            markerX = (hsv[0] / 360f) * w;
            float v = hsv[2], s = hsv[1];
            float fy = v >= 0.999f ? (1f - s) * 0.5f : 0.5f + (1f - v) * 0.5f;
            markerY = fy * h;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        boxRect.set(0, 0, w, h);
        buildShaders(w, h);
        if (markerX < 0) setInitialColor(lastColor);
    }

    private void buildShaders(int w, int h) {
        if (w <= 0 || h <= 0) return;
        int[] hueColors = new int[13];
        for (int i = 0; i < hueColors.length; i++) {
            hueColors[i] = Color.HSVToColor(new float[]{ (i * 30f) % 360f, 1f, 1f });
        }
        huePaint.setShader(new LinearGradient(0, 0, w, 0, hueColors, null, Shader.TileMode.CLAMP));
        whiteOverlayPaint.setShader(new LinearGradient(0, 0, 0, h / 2f,
                Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        blackOverlayPaint.setShader(new LinearGradient(0, h / 2f, 0, h,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(boxRect, huePaint);
        canvas.drawRect(boxRect, whiteOverlayPaint);
        canvas.drawRect(boxRect, blackOverlayPaint);
        if (markerX >= 0) {
            markerFillPaint.setColor(lastColor);
            canvas.drawCircle(markerX, markerY, dp(9f), markerRingPaint);
            canvas.drawCircle(markerX, markerY, dp(6f), markerFillPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                float x = clamp(event.getX(), 0, getWidth());
                float y = clamp(event.getY(), 0, getHeight());
                markerX = x;
                markerY = y;
                lastColor = colorAt(x, y);
                invalidate();
                if (listener != null) listener.onColorPicked(lastColor);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            default:
                return true;
        }
    }

    private int colorAt(float x, float y) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return lastColor;
        float hue = clamp(x, 0, w) / w * 360f;
        int hueColor = Color.HSVToColor(new float[]{ hue % 360f, 1f, 1f });
        float fy = clamp(y, 0, h) / h;
        if (fy <= 0.5f) {
            // top(white) -> middle(pure hue)
            return lerpColor(Color.WHITE, hueColor, fy / 0.5f);
        } else {
            // middle(pure hue) -> bottom(black)
            return lerpColor(hueColor, Color.BLACK, (fy - 0.5f) / 0.5f);
        }
    }

    private static int lerpColor(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
