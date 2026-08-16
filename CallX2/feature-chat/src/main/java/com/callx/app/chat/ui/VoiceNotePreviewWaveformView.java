package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
 *
 * Feature: TRIM HANDLES (Telegram-style edge trim). Two draggable vertical
 * handles pinned to the edges of the selected range let the user cut dead
 * air off the start and/or end before sending — drag the left handle in
 * from the start, the right handle in from the end, same as Telegram's
 * voice-note composer. Everything outside [trimStart, trimEnd] is drawn
 * dimmed; the playhead and tap/drag-to-seek are both clamped to stay
 * inside the selected range so you can only preview what will actually be
 * sent. Trim changes are reported via {@link OnTrimChangeListener} so the
 * caller can (a) constrain preview playback to the window and (b)
 * physically cut the audio file to that window before upload — see
 * VoiceTrimmer / ChatMediaController#finishAndSend().
 */
public class VoiceNotePreviewWaveformView extends View {

    public interface OnSeekListener {
        /** Fired continuously while dragging and once more on release.
         *  released=false while the finger is still moving (caller may
         *  choose to only commit an actual MediaPlayer.seekTo() on
         *  released=true to avoid thrashing). */
        void onSeek(float progress0to1, boolean released);
    }

    /** Fired while dragging either trim handle, and once more on release
     *  (released=true) — same contract as {@link OnSeekListener}. */
    public interface OnTrimChangeListener {
        void onTrimChanged(float trimStart0to1, float trimEnd0to1, boolean released);
    }

    private static final int BAR_COUNT = 56;
    private static final float BAR_GAP_DP = 2.5f;
    private static final float BAR_MIN_DP = 2f;
    private static final float THUMB_RADIUS_DP = 5f;

    private static final float HANDLE_WIDTH_DP = 10f;
    private static final float HANDLE_CAP_RADIUS_DP = 3f;
    /** Hit-test radius around each handle — wider than the drawn handle so
     *  it's easy to grab with a fingertip. */
    private static final float HANDLE_TOUCH_SLOP_DP = 18f;
    /** Smallest gap the two handles can be dragged to, as a fraction of
     *  the full clip — stops the user trimming the clip down to nothing. */
    private static final float MIN_TRIM_SPAN_FRACTION = 0.06f;

    private float[] displayBars = new float[0];
    private float progress = 0f; // 0..1, playhead position

    private float trimStart = 0f;
    private float trimEnd = 1f;

    private enum DragMode { NONE, HANDLE_LEFT, HANDLE_RIGHT, SEEK }
    private DragMode dragMode = DragMode.NONE;

    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trimmedOutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF handleRect = new RectF();

    private float barWidthPx, gapPx, minBarHeightPx, thumbRadiusPx;
    private float handleWidthPx, handleCapRadiusPx, handleTouchSlopPx;
    private OnSeekListener seekListener;
    private OnTrimChangeListener trimChangeListener;
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
        handleWidthPx = HANDLE_WIDTH_DP * density;
        handleCapRadiusPx = HANDLE_CAP_RADIUS_DP * density;
        handleTouchSlopPx = HANDLE_TOUCH_SLOP_DP * density;
        playedPaint.setColor(0xFF4CAF50);   // brand_primary — matches RecordingWaveformView default
        unplayedPaint.setColor(0x664CAF50); // same hue, muted — the "not yet played" portion
        thumbPaint.setColor(Color.WHITE);
        trimmedOutPaint.setColor(0x99000000); // scrim over the cut-off head/tail
        handlePaint.setColor(0xFFFFC107);     // amber — reads clearly against played/unplayed green
        setMinimumHeight((int) (28 * density));
    }

    /** Optional — lets callers match theme colors instead of the hardcoded default. */
    public void setColors(int playedColor, int unplayedColor, int thumbColor) {
        playedPaint.setColor(playedColor);
        unplayedPaint.setColor(unplayedColor);
        thumbPaint.setColor(thumbColor);
        invalidate();
    }

    /** Optional — lets callers match theme colors for the trim handles too. */
    public void setTrimHandleColor(int handleColor) {
        handlePaint.setColor(handleColor);
        invalidate();
    }

    public void setOnSeekListener(OnSeekListener listener) {
        this.seekListener = listener;
    }

    public void setOnTrimChangeListener(OnTrimChangeListener listener) {
        this.trimChangeListener = listener;
    }

    /** Whether the user can drag/tap to seek or drag the trim handles —
     *  disable while nothing is playable yet (e.g. the preview MediaPlayer
     *  hasn't prepared). */
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
     *  from user drag. progress0to1 is clamped to the current trim window
     *  (not just [0,1]) so the head can never wander into cut audio. */
    public void setProgress(float progress0to1) {
        this.progress = clampToTrim(progress0to1);
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    /** Programmatically sets the trim window WITHOUT notifying
     *  {@link OnTrimChangeListener} — used to (re)initialize a fresh
     *  preview to the full clip. */
    public void setTrimRange(float start0to1, float end0to1) {
        trimStart = Math.max(0f, Math.min(1f, start0to1));
        trimEnd = Math.max(trimStart, Math.min(1f, end0to1));
        progress = clampToTrim(progress);
        invalidate();
    }

    public float getTrimStart() {
        return trimStart;
    }

    public float getTrimEnd() {
        return trimEnd;
    }

    private float clampToTrim(float p) {
        float clamped = Math.max(0f, Math.min(1f, p));
        return Math.max(trimStart, Math.min(trimEnd, clamped));
    }

    public void reset() {
        displayBars = new float[0];
        progress = 0f;
        trimStart = 0f;
        trimEnd = 1f;
        dragMode = DragMode.NONE;
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
        int width = getWidth();
        if (width <= 0) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                dragMode = resolveDragMode(event.getX(), width);
                if (dragMode == DragMode.SEEK) {
                    setProgress(event.getX() / width);
                    if (seekListener != null) seekListener.onSeek(progress, false);
                } else if (dragMode != DragMode.NONE) {
                    updateHandleDrag(event.getX(), width, false);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragMode == DragMode.SEEK) {
                    setProgress(event.getX() / width);
                    if (seekListener != null) seekListener.onSeek(progress, false);
                } else if (dragMode == DragMode.HANDLE_LEFT || dragMode == DragMode.HANDLE_RIGHT) {
                    updateHandleDrag(event.getX(), width, false);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (dragMode == DragMode.SEEK) {
                    if (seekListener != null) seekListener.onSeek(progress, true);
                } else if (dragMode == DragMode.HANDLE_LEFT || dragMode == DragMode.HANDLE_RIGHT) {
                    updateHandleDrag(event.getX(), width, true);
                }
                dragMode = DragMode.NONE;
                return true;
            default:
                return false;
        }
    }

    /** Picks which handle (if either) the DOWN point landed on — favors
     *  whichever is closer if both hit-boxes overlap on a narrow/near-zero
     *  span. Anything outside both hit-boxes falls back to the existing
     *  tap/drag-to-seek behavior, clamped to the trim window. */
    private DragMode resolveDragMode(float x, int width) {
        float leftX = trimStart * width;
        float rightX = trimEnd * width;
        float distLeft = Math.abs(x - leftX);
        float distRight = Math.abs(x - rightX);
        boolean hitLeft = distLeft <= handleTouchSlopPx;
        boolean hitRight = distRight <= handleTouchSlopPx;
        if (hitLeft && hitRight) return distLeft <= distRight ? DragMode.HANDLE_LEFT : DragMode.HANDLE_RIGHT;
        if (hitLeft) return DragMode.HANDLE_LEFT;
        if (hitRight) return DragMode.HANDLE_RIGHT;
        return DragMode.SEEK;
    }

    private void updateHandleDrag(float x, int width, boolean released) {
        float p = Math.max(0f, Math.min(1f, x / width));
        if (dragMode == DragMode.HANDLE_LEFT) {
            trimStart = Math.max(0f, Math.min(p, trimEnd - MIN_TRIM_SPAN_FRACTION));
        } else if (dragMode == DragMode.HANDLE_RIGHT) {
            trimEnd = Math.min(1f, Math.max(p, trimStart + MIN_TRIM_SPAN_FRACTION));
        }
        progress = clampToTrim(progress);
        invalidate();
        if (trimChangeListener != null) trimChangeListener.onTrimChanged(trimStart, trimEnd, released);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (displayBars.length == 0 || getWidth() <= 0 || getHeight() <= 0) return;

        int width = getWidth();
        float centerY = getHeight() / 2f;
        float slot = barWidthPx + gapPx;
        float x = 0f;
        float radius = barWidthPx / 2f;
        float playedUpToX = progress * width;

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

        // Dim whatever falls outside the trim selection — the head/tail
        // that will actually get cut on send.
        float leftX = trimStart * width;
        float rightX = trimEnd * width;
        if (leftX > 0f) canvas.drawRect(0f, 0f, leftX, getHeight(), trimmedOutPaint);
        if (rightX < width) canvas.drawRect(rightX, 0f, width, getHeight(), trimmedOutPaint);

        // Draggable edge handles — Telegram-style vertical grip bars at
        // each edge of the selection, wider than the hairline they mark so
        // they're easy to grab with a fingertip (see handleTouchSlopPx).
        drawHandle(canvas, leftX);
        drawHandle(canvas, rightX);
    }

    private void drawHandle(Canvas canvas, float centerX) {
        handleRect.set(
                centerX - handleWidthPx / 2f, 0f,
                centerX + handleWidthPx / 2f, getHeight());
        canvas.drawRoundRect(handleRect, handleCapRadiusPx, handleCapRadiusPx, handlePaint);
    }
}
