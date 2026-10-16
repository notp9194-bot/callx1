package com.callx.app.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * Instagram-style clip picker: the song's waveform scrolls horizontally UNDER a fixed, centred
 * gradient window whose width equals the selected clip length. Whatever is inside the window is
 * the clip. Same public surface as {@link AudioTrimWaveformView} (range / duration / playhead /
 * amplitudes / listener) so screens can swap one for the other.
 */
public class ClipWindowWaveformView extends View {

    public interface OnRangeChangeListener {
        /** Fired continuously while the waveform is dragged / flung. */
        void onRangeChanging(int startMs, int endMs, boolean isStartHandle);
        /** Fired once when the user lifts the finger and any fling has settled. */
        void onRangeChangeFinished(int startMs, int endMs);
    }

    /** The window covers this fraction of the view width. */
    private static final float WINDOW_FRACTION = 0.34f;

    private final float density = getResources().getDisplayMetrics().density;
    private final float barW   = 3f * density;
    private final float barGap = 3f * density;
    private final float winRadius = 12f * density;
    private final float winPad = 4f * density;

    private final Paint tickPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint winPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int durationMs = 0;
    private int startMs = 0;
    private int clipMs = 15_000;
    private int playheadMs = -1;

    private float[] raw = null;          // real loudness (0..1), whole track
    private float[] placeholder = null;  // generated, whole track
    private Shader winShader;
    private int shaderW = -1;

    private OnRangeChangeListener listener;

    // touch
    private float lastX;
    private boolean dragging;
    private boolean limitTicked;
    private VelocityTracker vt;
    private ValueAnimator fling;
    private final int minFling = ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity();
    private final int maxFling = ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity();

    public ClipWindowWaveformView(Context c) { this(c, null); }
    public ClipWindowWaveformView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        tickPaint.setColor(0x99FFFFFF);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setStrokeWidth(2f * density);
        barPaint.setColor(0xFFFFFFFF);
        barPaint.setStrokeCap(Paint.Cap.ROUND);
        barPaint.setStrokeWidth(barW);
        playPaint.setColor(0xFFFF2D6F);
        playPaint.setStrokeCap(Paint.Cap.ROUND);
        playPaint.setStrokeWidth(3f * density);
        setClickable(true);
    }

    // ── API (mirrors AudioTrimWaveformView) ─────────────────────────────

    public void setOnRangeChangeListener(OnRangeChangeListener l) { listener = l; }

    public void setDurationMs(int ms) { durationMs = Math.max(1, ms); clampStart(); invalidate(); }

    /** Programmatic range: window length = end - start. Does not fire the listener. */
    public void setRangeMs(int s, int e) {
        int len = Math.max(1000, e - s);
        clipMs = durationMs > 0 ? Math.min(len, durationMs) : len;
        startMs = Math.max(0, s);
        clampStart();
        invalidate();
    }

    public int getStartMs() { return startMs; }
    public int getEndMs()   { return Math.min(durationMs > 0 ? durationMs : startMs + clipMs, startMs + clipMs); }

    public void setPlayheadMs(int ms) { playheadMs = ms; invalidate(); }

    public void generateAmplitudes(@Nullable String seedKey) {
        raw = null;
        int n = 480;
        placeholder = new float[n];
        Random rng = new Random(seedKey != null ? seedKey.hashCode() : 42L);
        float phase = rng.nextFloat() * 6.28f;
        for (int i = 0; i < n; i++) {
            double wave = Math.sin(i * 0.11 + phase) * 0.5 + 0.5;
            placeholder[i] = 0.18f + 0.82f * (float) (0.55 * wave + 0.45 * rng.nextFloat());
        }
        invalidate();
    }

    public void setRealAmplitudes(@Nullable float[] r) {
        if (r == null || r.length == 0) return;
        raw = r;
        invalidate();
    }

    // ── geometry ────────────────────────────────────────────────────────

    private float windowW() { return getWidth() * WINDOW_FRACTION; }
    private float pxPerMs() { return clipMs > 0 ? windowW() / clipMs : 0.01f; }
    private float winLeft() { return (getWidth() - windowW()) / 2f; }

    private void clampStart() {
        int max = Math.max(0, durationMs - clipMs);
        if (startMs > max) startMs = max;
        if (startMs < 0) startMs = 0;
    }

    private float amp(float tMs) {
        float[] a = raw != null ? raw : placeholder;
        if (a == null || a.length == 0 || durationMs <= 0) return 0.3f;
        float f = Math.max(0f, Math.min(0.9999f, tMs / durationMs)) * a.length;
        int i = (int) f;
        return a[i];
    }

    // ── drawing ─────────────────────────────────────────────────────────

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || durationMs <= 0) return;
        float ppm = pxPerMs();
        float wl = winLeft(), wr = wl + windowW();
        float midY = h / 2f;

        // Outside the window: small ticks that scroll with the track.
        float step = barW + barGap;
        float firstT = startMs - wl / ppm;                 // time at x = 0
        float stepMs = step / ppm;
        long k0 = (long) Math.floor(firstT / stepMs);
        for (long k = k0; ; k++) {
            float t = k * stepMs;
            float x = wl + (t - startMs) * ppm;
            if (x > w + step) break;
            if (t < 0 || t > durationMs) continue;
            if (x >= wl - 1 && x <= wr + 1) continue;      // window draws its own bars
            float a = amp(t);
            float tall = h * (0.22f + 0.30f * a);
            c.drawLine(x, midY - tall / 2f, x, midY + tall / 2f, tickPaint);
        }

        // Window: gradient rounded rect (orange → pink → purple) + white bars of the clip.
        if (winShader == null || shaderW != w) {
            winShader = new LinearGradient(wl, 0, wr, 0,
                    new int[]{0xFFFFC300, 0xFFFF2D6F, 0xFFB030F0, 0xFF6A3DF5}, null, Shader.TileMode.CLAMP);
            shaderW = w;
        }
        winPaint.setShader(winShader);
        rect.set(wl - winPad, 2f * density, wr + winPad, h - 2f * density);
        c.drawRoundRect(rect, winRadius, winRadius, winPaint);

        float inL = wl, inR = wr;
        for (float x = inL + barW; x < inR - barW; x += step) {
            float t = startMs + (x - wl) / ppm;
            float a = amp(t);
            float bh = Math.max(4f * density, a * (h - 18f * density));
            c.drawLine(x, midY - bh / 2f, x, midY + bh / 2f, barPaint);
        }

        // Playhead inside the window while previewing.
        if (playheadMs >= startMs && playheadMs <= startMs + clipMs) {
            float x = wl + (playheadMs - startMs) * ppm;
            c.drawLine(x, 5f * density, x, h - 5f * density, playPaint);
        }
    }

    // ── touch ───────────────────────────────────────────────────────────

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (durationMs <= 0 || !isEnabled()) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (fling != null) fling.cancel();
                getParent().requestDisallowInterceptTouchEvent(true);
                vt = VelocityTracker.obtain(); vt.addMovement(e);
                lastX = e.getX(); dragging = true; limitTicked = false;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                vt.addMovement(e);
                float dx = e.getX() - lastX; lastX = e.getX();
                moveBy(-dx / pxPerMs());          // content follows the finger
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                float vx = 0f;
                if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000, maxFling); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(vx) > minFling * 4) startFling(-vx / pxPerMs());
                else finish();
                return true;
        }
        return true;
    }

    private void moveBy(float dMs) {
        int before = startMs;
        float ns = startMs + dMs;
        int max = Math.max(0, durationMs - clipMs);
        boolean hitLimit = ns <= 0 || ns >= max;
        startMs = (int) Math.max(0, Math.min(max, ns));
        if (hitLimit && !limitTicked) { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); limitTicked = true; }
        else if (!hitLimit) limitTicked = false;
        if (startMs != before) {
            invalidate();
            if (listener != null) listener.onRangeChanging(startMs, getEndMs(), true);
        }
    }

    private void startFling(float velMsPerSec) {
        final float v0 = velMsPerSec;
        final float dur = 450f;
        final float[] last = {0f};
        fling = ValueAnimator.ofFloat(0f, 1f);
        fling.setDuration((long) dur);
        fling.setInterpolator(new DecelerateInterpolator(2f));
        fling.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float travelled = v0 * (dur / 1000f) * 0.35f * f;
            moveBy(travelled - last[0]);
            last[0] = travelled;
        });
        fling.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled) finish(); }
        });
        fling.start();
    }

    private void finish() {
        if (listener != null) listener.onRangeChangeFinished(startMs, getEndMs());
    }

    @Override protected void onDetachedFromWindow() {
        if (fling != null) fling.cancel();
        super.onDetachedFromWindow();
    }
}
