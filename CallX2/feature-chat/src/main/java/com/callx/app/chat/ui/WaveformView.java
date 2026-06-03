package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * WaveformView — Live audio waveform while recording voice messages.
 *
 * Add to layout: <com.callx.app.chat.ui.WaveformView ... />
 * Call addAmplitude(int) from a timer while MediaRecorder is active.
 * Call reset() when recording stops or is cancelled.
 */
public class WaveformView extends View {

    private static final int MAX_BARS    = 40;
    private static final int BAR_WIDTH   = 6;   // dp
    private static final int BAR_GAP     = 3;   // dp
    private static final int MIN_HEIGHT  = 4;   // dp
    private static final int ANIM_FRAMES = 3;

    private final Paint activePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Integer> amplitudes = new ArrayList<>();
    private float density;

    public WaveformView(Context context) {
        super(context); init(context);
    }
    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs); init(context);
    }
    public WaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init(context);
    }

    private void init(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;
        activePaint.setColor(0xFF6C63FF);   // brand purple
        activePaint.setStrokeCap(Paint.Cap.ROUND);
        inactivePaint.setColor(0x33000000);
        inactivePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Call this from a Handler while recording is active */
    public void addAmplitude(int maxAmplitude) {
        // Normalize 0–32767 to 0–100
        int normalized = Math.min(100, Math.max(0, maxAmplitude / 327));
        amplitudes.add(normalized);
        if (amplitudes.size() > MAX_BARS) amplitudes.remove(0);
        invalidate();
    }

    public void setActiveColor(int color) {
        activePaint.setColor(color);
        invalidate();
    }

    public void reset() {
        amplitudes.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float barW = BAR_WIDTH * density;
        float gap  = BAR_GAP   * density;
        float minH = MIN_HEIGHT * density;
        float totalBarW = barW + gap;

        int maxBars = (int) (w / totalBarW);

        // Draw from right to left (newest bar on right)
        int count = Math.min(amplitudes.size(), maxBars);
        for (int i = 0; i < count; i++) {
            int dataIdx = amplitudes.size() - count + i;
            int amp     = amplitudes.get(dataIdx);
            float barH  = Math.max(minH, (amp / 100f) * h * 0.85f);
            float x     = i * totalBarW + barW / 2;
            float top   = (h - barH) / 2f;
            float bot   = top + barH;
            canvas.drawLine(x, top, x, bot, activePaint);
        }
        // Fill remaining with inactive bars
        for (int i = count; i < maxBars; i++) {
            float x   = i * totalBarW + barW / 2;
            float barH = minH;
            float top  = (h - barH) / 2f;
            canvas.drawLine(x, top, x, top + barH, inactivePaint);
        }
    }
}
