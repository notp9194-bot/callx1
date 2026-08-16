package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * VoiceNotePreviewWaveformView — the FROZEN, full-clip waveform shown once
 * a recording is paused (Feature: pause/resume/play/adjust/send, matching
 * the WhatsApp voice-note composer). Unlike {@link RecordingWaveformView}
 * (a scrolling ring buffer fed live while recording, oldest bars dropped),
 * this view is handed the recording's ENTIRE amplitude history in one shot
 * via {@link #setSamples(float[])} and downsamples it to fit the width —
 * the whole clip is always visible at once, like WhatsApp/Telegram's
 * paused-preview bar.
 *
 * Also renders and drives the draggable playhead: the portion already
 * played back is drawn solid, the rest muted, with a small circular thumb
 * at the boundary. Dragging the thumb (or tapping anywhere on the bar)
 * reports the touched position via {@link OnSeekListener} — that's the
 * "adjust" step before Resume/Send.
 */
public class VoiceNotePreviewWaveformView extends View {

    public interface OnSeekListener {
        /** Fired continuously while dragging and once more on release.
         *  released=false while the finger is still moving (caller may
         *  choose to only commit an actual MediaPlayer.seekTo() on
         *  released=true to avoid thrashing). */
        void onSeek(float progress0to1, boolean released);
    }

    private static final int BAR_COUNT = 56;
    private static final float BAR_GAP_DP = 2.5f;
    private static final float BAR_MIN_DP = 2f;
    private static final float THUMB_RADIUS_DP = 5f;

    private float[] displayBars = new float[0];
    private float progress = 0f; // 0..1, playhead position

    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float barWidthPx, gapPx, minBarHeightPx, thumbRadiusPx;
    private OnSeekListener seekListener;
    private boolean draggable = true;

    public VoiceNotePreviewWaveformView(Context context) { super(context); init(); }
    public VoiceNotePreviewWaveformView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public VoiceNotePreviewWaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        gapPx = BAR_GAP_DP * density;
        minBarHeightPx = BAR_MIN_DP * density;
        thumbRadiusPx = THUMB_RADIUS_DP * density;
        playedPaint.setColor(0xFF4CAF50);   // brand_primary — matches RecordingWaveformView default
        unplayedPaint.setColor(0x664CAF50); // same hue, muted — the "not yet played" portion
        thumbPaint.setColor(Color.WHITE);
        setMinimumHeight((int) (28 * density));
    }

    /** Optional — lets callers match theme colors instead of the hardcoded default. */
    public void setColors(int playedColor, int unplayedColor, int thumbColor) {
        playedPaint.setColor(playedColor);
        unplayedPaint.setColor(unplayedColor);
        thumbPaint.setColor(thumbColor);
        invalidate();
    }

    public void setOnSeekListener(OnSeekListener listener) {
        this.seekListener = listener;
    }

    /** Whether the user can drag/tap to seek — disable while nothing is
     *  playable yet (e.g. the preview MediaPlayer hasn't prepared). */
    public void setDraggable(boolean draggable) {
        this.draggable = draggable;
    }

    /**
     * Feeds the FULL amplitude history of the paused recording (every
     * sample collected since start(), not just the last N like the live
     * ring buffer) — downsampled here into a fixed BAR_COUNT-bucket
     * average so the whole clip renders across the view's width.
     */
    public void setSamples(float[] allSamples) {
        if (allSamples == null || allSamples.length == 0) {
            displayBars = new float[0];
            invalidate();
            return;
        }
        float[] out = new float[BAR_COUNT];
        float bucketSize = allSamples.length / (float) BAR_COUNT;
        for (int i = 0; i < BAR_COUNT; i++) {
            int from = (int) (i * bucketSize);
            int to = Math.max(from + 1, (int) ((i + 1) * bucketSize));
            to = Math.min(to, allSamples.length);
            float sum = 0f;
            int count = 0;
            for (int j = from; j < to; j++) {
                sum += allSamples[j];
                count++;
            }
            out[i] = count > 0 ? sum / count : 0f;
        }
        displayBars = out;
        invalidate();
    }

    /** Moves the playhead — called both from playback progress ticks and
     *  from user drag. progress0to1 is clamped to [0,1]. */
    public void setProgress(float progress0to1) {
        this.progress = Math.max(0f, Math.min(1f, progress0to1));
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    public void reset() {
        displayBars = new float[0];
        progress = 0f;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || BAR_COUNT == 0) return;
        float totalGap = gapPx * (BAR_COUNT - 1);
        barWidthPx = Math.max(1.5f, (w - totalGap) / BAR_COUNT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!draggable) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through — a single tap also seeks immediately
            case MotionEvent.ACTION_MOVE: {
                float p = getWidth() > 0 ? event.getX() / getWidth() : 0f;
                setProgress(p);
                if (seekListener != null) seekListener.onSeek(progress, false);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (seekListener != null) seekListener.onSeek(progress, true);
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (displayBars.length == 0 || getWidth() <= 0 || getHeight() <= 0) return;

        float centerY = getHeight() / 2f;
        float slot = barWidthPx + gapPx;
        float x = 0f;
        float radius = barWidthPx / 2f;
        float playedUpToX = progress * getWidth();

        for (int i = 0; i < displayBars.length; i++) {
            float barHeight = Math.max(minBarHeightPx, displayBars[i] * getHeight());
            Paint paint = (x + barWidthPx / 2f) <= playedUpToX ? playedPaint : unplayedPaint;
            canvas.drawRoundRect(
                    x, centerY - barHeight / 2f,
                    x + barWidthPx, centerY + barHeight / 2f,
                    radius, radius, paint);
            x += slot;
        }

        canvas.drawCircle(playedUpToX, centerY, thumbRadiusPx, thumbPaint);
    }
}
