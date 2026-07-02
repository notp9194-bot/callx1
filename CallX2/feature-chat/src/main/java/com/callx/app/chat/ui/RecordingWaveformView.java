package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;

/**
 * RecordingWaveformView — WhatsApp-style LIVE waveform for the capsule's
 * recording bar (Feature: voice waveform + swipe-to-cancel/lock).
 *
 * Unlike {@link AudioWaveformView} (which renders a fixed, seed-based shape
 * for a finished voice-note bubble), this view is fed REAL amplitude samples
 * while {@link com.callx.app.utils.VoiceRecorder} is running — one call to
 * {@link #pushLevel(float)} per poll tick (see ChatMediaController). New
 * bars enter from the right and the whole strip scrolls left, same feel as
 * WhatsApp/Telegram's recording waveform.
 *
 * Cheap by design: a fixed-capacity ring buffer (no allocation per frame)
 * and a single onDraw pass — no bitmap caching needed since the content
 * changes every tick anyway.
 */
public class RecordingWaveformView extends View {

    private static final float BAR_GAP_DP  = 3f;
    private static final float BAR_MIN_DP  = 2f;

    private final ArrayDeque<Float> levels = new ArrayDeque<>();
    private int capacity = 40; // recomputed from width in onSizeChanged

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float barWidthPx;
    private float gapPx;
    private float minBarHeightPx;

    public RecordingWaveformView(Context context) {
        super(context);
        init();
    }

    public RecordingWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RecordingWaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        gapPx = BAR_GAP_DP * density;
        minBarHeightPx = BAR_MIN_DP * density;
        barPaint.setColor(0xFF4CAF50); // brand_primary — overridden via setBarColor() if needed
        setMinimumHeight((int) (28 * density));
    }

    /** Optional — lets callers match theme colors instead of the hardcoded default. */
    public void setBarColor(int color) {
        barPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0) return;
        // ~3.5dp per bar (bar + gap) feels close to WhatsApp's density.
        float slot = 3.5f * getResources().getDisplayMetrics().density;
        capacity = Math.max(8, (int) (w / slot));
        barWidthPx = Math.max(1.5f, slot - gapPx);
        while (levels.size() > capacity) levels.pollFirst();
    }

    /**
     * Push a new normalized amplitude sample (0f..1f). Called once per
     * poll tick (~100ms) while recording. Oldest bar drops off the left
     * once the buffer is full — bars appear to scroll leftward.
     */
    public void pushLevel(float level) {
        float clamped = Math.max(0f, Math.min(1f, level));
        levels.addLast(clamped);
        while (levels.size() > capacity) levels.pollFirst();
        invalidate();
    }

    /** Clears all bars back to empty (called when a new recording starts). */
    public void reset() {
        levels.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (levels.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;

        float centerY = getHeight() / 2f;
        float slot = barWidthPx + gapPx;
        // Right-align the strip so the newest bar always sits at the right
        // edge, same as WhatsApp — older bars trail off to the left.
        float x = getWidth() - barWidthPx;
        float radius = barWidthPx / 2f;

        java.util.Iterator<Float> it = levels.descendingIterator();
        while (it.hasNext() && x >= -barWidthPx) {
            float lvl = it.next();
            float barHeight = Math.max(minBarHeightPx, lvl * getHeight());
            canvas.drawRoundRect(
                    x, centerY - barHeight / 2f,
                    x + barWidthPx, centerY + barHeight / 2f,
                    radius, radius, barPaint);
            x -= slot;
        }
    }
}
