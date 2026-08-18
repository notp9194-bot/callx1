package com.callx.app.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Random;

/**
 * AudioTrimWaveformView — the pink→purple, dual-handle draggable waveform
 * trimmer shown in ReelPhotoMusicTrimActivity ("Adjust Music").
 *
 * Mirrors the look of the reference design: a bar-style waveform where the
 * selected range is colored with a pink→purple gradient and everything
 * outside it is dimmed, two round "+" handles bracketing the selection
 * (connected by a thin vertical guide line spanning the full height), a
 * floating time-tooltip above whichever handle is being dragged, and an
 * optional moving playhead line synced to audio preview playback.
 *
 * All colors are supplied from outside via {@link #setPalette} so the
 * caller can resolve them from day/night-aware color resources
 * (ContextCompat.getColor picks the correct values/ vs values-night/
 * variant automatically) rather than this view hardcoding hex values —
 * that's what lets the whole trim screen follow the device's light/dark
 * theme instead of always rendering dark.
 *
 * Usage (see ReelPhotoMusicTrimActivity):
 *   waveformView.setPalette(dim, start, end, playhead, tooltipBg, tooltipText);
 *   waveformView.setDurationMs(totalMs);
 *   waveformView.generateAmplitudes(soundId); // once, after duration is known
 *   waveformView.setRangeMs(startMs, endMs);  // whenever range changes programmatically
 *   waveformView.setOnRangeChangeListener(listener);
 *   waveformView.setPlayheadMs(mediaPlayer.getCurrentPosition()); // while previewing, or -1 to hide
 */
public class AudioTrimWaveformView extends View {

    public interface OnRangeChangeListener {
        /** Fired continuously while a handle is being dragged. */
        void onRangeChanging(int startMs, int endMs, boolean isStartHandle);
        /** Fired once when the user lifts their finger off a handle. */
        void onRangeChangeFinished(int startMs, int endMs);
    }

    private static final int HANDLE_NONE  = 0;
    private static final int HANDLE_START = 1;
    private static final int HANDLE_END   = 2;

    private final float density = getResources().getDisplayMetrics().density;
    private final float handleRadiusPx   = 11 * density;
    private final float handleTouchSlopPx = 22 * density;
    private final float guideLineWidthPx = 2 * density;
    private final float barWidthPx = 3 * density;
    private final float barGapPx   = 2.4f * density;
    private final float tooltipPadH = 8 * density;
    private final float tooltipPadV = 5 * density;
    private final float tooltipTextSizePx = 11 * density;

    private final Paint barPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePlusPaint= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tooltipRect    = new RectF();

    private int dimColor      = 0xFFD6D6DE;
    private int startColor    = 0xFFFF3B5C;
    private int endColor      = 0xFFA855F7;
    private int playheadColor = 0xFFFFFFFF;
    private int tooltipBg     = 0xE6141420;
    private int tooltipText   = 0xFFFFFFFF;

    private int durationMs = 0;
    private int startMs    = 0;
    private int endMs      = 0;
    private int playheadMs = -1; // -1 == hidden (not previewing)

    private float[] amplitudes = new float[0];
    private int activeHandle = HANDLE_NONE;
    private float touchDownDx = 0f;

    private OnRangeChangeListener listener;

    public AudioTrimWaveformView(Context context) { this(context, null); }

    public AudioTrimWaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint.setStyle(Paint.Style.FILL);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePlusPaint.setColor(Color.WHITE);
        handlePlusPaint.setStrokeWidth(2 * density);
        handlePlusPaint.setStrokeCap(Paint.Cap.ROUND);
        guidePaint.setStyle(Paint.Style.FILL);
        playheadPaint.setStyle(Paint.Style.FILL);
        tooltipBgPaint.setStyle(Paint.Style.FILL);
        tooltipTextPaint.setColor(tooltipText);
        tooltipTextPaint.setTextSize(tooltipTextSizePx);
        tooltipTextPaint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Colors resolved by the caller from day/night-aware color resources. */
    public void setPalette(int dimColor, int startColor, int endColor,
                            int playheadColor, int tooltipBg, int tooltipText) {
        this.dimColor = dimColor;
        this.startColor = startColor;
        this.endColor = endColor;
        this.playheadColor = playheadColor;
        this.tooltipBg = tooltipBg;
        this.tooltipText = tooltipText;
        tooltipTextPaint.setColor(tooltipText);
        invalidate();
    }

    public void setOnRangeChangeListener(OnRangeChangeListener l) { this.listener = l; }

    public void setDurationMs(int ms) {
        this.durationMs = Math.max(1, ms);
        invalidate();
    }

    /** Programmatic range update (presets, seekbar sync) — does not fire the listener. */
    public void setRangeMs(int startMs, int endMs) {
        this.startMs = Math.max(0, startMs);
        this.endMs   = Math.min(durationMs, endMs);
        invalidate();
    }

    public int getStartMs() { return startMs; }
    public int getEndMs()   { return endMs; }

    /** Pass -1 to hide the playhead (not currently previewing). */
    public void setPlayheadMs(int ms) {
        this.playheadMs = ms;
        invalidate();
    }

    /**
     * Generates a stable (non-reshuffling) set of bar amplitudes, seeded off
     * the track id so the same track always draws the same-looking waveform
     * across screen opens instead of a new random pattern every time.
     */
    public void generateAmplitudes(@Nullable String seedKey) {
        int w = getWidth();
        int count = w > 0 ? Math.max(24, (int) (w / (barWidthPx + barGapPx))) : 70;
        amplitudes = new float[count];
        Random rng = new Random(seedKey != null ? seedKey.hashCode() : 42L);
        float phase = rng.nextFloat() * 6.28f;
        for (int i = 0; i < count; i++) {
            // Smooth-ish undulation + noise so it reads as a real waveform, not pure static.
            double wave = Math.sin(i * 0.35 + phase) * 0.5 + 0.5;
            float noise = rng.nextFloat();
            amplitudes[i] = 0.18f + 0.82f * (float) (0.55 * wave + 0.45 * noise);
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (amplitudes.length == 0 && w > 0) generateAmplitudes(null);
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || amplitudes.length == 0 || durationMs <= 0) return;

        float startX = xForMs(startMs);
        float endX   = xForMs(endMs);
        float midY   = h / 2f;

        // Waveform bars: dim outside the selection, pink→purple gradient inside it.
        float step = (float) w / amplitudes.length;
        for (int i = 0; i < amplitudes.length; i++) {
            float cx = i * step + step / 2f;
            float barH = amplitudes[i] * (h * 0.72f);
            float top = midY - barH / 2f;
            float bottom = midY + barH / 2f;

            int color;
            if (cx < startX || cx > endX) {
                color = dimColor;
            } else {
                float frac = (endX > startX) ? (cx - startX) / (endX - startX) : 0f;
                color = lerpColor(startColor, endColor, frac);
            }
            barPaint.setColor(color);
            canvas.drawRoundRect(cx - barWidthPx / 2f, top, cx + barWidthPx / 2f, bottom,
                    barWidthPx / 2f, barWidthPx / 2f, barPaint);
        }

        // Vertical guide lines + handles at the selection edges.
        drawHandle(canvas, startX, h, startColor, true);
        drawHandle(canvas, endX, h, endColor, false);

        // Playhead (only meaningful while previewing, and only inside the selection).
        if (playheadMs >= 0) {
            float px = xForMs(Math.max(startMs, Math.min(endMs, playheadMs)));
            playheadPaint.setColor(playheadColor);
            canvas.drawRoundRect(px - 1.2f * density, 2 * density, px + 1.2f * density, h - 2 * density,
                    1.2f * density, 1.2f * density, playheadPaint);
        }

        // Time tooltip above the handle currently being dragged.
        if (activeHandle != HANDLE_NONE) {
            float handleX = (activeHandle == HANDLE_START) ? startX : endX;
            int ms = (activeHandle == HANDLE_START) ? startMs : endMs;
            drawTooltip(canvas, handleX, msToTooltipTime(ms));
        }
    }

    private void drawHandle(Canvas canvas, float x, int h, int color, boolean isStart) {
        guidePaint.setColor(color);
        canvas.drawRoundRect(x - guideLineWidthPx / 2f, 0, x + guideLineWidthPx / 2f, h,
                guideLineWidthPx / 2f, guideLineWidthPx / 2f, guidePaint);

        float cy = h / 2f;
        handlePaint.setColor(color);
        canvas.drawCircle(x, cy, handleRadiusPx, handlePaint);
        // "+" glyph so the handle reads as grabbable, matching the reference design.
        float armLen = handleRadiusPx * 0.5f;
        canvas.drawLine(x - armLen, cy, x + armLen, cy, handlePlusPaint);
        canvas.drawLine(x, cy - armLen, x, cy + armLen, handlePlusPaint);
    }

    private void drawTooltip(Canvas canvas, float anchorX, String text) {
        float textW = tooltipTextPaint.measureText(text);
        float boxW = textW + tooltipPadH * 2;
        float boxH = tooltipTextSizePx + tooltipPadV * 2;
        float top = -boxH - 10 * density;
        float bottom = top + boxH;
        float left = anchorX - boxW / 2f;
        float right = anchorX + boxW / 2f;
        // Keep the tooltip within the view's horizontal bounds.
        if (left < 0) { right -= left; left = 0; }
        if (right > getWidth()) { left -= (right - getWidth()); right = getWidth(); }

        tooltipRect.set(left, top, right, bottom);
        tooltipBgPaint.setColor(tooltipBg);
        canvas.drawRoundRect(tooltipRect, 8 * density, 8 * density, tooltipBgPaint);
        canvas.drawText(text, (left + right) / 2f, bottom - tooltipPadV - (tooltipTextSizePx * 0.15f), tooltipTextPaint);
    }

    private static String msToTooltipTime(int ms) {
        int totalTenths = ms / 100;
        int sec = totalTenths / 10;
        int tenth = totalTenths % 10;
        return String.format(Locale.US, "%d:%02d.%d", sec / 60, sec % 60, tenth);
    }

    private static int lerpColor(int c1, int c2, float frac) {
        frac = Math.max(0f, Math.min(1f, frac));
        int a = (int) (Color.alpha(c1) + frac * (Color.alpha(c2) - Color.alpha(c1)));
        int r = (int) (Color.red(c1)   + frac * (Color.red(c2)   - Color.red(c1)));
        int g = (int) (Color.green(c1) + frac * (Color.green(c2) - Color.green(c1)));
        int b = (int) (Color.blue(c1)  + frac * (Color.blue(c2)  - Color.blue(c1)));
        return Color.argb(a, r, g, b);
    }

    /**
     * Horizontal inset reserved on both edges so the round "+" handles
     * (radius handleRadiusPx) always draw fully inside the view's bounds
     * instead of getting clipped in half when a handle sits at ms=0 or
     * ms=durationMs. Without this, the left handle's "+" circle used to
     * stick half-off the left edge (and the right one off the right edge).
     */
    private float edgeInsetPx() { return handleRadiusPx; }

    private float xForMs(int ms) {
        if (durationMs <= 0) return 0;
        float inset = edgeInsetPx();
        float usableW = Math.max(0f, getWidth() - inset * 2f);
        float ratio = (float) ms / (float) durationMs;
        ratio = Math.max(0f, Math.min(1f, ratio));
        return inset + ratio * usableW;
    }

    private int msForX(float x) {
        float inset = edgeInsetPx();
        float usableW = Math.max(0f, getWidth() - inset * 2f);
        if (usableW <= 0) return 0;
        float ratio = Math.max(0f, Math.min(1f, (x - inset) / usableW));
        return Math.round(ratio * durationMs);
    }

    // ── Touch handling ──────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float startX = xForMs(startMs);
        float endX   = xForMs(endMs);
        int minGapMs = 1000;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float dStart = Math.abs(x - startX);
                float dEnd   = Math.abs(x - endX);
                if (dStart <= handleTouchSlopPx && dStart <= dEnd) {
                    activeHandle = HANDLE_START;
                    touchDownDx = x - startX;
                } else if (dEnd <= handleTouchSlopPx) {
                    activeHandle = HANDLE_END;
                    touchDownDx = x - endX;
                } else {
                    activeHandle = HANDLE_NONE;
                    return false;
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activeHandle == HANDLE_NONE) return false;
                float adjX = x - touchDownDx;
                int candidate = msForX(adjX);
                if (activeHandle == HANDLE_START) {
                    candidate = Math.min(candidate, endMs - minGapMs);
                    candidate = Math.max(candidate, 0);
                    startMs = candidate;
                } else {
                    candidate = Math.max(candidate, startMs + minGapMs);
                    candidate = Math.min(candidate, durationMs);
                    endMs = candidate;
                }
                invalidate();
                if (listener != null) listener.onRangeChanging(startMs, endMs, activeHandle == HANDLE_START);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean wasActive = activeHandle != HANDLE_NONE;
                activeHandle = HANDLE_NONE;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                if (wasActive && listener != null) listener.onRangeChangeFinished(startMs, endMs);
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }
}
