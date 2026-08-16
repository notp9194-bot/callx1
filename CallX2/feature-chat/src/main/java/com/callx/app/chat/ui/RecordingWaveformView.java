package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

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
 * ── Advanced optimization pass ──────────────────────────────────────────
 * 1. Ring buffer is now a PRIMITIVE float[] (fixed MAX_CAPACITY), not
 *    ArrayDeque<Float>. ArrayDeque<Float> autoboxes a new Float object on
 *    every single pushLevel()/pushLevelQuiet() call (~5-10/sec for the
 *    entire duration of every voice note, on both the recorder's own bar
 *    AND the partner's live preview) — continuous small-object churn for
 *    the GC to chase on a screen that's also trying to keep rvMessages
 *    scrolling smoothly. A primitive circular buffer means ZERO allocation
 *    per sample, period.
 * 2. onDraw no longer calls descendingIterator() — iterating an ArrayDeque
 *    allocates an Iterator object per draw pass. Draw now walks the
 *    primitive array by index only.
 * 3. invalidate() → postInvalidateOnAnimation(). Plain invalidate() can
 *    request a traversal mid-frame; postInvalidateOnAnimation() aligns the
 *    redraw to the next Choreographer frame, the same mechanism the
 *    platform itself uses for animations — avoids this small view forcing
 *    an out-of-cadence draw pass that competes with rvMessages' own frame.
 *
 * Cheap by design: a fixed-capacity ring buffer (no allocation per frame)
 * and a single onDraw pass — no bitmap caching needed since the content
 * changes every tick anyway.
 */
public class RecordingWaveformView extends View {

    private static final float BAR_GAP_DP  = 3f;
    private static final float BAR_MIN_DP  = 2f;

    /** Generous ceiling for the widest usage (full-width own-recording bar
     *  on a large screen); the actual effective window is `capacity`,
     *  recomputed per view width in onSizeChanged. One array, allocated
     *  once, reused for the view's entire lifetime — never resized. */
    private static final int MAX_CAPACITY = 160;
    private final float[] buffer = new float[MAX_CAPACITY];
    /** Total samples ever pushed (not wrapped) — lets draw/size logic use
     *  a cheap min() to know how many valid entries currently exist. */
    private long totalPushed = 0;
    private int capacity = 40; // recomputed from width in onSizeChanged

    /** Sentinel written into `buffer` by {@link #pushGapMarker()} to mark a
     *  pause/resume boundary — onDraw renders it as three small dots
     *  instead of a bar (WhatsApp shows the same cue when you resume a
     *  paused recording), so the discontinuity in the clip stays visible
     *  even while the live strip keeps scrolling. */
    private static final float GAP_MARKER = -1f;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float barWidthPx;
    private float gapPx;
    private float minBarHeightPx;
    private float dotRadiusPx;

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
        dotPaint.setColor(0xFF4CAF50);
        dotRadiusPx = 1.3f * density;
        setMinimumHeight((int) (28 * density));
    }

    /** Optional — lets callers match theme colors instead of the hardcoded default. */
    public void setBarColor(int color) {
        barPaint.setColor(color);
        dotPaint.setColor(color);
        invalidate();
    }

    /** Marks a pause/resume boundary in the stream — renders as three tiny
     *  dots instead of a bar once it scrolls into view. Call once when the
     *  recording is resumed (not on every tick). */
    public void pushGapMarker() {
        writeSample(GAP_MARKER);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0) return;
        // ~3.5dp per bar (bar + gap) feels close to WhatsApp's density.
        float slot = 3.5f * getResources().getDisplayMetrics().density;
        capacity = Math.max(8, Math.min(MAX_CAPACITY, (int) (w / slot)));
        barWidthPx = Math.max(1.5f, slot - gapPx);
        // No buffer copy/trim needed — draw already only ever reads back
        // the most recent `capacity` entries (see onDraw), so shrinking or
        // growing `capacity` takes effect on the very next frame for free.
    }

    /**
     * Push a new normalized amplitude sample (0f..1f). Called once per
     * poll tick (~100ms) while recording. Oldest bar drops off the left
     * once the buffer is full — bars appear to scroll leftward.
     * Zero allocation: writes a primitive into a fixed circular array.
     */
    public void pushLevel(float level) {
        writeSample(level);
        // postInvalidateOnAnimation(), not invalidate() — batches this
        // view's redraw onto the next Choreographer frame instead of
        // requesting an immediate mid-frame traversal.
        postInvalidateOnAnimation();
    }

    /**
     * Same as {@link #pushLevel(float)} but does NOT request a redraw —
     * updates the ring buffer only. Used while the message list is
     * actively flinging, so this view's draw pass never competes with
     * RecyclerView's own frame budget mid-scroll. Pair with {@link #flush()}
     * to catch the view up with one single draw once scrolling settles.
     */
    public void pushLevelQuiet(float level) {
        writeSample(level);
    }

    private void writeSample(float level) {
        // GAP_MARKER (-1) passes through unclamped — onDraw special-cases it.
        float clamped = (level == GAP_MARKER) ? GAP_MARKER : Math.max(0f, Math.min(1f, level));
        buffer[(int) (totalPushed % MAX_CAPACITY)] = clamped;
        totalPushed++;
    }

    /** Forces one draw pass to catch up on any pushLevelQuiet() calls made
     *  while scrolling was active. No-op (and cheap) if nothing changed. */
    public void flush() {
        postInvalidateOnAnimation();
    }

    /** Clears all bars back to empty (called when a new recording starts). */
    public void reset() {
        totalPushed = 0;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = (int) Math.min(totalPushed, capacity);
        if (count == 0 || getWidth() <= 0 || getHeight() <= 0) return;

        float centerY = getHeight() / 2f;
        float slot = barWidthPx + gapPx;
        // Right-align the strip so the newest bar always sits at the right
        // edge, same as WhatsApp — older bars trail off to the left.
        float x = getWidth() - barWidthPx;
        float radius = barWidthPx / 2f;

        // Walk the primitive array newest-to-oldest by index — no
        // Iterator object, no boxing, just arithmetic on a fixed array.
        for (int i = 0; i < count && x >= -barWidthPx; i++) {
            long sampleIndex = totalPushed - 1 - i;
            float lvl = buffer[(int) (sampleIndex % MAX_CAPACITY)];
            if (lvl == GAP_MARKER) {
                // Three small dots in a horizontal row ("···") at the
                // vertical center — reads as a pause boundary, not a
                // vertical ellipsis, matching the WhatsApp reference cue.
                float dotCx = x + barWidthPx / 2f;
                float dotSpacing = dotRadiusPx * 2.6f;
                canvas.drawCircle(dotCx - dotSpacing, centerY, dotRadiusPx, dotPaint);
                canvas.drawCircle(dotCx, centerY, dotRadiusPx, dotPaint);
                canvas.drawCircle(dotCx + dotSpacing, centerY, dotRadiusPx, dotPaint);
            } else {
                float barHeight = Math.max(minBarHeightPx, lvl * getHeight());
                canvas.drawRoundRect(
                        x, centerY - barHeight / 2f,
                        x + barWidthPx, centerY + barHeight / 2f,
                        radius, radius, barPaint);
            }
            x -= slot;
        }
    }
}
