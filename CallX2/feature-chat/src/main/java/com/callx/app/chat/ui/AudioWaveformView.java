package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * AudioWaveformView — zero-measure-pass waveform renderer.
 *
 * Design:
 *   • Replaces SeekBar for voice messages — waveform bars look like WhatsApp/Telegram.
 *   • Pre-renders into a Bitmap at setWaveform() time — zero per-frame measure passes.
 *   • onDraw() just canvas.drawBitmap() → single GPU blit per frame.
 *   • Progress is rendered by re-drawing only the right half in dim color (no second bitmap).
 *   • Touch → seeks to tapped fraction; setProgress(float) from MediaPlayer tick.
 *
 * Performance:
 *   • setWaveform() runs once when audio stub is inflated — O(N) bar draw into Bitmap.
 *   • onDraw() is O(1): drawBitmap + clip-rect progress line. Zero Paths/loops per frame.
 *   • MATCH_PARENT width + fixed 48dp height — one measure pass on inflation, never again.
 */
public class AudioWaveformView extends View {

    public interface OnSeekListener {
        void onSeek(float progress); // 0.0 – 1.0
    }

    // ── Bar config ─────────────────────────────────────────────────────────
    private static final int   BAR_COUNT  = 50;
    private static final float BAR_GAP_DP = 2f;
    private static final float BAR_MIN_DP = 4f;

    // ── State ──────────────────────────────────────────────────────────────
    private float[]  amplitudes;          // 0..1 per bar
    private float    progress = 0f;       // 0..1 playback progress
    private Bitmap   waveCache;           // pre-rendered bar bitmap (full color)
    private Bitmap   waveDimCache;        // pre-rendered bar bitmap (dim/unplayed color)

    // ── Paint ──────────────────────────────────────────────────────────────
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private OnSeekListener seekListener;

    // ── Colors (can be set before first draw) ─────────────────────────────
    private int colorPlayed = 0xFF25D366;  // WhatsApp green (sent); override for received
    private int colorUnplayed = 0x6625D366;

    public AudioWaveformView(@NonNull Context ctx) { super(ctx); init(); }
    public AudioWaveformView(@NonNull Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs); init();
    }
    public AudioWaveformView(@NonNull Context ctx, @Nullable AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle); init();
    }

    private void init() {
        // View is fully self-contained — no background needed
        setWillNotDraw(false);
    }

    /** Set 50-ish amplitude samples (any length; will be resampled to BAR_COUNT). */
    public void setWaveform(float[] rawAmplitudes) {
        this.amplitudes = resample(rawAmplitudes, BAR_COUNT);
        invalidateCache();
        invalidate();
    }

    /** Waveform not available — generate fake random bars (WhatsApp does this too). */
    public void setFakeWaveform(int seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[] fake = new float[BAR_COUNT];
        for (int i = 0; i < BAR_COUNT; i++) {
            // Bell-ish distribution: center louder
            float center = 1f - Math.abs((i - BAR_COUNT / 2f) / (BAR_COUNT / 2f)) * 0.4f;
            fake[i] = (0.2f + rng.nextFloat() * 0.8f) * center;
        }
        setWaveform(fake);
    }

    public void setProgress(float p) {
        if (Math.abs(p - progress) < 0.005f) return; // no-op if tiny change
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    public void setColors(int played, int unplayed) {
        this.colorPlayed = played;
        this.colorUnplayed = unplayed;
        invalidateCache();
        invalidate();
    }

    public void setOnSeekListener(OnSeekListener l) { this.seekListener = l; }

    // ── Touch seek ─────────────────────────────────────────────────────────
    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (ev.getAction() == android.view.MotionEvent.ACTION_UP
         || ev.getAction() == android.view.MotionEvent.ACTION_MOVE) {
            float frac = ev.getX() / Math.max(1f, getWidth());
            frac = Math.max(0f, Math.min(1f, frac));
            setProgress(frac);
            if (seekListener != null) seekListener.onSeek(frac);
            return true;
        }
        return super.onTouchEvent(ev);
    }

    // ── Draw ───────────────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w > 0 && h > 0) {
            invalidateCache();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (amplitudes == null || getWidth() == 0 || getHeight() == 0) return;

        ensureCache();
        if (waveCache == null) return;

        // Draw full dim bitmap first
        canvas.drawBitmap(waveDimCache, 0, 0, bitmapPaint);

        // Clip to played region and draw bright bitmap
        int playedX = (int) (progress * getWidth());
        if (playedX > 0) {
            canvas.save();
            canvas.clipRect(0, 0, playedX, getHeight());
            canvas.drawBitmap(waveCache, 0, 0, bitmapPaint);
            canvas.restore();
        }
    }

    // ── Cache management ──────────────────────────────────────────────────

    private void invalidateCache() {
        if (waveCache != null)    { waveCache.recycle();    waveCache = null; }
        if (waveDimCache != null) { waveDimCache.recycle(); waveDimCache = null; }
    }

    private void ensureCache() {
        int w = getWidth(), h = getHeight();
        if (waveCache != null || amplitudes == null || w == 0 || h == 0) return;

        Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setStyle(Paint.Style.FILL);

        float density  = getResources().getDisplayMetrics().density;
        float gap      = BAR_GAP_DP * density;
        float minH     = BAR_MIN_DP * density;
        float totalGap = gap * (BAR_COUNT - 1);
        float barW     = Math.max(1f, (w - totalGap) / BAR_COUNT);
        float centerY  = h / 2f;

        waveCache    = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        waveDimCache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas brightC = new Canvas(waveCache);
        Canvas dimC    = new Canvas(waveDimCache);

        barPaint.setColor(colorPlayed);
        Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dimPaint.setStyle(Paint.Style.FILL);
        dimPaint.setColor(colorUnplayed);

        for (int i = 0; i < BAR_COUNT; i++) {
            float amp    = amplitudes[i];
            float barH   = Math.max(minH, amp * (h * 0.85f));
            float left   = i * (barW + gap);
            float right  = left + barW;
            float top    = centerY - barH / 2f;
            float bottom = centerY + barH / 2f;
            // Rounded caps: radius = barW/2
            float radius = barW / 2f;
            brightC.drawRoundRect(left, top, right, bottom, radius, radius, barPaint);
            dimC.drawRoundRect(left, top, right, bottom, radius, radius, dimPaint);
        }
    }

    // ── Utils ─────────────────────────────────────────────────────────────

    private static float[] resample(float[] src, int targetLen) {
        if (src == null || src.length == 0) {
            float[] flat = new float[targetLen];
            for (int i = 0; i < targetLen; i++) flat[i] = 0.3f;
            return flat;
        }
        if (src.length == targetLen) return src;
        float[] out = new float[targetLen];
        float step = (float) src.length / targetLen;
        for (int i = 0; i < targetLen; i++) {
            float pos  = i * step;
            int   lo   = (int) pos;
            int   hi   = Math.min(lo + 1, src.length - 1);
            float frac = pos - lo;
            out[i] = src[lo] * (1 - frac) + src[hi] * frac;
        }
        return out;
    }
}
