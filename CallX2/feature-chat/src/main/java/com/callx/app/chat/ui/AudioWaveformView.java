package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * AudioWaveformView — replaces the plain {@code SeekBar} on voice-message bubbles.
 *
 * PERF FIX: A SeekBar re-measures/re-lays-out its track + thumb drawables on every
 * setProgress() call inside the 250ms playback tick (Drawable.setLevel() + invalidate
 * walks the View's draw state machine via its own internal Drawable, and on older
 * devices the thumb drawable bounds get re-resolved too). For 1 bubble that's invisible;
 * for a chat screen with several playable voice notes and a live progress tick every
 * 250ms each, it adds up to a lot of avoidable layout-adjacent work for what is just a
 * cosmetic progress bar.
 *
 * NEW: the bar shape (the "waveform") is rendered ONCE into a {@link Bitmap} when the
 * view is sized or the data changes — never again during playback. setProgress() only
 * stores a 0..1 float and calls invalidate(); onDraw() does two cheap drawBitmap() calls
 * (one clipped to the played fraction). No measure pass, no drawable level resolution,
 * no per-tick allocation.
 *
 * We don't currently decode real PCM amplitudes from the audio file (no waveform
 * extraction pipeline wired into the app yet), so the bar heights are generated once
 * from a stable seed (the message/audio URL) — same shape every time a given voice note
 * is bound, like WhatsApp's placeholder-style waveform, instead of a flat progress track.
 */
public class AudioWaveformView extends View {

    private static final int BAR_COUNT = 32;

    private float[] levels;
    private float progress = 0f; // 0..1, played fraction

    private Bitmap idleBitmap;     // unplayed-color bars, pre-rendered
    private Bitmap playedBitmap;   // played-color bars, pre-rendered (same shape)

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int idleColor = 0x66FFFFFF;
    private int playedColor = 0xFFFFFFFF;

    private OnSeekListener seekListener;

    public interface OnSeekListener {
        void onSeek(float fraction);
    }

    public AudioWaveformView(Context context) {
        super(context);
        init();
    }

    public AudioWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AudioWaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        levels = generateLevels(String.valueOf(System.identityHashCode(this)), BAR_COUNT);
        setMinimumHeight((int) (getResources().getDisplayMetrics().density * 28));
    }

    public void setColors(int idleColor, int playedColor) {
        this.idleColor = idleColor;
        this.playedColor = playedColor;
        invalidateBitmaps();
    }

    /** Seeds bar heights from a stable string (audio URL / message id) so the same
     *  voice note always renders the same "waveform" shape across rebinds/recycling. */
    public void setSeed(String seed) {
        levels = generateLevels(seed, BAR_COUNT);
        invalidateBitmaps();
    }

    /** Cheap path — called every playback tick. No layout/measure work, draw-only. */
    public void setProgress(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        if (clamped == progress) return;
        progress = clamped;
        invalidate();
    }

    public float getProgress() { return progress; }

    public void setOnSeekListener(OnSeekListener listener) {
        this.seekListener = listener;
    }

    private void invalidateBitmaps() {
        idleBitmap = null;
        playedBitmap = null;
        if (getWidth() > 0 && getHeight() > 0) renderBitmaps(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) renderBitmaps(w, h);
    }

    /** The only place bars are actually computed/drawn at full shape — once per size/seed
     *  change, never per-frame or per-tick. */
    private void renderBitmaps(int w, int h) {
        try {
            idleBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            playedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            drawBars(new Canvas(idleBitmap), w, h, idleColor);
            drawBars(new Canvas(playedBitmap), w, h, playedColor);
        } catch (OutOfMemoryError oom) {
            idleBitmap = null;
            playedBitmap = null;
        }
    }

    private void drawBars(Canvas canvas, int w, int h, int color) {
        barPaint.setColor(color);
        int n = levels.length;
        float slot = (float) w / n;
        float barWidth = slot * 0.55f;
        float radius = barWidth / 2f;
        float centerY = h / 2f;
        float x = 0;
        for (float lvl : levels) {
            float barHeight = Math.max(barWidth, lvl * h);
            canvas.drawRoundRect(
                    x, centerY - barHeight / 2f,
                    x + barWidth, centerY + barHeight / 2f,
                    radius, radius, barPaint);
            x += slot;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (idleBitmap == null) return;
        canvas.drawBitmap(idleBitmap, 0, 0, null);
        if (playedBitmap != null && progress > 0f) {
            int clipWidth = (int) (getWidth() * progress);
            if (clipWidth <= 0) return;
            canvas.save();
            canvas.clipRect(0, 0, clipWidth, getHeight());
            canvas.drawBitmap(playedBitmap, 0, 0, null);
            canvas.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                if (getWidth() <= 0) return true;
                float fraction = Math.max(0f, Math.min(1f, event.getX() / getWidth()));
                setProgress(fraction);
                if (seekListener != null) seekListener.onSeek(fraction);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private static float[] generateLevels(String seed, int count) {
        long s = (seed == null || seed.isEmpty()) ? 0L : seed.hashCode();
        Random r = new Random(s);
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = 0.25f + r.nextFloat() * 0.7f;
        }
        return out;
    }
}
