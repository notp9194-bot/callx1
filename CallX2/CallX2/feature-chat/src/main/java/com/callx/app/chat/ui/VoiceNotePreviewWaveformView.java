package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.Locale;

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
 * VoiceTrimmer / ChatMediaController#finishAndSend(). Grabbing a handle
 * fires a light haptic tick; pushing one past the other (hitting
 * MIN_TRIM_SPAN_FRACTION) fires a distinct reject-style buzz, once per
 * press, so both ends of the drag have physical feedback without relying
 * on the caller to watch pixel-level clamping.
 *
 * Feature: PINCH/ZOOM. For long recordings the whole-clip view compresses
 * every sample into {@link #BARS_ON_SCREEN} fixed bars, which isn't enough
 * resolution to trim precisely to the syllable. A two-finger pinch zooms
 * in (up to {@link #MAX_SCALE}x) around the pinch focus; once zoomed, a
 * single-finger drag pans the visible window instead of seeking (a tap
 * that barely moves still seeks — see the PAN branch in onTouchEvent), and
 * the waveform re-buckets the RAW sample array for just the visible slice
 * on every draw, so zooming in reveals finer detail instead of just
 * stretching the same 56 bars. setSamples() resets zoom/pan back to the
 * full clip, since a fresh pause/resume changes what the old fractions
 * even point at.
 *
 * Feature: SNAP-TO-SILENCE. On releasing a handle, if a quiet stretch
 * (below {@link #SILENCE_BAR_THRESHOLD}) sits within
 * {@link #SNAP_SEARCH_FRACTION} of where the finger let go, the handle
 * snaps to the quietest point in that stretch instead of landing wherever
 * the finger happened to be — cutting dead air cleanly without needing
 * pixel-perfect placement.
 *
 * Feature: UNDO TRIM. {@link #resetTrim()} snaps both handles back to
 * [0,1] in one call — the reset button's handler.
 *
 * Feature: DURATION BADGES. A small mm:ss (or m:ss.t for clips under a
 * minute) label is drawn beside each handle at all times (not just while
 * dragging), showing that handle's position in the clip — set the clip's
 * total duration via {@link #setTotalDurationMs(int)}.
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

    private static final int BARS_ON_SCREEN = 56;
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

    /** Pinch/zoom bounds. 1x = whole clip visible (old behavior). */
    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 6f;
    /** A one-finger movement shorter than this (dp) while zoomed reads as
     *  a tap-to-seek rather than a pan. */
    private static final float TAP_SLOP_DP = 8f;

    /** Bars whose (downsampled) amplitude sits below this are treated as
     *  "silence" for snap purposes. Tuned against the same 0..1 amplitude
     *  scale ChatMediaController's normalizeAmplitude() already produces. */
    private static final float SILENCE_BAR_THRESHOLD = 0.10f;
    /** How far (as a fraction of the full clip) snap-to-silence will look
     *  from the point the finger released a handle. */
    private static final float SNAP_SEARCH_FRACTION = 0.035f;

    private float[] rawSamples = new float[0];
    private float progress = 0f; // 0..1, playhead position

    private float trimStart = 0f;
    private float trimEnd = 1f;

    /** Total clip duration, for the handle duration badges. 0 = unknown,
     *  badges hidden. */
    private int totalDurationMs = 0;

    // ── Zoom / pan state ────────────────────────────────────────────────
    private float scaleFactor = 1f;
    private float scrollPx = 0f; // in content-px space, i.e. width*scaleFactor
    private ScaleGestureDetector scaleGestureDetector;
    private boolean isScaling = false;

    private enum DragMode { NONE, HANDLE_LEFT, HANDLE_RIGHT, SEEK, PAN }
    private DragMode dragMode = DragMode.NONE;
    private float downX = 0f;
    private float lastPanX = 0f;
    private float tapSlopPx;

    /** True once the currently-dragged handle has been pushed up against
     *  MIN_TRIM_SPAN_FRACTION — used to fire the "hit the limit" haptic
     *  exactly once per press instead of buzzing on every move sample
     *  while the finger stays pinned at the limit. Reset on each new grab. */
    private boolean minSpanLimitHit = false;

    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trimmedOutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF handleRect = new RectF();
    private final RectF badgeRect = new RectF();

    private float barWidthPx, gapPx, minBarHeightPx, thumbRadiusPx;
    private float handleWidthPx, handleCapRadiusPx, handleTouchSlopPx;
    private float badgePadH, badgePadV, badgeTextSizePx;
    private OnSeekListener seekListener;
    private OnTrimChangeListener trimChangeListener;
    private boolean draggable = true;
    /** Scratch buffer reused every draw instead of allocating a new
     *  float[BARS_ON_SCREEN] per frame. */
    private final float[] visibleBarsScratch = new float[BARS_ON_SCREEN];

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
        tapSlopPx = TAP_SLOP_DP * density;
        badgePadH = 4 * density;
        badgePadV = 2 * density;
        badgeTextSizePx = 8.5f * density;
        playedPaint.setColor(0xFF4CAF50);   // brand_primary — matches RecordingWaveformView default
        unplayedPaint.setColor(0x664CAF50); // same hue, muted — the "not yet played" portion
        thumbPaint.setColor(Color.WHITE);
        trimmedOutPaint.setColor(0x99000000); // scrim over the cut-off head/tail
        handlePaint.setColor(0xFFFFC107);     // amber — reads clearly against played/unplayed green
        badgeBgPaint.setColor(0xE6141420);
        badgeBgPaint.setStyle(Paint.Style.FILL);
        badgeTextPaint.setColor(Color.WHITE);
        badgeTextPaint.setTextSize(badgeTextSizePx);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        setMinimumHeight((int) (28 * density));

        scaleGestureDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                isScaling = true;
                // Abandon any single-finger drag the moment a second finger lands.
                dragMode = DragMode.NONE;
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                int w = getWidth();
                if (w <= 0) return true;
                float focusX = detector.getFocusX();
                float contentFocusXBefore = scrollPx + focusX;
                float newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE,
                        scaleFactor * detector.getScaleFactor()));
                if (newScale != scaleFactor) {
                    float ratio = newScale / scaleFactor;
                    scaleFactor = newScale;
                    scrollPx = clampScroll(contentFocusXBefore * ratio - focusX);
                    invalidate();
                }
                return true;
            }

            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                isScaling = false;
            }
        });
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

    /** Total clip duration in ms — drives the mm:ss handle badges. Pass 0
     *  to hide the badges (duration not known yet). */
    public void setTotalDurationMs(int ms) {
        this.totalDurationMs = Math.max(0, ms);
        invalidate();
    }

    /**
     * Feeds the FULL amplitude history of the paused recording (every
     * sample collected since start(), not just the last N like the live
     * ring buffer). Kept at full resolution (not pre-downsampled) so
     * pinch/zoom can re-bucket just the visible slice for finer detail —
     * see {@link #computeVisibleBars()}. Resets zoom/pan to the full clip,
     * since a fresh pause/resume changes what the old scroll fractions
     * would even point at.
     */
    public void setSamples(float[] allSamples) {
        rawSamples = (allSamples != null) ? allSamples.clone() : new float[0];
        scaleFactor = MIN_SCALE;
        scrollPx = 0f;
        invalidate();
    }

    /** Moves the playhead — called both from playback progress ticks and
     *  from user drag. progress0to1 is clamped to the current trim window. */
    public void setProgress(float progress0to1) {
        progress = clampToTrim(progress0to1);
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

    /** UNDO TRIM — snaps both handles back to the full clip [0,1] and
     *  notifies {@link OnTrimChangeListener} (released=true) so the caller
     *  re-syncs playback range and the duration readout, exactly as if the
     *  user had dragged both handles back out by hand. Does not touch
     *  zoom/pan. */
    public void resetTrim() {
        trimStart = 0f;
        trimEnd = 1f;
        progress = clampToTrim(progress);
        invalidate();
        if (trimChangeListener != null) trimChangeListener.onTrimChanged(trimStart, trimEnd, true);
    }

    private float clampToTrim(float p) {
        float clamped = Math.max(0f, Math.min(1f, p));
        return Math.max(trimStart, Math.min(trimEnd, clamped));
    }

    public void reset() {
        rawSamples = new float[0];
        progress = 0f;
        trimStart = 0f;
        trimEnd = 1f;
        totalDurationMs = 0;
        scaleFactor = MIN_SCALE;
        scrollPx = 0f;
        dragMode = DragMode.NONE;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0) return;
        // Bars always fill the screen width with the CURRENT visible slice
        // (see computeVisibleBars) — bar geometry itself doesn't depend on
        // zoom level, only which samples land in each bar do.
        float totalGap = gapPx * (BARS_ON_SCREEN - 1);
        barWidthPx = Math.max(1.5f, (w - totalGap) / BARS_ON_SCREEN);
        scrollPx = clampScroll(scrollPx);
    }

    // ── Zoom/pan coordinate helpers ─────────────────────────────────────

    private float contentWidth() {
        return getWidth() * scaleFactor;
    }

    private float maxScrollPx() {
        return Math.max(0f, contentWidth() - getWidth());
    }

    private float clampScroll(float s) {
        return Math.max(0f, Math.min(maxScrollPx(), s));
    }

    /** Fraction-of-full-clip → screen x, accounting for current zoom/pan. */
    private float xForFrac(float frac) {
        return frac * contentWidth() - scrollPx;
    }

    /** Screen x → fraction-of-full-clip, accounting for current zoom/pan. */
    private float fracForX(float screenX) {
        float cw = contentWidth();
        if (cw <= 0f) return 0f;
        float contentX = screenX + scrollPx;
        return Math.max(0f, Math.min(1f, contentX / cw));
    }

    /**
     * Re-buckets rawSamples down to BARS_ON_SCREEN bars covering ONLY the
     * currently visible window [scrollPx, scrollPx+width] of the content.
     * At scaleFactor==1 this is identical to the old whole-clip downsample;
     * as the user pinch-zooms in, the visible window shrinks so each bar
     * represents fewer raw samples — real added resolution, not just
     * stretched pixels of the same 56 bars.
     */
    private float[] computeVisibleBars() {
        int len = rawSamples.length;
        if (len == 0 || getWidth() <= 0) return visibleBarsScratch;
        float cw = contentWidth();
        float visStartFrac = cw > 0 ? scrollPx / cw : 0f;
        float visEndFrac = cw > 0 ? Math.min(1f, (scrollPx + getWidth()) / cw) : 1f;
        int fromIdx = Math.max(0, (int) (visStartFrac * len));
        int toIdx = Math.min(len, Math.max(fromIdx + 1, (int) (visEndFrac * len)));

        int bucketLen = toIdx - fromIdx;
        float bucketSize = bucketLen / (float) BARS_ON_SCREEN;
        for (int i = 0; i < BARS_ON_SCREEN; i++) {
            int from = fromIdx + (int) (i * bucketSize);
            int to = Math.max(from + 1, fromIdx + (int) ((i + 1) * bucketSize));
            to = Math.min(to, toIdx);
            float sum = 0f;
            int count = 0;
            for (int j = from; j < to && j < len; j++) {
                sum += rawSamples[j];
                count++;
            }
            visibleBarsScratch[i] = count > 0 ? sum / count : 0f;
        }
        return visibleBarsScratch;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!draggable) return false;
        int width = getWidth();
        if (width <= 0) return false;

        scaleGestureDetector.onTouchEvent(event);

        if (event.getPointerCount() > 1 || isScaling) {
            // A pinch owns the gesture — abandon any single-finger drag
            // that might have been mid-flight when the second finger landed.
            dragMode = DragMode.NONE;
            int action = event.getActionMasked();
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(
                        action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
            }
            return true;
        }

        float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = x;
                dragMode = resolveDragMode(x, width);
                if (dragMode == DragMode.SEEK) {
                    if (scaleFactor > 1.01f) {
                        // Zoomed in: a single finger pans the visible window
                        // instead of seeking (still resolves to a tap-seek
                        // on release if it never really moved — see UP).
                        dragMode = DragMode.PAN;
                        lastPanX = x;
                    } else {
                        setProgress(fracForX(x));
                        if (seekListener != null) seekListener.onSeek(progress, false);
                    }
                } else if (dragMode != DragMode.NONE) {
                    minSpanLimitHit = false;
                    // Light tick on grab — confirms the finger actually
                    // caught a handle (vs. landing on the seek area) before
                    // any dragging happens.
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    updateHandleDrag(x, width, false);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragMode == DragMode.SEEK) {
                    setProgress(fracForX(x));
                    if (seekListener != null) seekListener.onSeek(progress, false);
                } else if (dragMode == DragMode.PAN) {
                    float dx = x - lastPanX;
                    lastPanX = x;
                    scrollPx = clampScroll(scrollPx - dx);
                    invalidate();
                } else if (dragMode == DragMode.HANDLE_LEFT || dragMode == DragMode.HANDLE_RIGHT) {
                    updateHandleDrag(x, width, false);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                getParent().requestDisallowInterceptTouchEvent(false);
                if (dragMode == DragMode.SEEK) {
                    if (seekListener != null) seekListener.onSeek(progress, true);
                } else if (dragMode == DragMode.PAN) {
                    // A "pan" that barely moved is really a tap — seek there.
                    if (Math.abs(x - downX) < tapSlopPx) {
                        setProgress(fracForX(x));
                        if (seekListener != null) {
                            seekListener.onSeek(progress, false);
                            seekListener.onSeek(progress, true);
                        }
                    }
                } else if (dragMode == DragMode.HANDLE_LEFT || dragMode == DragMode.HANDLE_RIGHT) {
                    updateHandleDrag(x, width, true);
                }
                dragMode = DragMode.NONE;
                return true;
            }
            default:
                return false;
        }
    }

    /** Picks which handle (if either) the DOWN point landed on — favors
     *  whichever is closer if both hit-boxes overlap on a narrow/near-zero
     *  span. Anything outside both hit-boxes falls back to the existing
     *  tap/drag-to-seek (or pan, if zoomed) behavior. */
    private DragMode resolveDragMode(float x, int width) {
        float leftX = xForFrac(trimStart);
        float rightX = xForFrac(trimEnd);
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
        float p = fracForX(x);
        boolean hitLimit;
        if (dragMode == DragMode.HANDLE_LEFT) {
            float maxAllowed = trimEnd - MIN_TRIM_SPAN_FRACTION;
            hitLimit = p > maxAllowed;
            trimStart = Math.max(0f, Math.min(p, maxAllowed));
        } else if (dragMode == DragMode.HANDLE_RIGHT) {
            float minAllowed = trimStart + MIN_TRIM_SPAN_FRACTION;
            hitLimit = p < minAllowed;
            trimEnd = Math.min(1f, Math.max(p, minAllowed));
        } else {
            hitLimit = false;
        }
        // Fire the "can't trim any further" buzz once per press, right as
        // the finger first pushes past the minimum span — not on every
        // move sample while it stays pinned there.
        if (hitLimit && !minSpanLimitHit) {
            performHapticFeedback(HapticFeedbackConstants.REJECT);
        }
        minSpanLimitHit = hitLimit;

        if (released) {
            snapToSilenceIfClose(dragMode == DragMode.HANDLE_LEFT);
        }

        progress = clampToTrim(progress);
        invalidate();
        if (trimChangeListener != null) trimChangeListener.onTrimChanged(trimStart, trimEnd, released);
    }

    /**
     * SNAP-TO-SILENCE. Called right as a handle is released: looks at the
     * whole-clip downsample (same resolution the un-zoomed view uses, so
     * the search window is stable regardless of current zoom) within
     * SNAP_SEARCH_FRACTION of where the finger let go, and — if a quiet
     * stretch is found there — snaps the handle to the quietest bar's
     * boundary in that stretch instead of leaving it exactly under the
     * fingertip. A light haptic marks the snap so it doesn't feel silent
     * (pun intended) when the handle jumps a few px on release.
     */
    private void snapToSilenceIfClose(boolean isLeftHandle) {
        if (rawSamples.length == 0) return;
        float currentFrac = isLeftHandle ? trimStart : trimEnd;

        // Downsample the WHOLE clip at BARS_ON_SCREEN resolution for a
        // zoom-independent search window, regardless of what's currently
        // on screen.
        float[] wholeClip = new float[BARS_ON_SCREEN];
        float bucketSize = rawSamples.length / (float) BARS_ON_SCREEN;
        for (int i = 0; i < BARS_ON_SCREEN; i++) {
            int from = (int) (i * bucketSize);
            int to = Math.max(from + 1, (int) ((i + 1) * bucketSize));
            to = Math.min(to, rawSamples.length);
            float sum = 0f;
            int count = 0;
            for (int j = from; j < to; j++) { sum += rawSamples[j]; count++; }
            wholeClip[i] = count > 0 ? sum / count : 0f;
        }

        int centerBar = Math.round(currentFrac * (BARS_ON_SCREEN - 1));
        int windowBars = Math.max(1, Math.round(SNAP_SEARCH_FRACTION * BARS_ON_SCREEN));
        int lo = Math.max(0, centerBar - windowBars);
        int hi = Math.min(BARS_ON_SCREEN - 1, centerBar + windowBars);

        int quietestIdx = -1;
        float quietestVal = SILENCE_BAR_THRESHOLD;
        for (int i = lo; i <= hi; i++) {
            if (wholeClip[i] < quietestVal) {
                quietestVal = wholeClip[i];
                quietestIdx = i;
            }
        }
        if (quietestIdx < 0) return; // nothing quiet enough nearby — leave the handle where it is

        float snappedFrac = quietestIdx / (float) (BARS_ON_SCREEN - 1);
        if (isLeftHandle) {
            trimStart = Math.max(0f, Math.min(snappedFrac, trimEnd - MIN_TRIM_SPAN_FRACTION));
        } else {
            trimEnd = Math.min(1f, Math.max(snappedFrac, trimStart + MIN_TRIM_SPAN_FRACTION));
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (rawSamples.length == 0 || width <= 0 || height <= 0) return;

        float[] bars = computeVisibleBars();
        float centerY = height / 2f;
        float slot = barWidthPx + gapPx;
        float x = 0f;
        float radius = barWidthPx / 2f;
        float playedUpToX = xForFrac(progress);

        for (int i = 0; i < bars.length; i++) {
            float barHeight = Math.max(minBarHeightPx, bars[i] * height);
            Paint paint = (x + barWidthPx / 2f) <= playedUpToX ? playedPaint : unplayedPaint;
            canvas.drawRoundRect(
                    x, centerY - barHeight / 2f,
                    x + barWidthPx, centerY + barHeight / 2f,
                    radius, radius, paint);
            x += slot;
        }

        canvas.drawCircle(playedUpToX, centerY, thumbRadiusPx, thumbPaint);

        // Dim whatever falls outside the trim selection — the head/tail
        // that will actually get cut on send. Clamped to the on-screen
        // range since the trim edges may currently be scrolled off-screen
        // while zoomed in.
        float leftX = Math.max(0f, xForFrac(trimStart));
        float rightX = Math.min(width, xForFrac(trimEnd));
        if (xForFrac(trimStart) > 0f) canvas.drawRect(0f, 0f, Math.min(width, xForFrac(trimStart)), height, trimmedOutPaint);
        if (xForFrac(trimEnd) < width) canvas.drawRect(Math.max(0f, xForFrac(trimEnd)), 0f, width, height, trimmedOutPaint);

        // Draggable edge handles — Telegram-style vertical grip bars at
        // each edge of the selection, wider than the hairline they mark so
        // they're easy to grab with a fingertip (see handleTouchSlopPx).
        // Only drawn (and badged) while their edge is actually on screen.
        float startHandleX = xForFrac(trimStart);
        float endHandleX = xForFrac(trimEnd);
        if (startHandleX >= -handleWidthPx && startHandleX <= width + handleWidthPx) {
            drawHandle(canvas, startHandleX);
            drawHandleBadge(canvas, startHandleX, trimStart, true);
        }
        if (endHandleX >= -handleWidthPx && endHandleX <= width + handleWidthPx) {
            drawHandle(canvas, endHandleX);
            drawHandleBadge(canvas, endHandleX, trimEnd, false);
        }
    }

    private void drawHandle(Canvas canvas, float centerX) {
        handleRect.set(
                centerX - handleWidthPx / 2f, 0f,
                centerX + handleWidthPx / 2f, getHeight());
        canvas.drawRoundRect(handleRect, handleCapRadiusPx, handleCapRadiusPx, handlePaint);
    }

    /**
     * DURATION BADGE — a small always-visible mm:ss pill beside a handle
     * (Telegram-style), not just while dragging. Sits just outside the
     * selection on that handle's side so it never sits over the dimmed
     * cut region, and clamps to the view's horizontal bounds so it never
     * draws half off-screen at either edge.
     */
    private void drawHandleBadge(Canvas canvas, float handleX, float frac, boolean isStartHandle) {
        if (totalDurationMs <= 0) return;
        String text = formatBadgeTime(Math.round(frac * totalDurationMs));
        float textW = badgeTextPaint.measureText(text);
        float boxW = textW + badgePadH * 2;
        float boxH = badgeTextSizePx + badgePadV * 2;

        float anchorX = isStartHandle ? handleX - handleWidthPx / 2f - 2f : handleX + handleWidthPx / 2f + 2f;
        float left = isStartHandle ? anchorX - boxW : anchorX;
        float right = left + boxW;
        float top = 1f;
        float bottom = top + boxH;

        if (left < 0f) { right -= left; left = 0f; }
        if (right > getWidth()) { left -= (right - getWidth()); right = getWidth(); }

        badgeRect.set(left, top, right, bottom);
        canvas.drawRoundRect(badgeRect, boxH / 2f, boxH / 2f, badgeBgPaint);
        canvas.drawText(text, (left + right) / 2f, bottom - badgePadV - (badgeTextSizePx * 0.12f), badgeTextPaint);
    }

    private static String formatBadgeTime(int ms) {
        int totalSec = ms / 1000;
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", min, sec);
    }
}
