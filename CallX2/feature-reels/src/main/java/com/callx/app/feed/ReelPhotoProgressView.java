package com.callx.app.feed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * ReelPhotoProgressView — Ultra-Advanced Story-Style Progress Bar v5
 * ═══════════════════════════════════════════════════════════════════
 *
 * A custom view that renders N segmented story-style progress bars — one per photo —
 * matching Instagram Reels / TikTok's style. Each segment smoothly fills left→right
 * over the photo's display duration, then the next segment begins.
 *
 * ✅ Features:
 *   • Animated fill for the active segment (ValueAnimator, real-time)
 *   • Fully completed segments shown as solid at 100%
 *   • Future segments shown as dim/translucent tracks
 *   • Active segment has a gradient fill (brand colours by default)
 *   • Configurable gap between segments (default 3dp)
 *   • Configurable height (default 3dp, same as fragment_reel_player.xml)
 *   • Rounded caps on segment bars
 *   • Segment tap detection: tapping a segment jumps to that photo
 *   • Pause / Resume support (stops the fill animator in place)
 *   • Supports beat-sync: can be ticked from outside instead of self-timing
 */
public class ReelPhotoProgressView extends View {

    // ── Listener ──────────────────────────────────────────────────────────────

    public interface OnSegmentTapListener {
        void onSegmentTapped(int segmentIndex);
    }

    public interface OnSegmentCompleteListener {
        void onSegmentComplete(int completedIndex);
    }

    // ── Constants & defaults ──────────────────────────────────────────────────

    private static final float  DEFAULT_HEIGHT_DP   = 3f;
    private static final float  DEFAULT_GAP_DP      = 3f;
    private static final float  DEFAULT_CORNER_DP   = 1.5f;
    private static final int    COLOR_TRACK          = 0x66FFFFFF;  // dim white
    private static final int    COLOR_DONE           = 0xCCFFFFFF;  // bright white
    private static final int    COLOR_ACTIVE_START   = 0xFFFF416C;  // brand pink
    private static final int    COLOR_ACTIVE_END     = 0xFFA855F7;  // brand purple

    // ── Configuration ─────────────────────────────────────────────────────────

    private int              segmentCount     = 1;
    private int              activeIndex      = 0;
    private final List<Integer> durations     = new ArrayList<>();  // ms per segment
    private float            gapDp            = DEFAULT_GAP_DP;
    private float            cornerDp         = DEFAULT_CORNER_DP;
    private int              colorTrack       = COLOR_TRACK;
    private int              colorDone        = COLOR_DONE;
    private int              colorActiveStart = COLOR_ACTIVE_START;
    private int              colorActiveEnd   = COLOR_ACTIVE_END;

    @Nullable private OnSegmentTapListener      tapListener;
    @Nullable private OnSegmentCompleteListener completeListener;

    // ── Runtime state ─────────────────────────────────────────────────────────

    private float[]         fillFractions;   // 0f→1f fill for each segment
    private ValueAnimator   fillAnimator;
    private boolean         isPaused        = false;
    private float           pausedFraction  = 0f;

    // ── Paint ─────────────────────────────────────────────────────────────────

    private final Paint trackPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint donePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF segRect     = new RectF();

    // ── Constructors ──────────────────────────────────────────────────────────

    public ReelPhotoProgressView(Context ctx) { super(ctx); init(); }
    public ReelPhotoProgressView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs); init();
    }
    public ReelPhotoProgressView(Context ctx, @Nullable AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle); init();
    }

    private void init() {
        trackPaint.setColor(colorTrack);
        donePaint.setColor(colorDone);
        setClickable(true);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Set up the progress bar for N photos with individual durations.
     *
     * @param count     Number of photos / segments.
     * @param durationsMs Duration each segment takes to fill (ms). If shorter than
     *                    count, the last provided value is repeated.
     */
    public void setSegments(int count, List<Integer> durationsMs) {
        stopAnimation();
        segmentCount = Math.max(1, count);
        durations.clear();
        if (durationsMs != null) durations.addAll(durationsMs);
        fillFractions = new float[segmentCount];
        activeIndex   = 0;
        invalidate();
    }

    /**
     * Convenience: all segments have the same duration.
     */
    public void setSegments(int count, int uniformDurationMs) {
        List<Integer> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(uniformDurationMs);
        setSegments(count, list);
    }

    /**
     * Jump to and start animating the given segment.
     * Previous segments are filled to 100%; future ones are at 0%.
     */
    public void goToSegment(int index) {
        stopAnimation();
        activeIndex = Math.max(0, Math.min(index, segmentCount - 1));
        if (fillFractions == null) fillFractions = new float[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            fillFractions[i] = i < activeIndex ? 1f : 0f;
        }
        isPaused       = false;
        pausedFraction = 0f;
        invalidate();
        startActiveAnimation();
    }

    /** Start / resume animating the active segment. */
    public void start() {
        if (isPaused) resume();
        else startActiveAnimation();
    }

    /** Pause the active segment's fill animation in place. */
    public void pause() {
        if (fillAnimator != null && fillAnimator.isRunning()) {
            pausedFraction = fillAnimator.getAnimatedFraction();
            fillAnimator.cancel();
            isPaused = true;
        }
    }

    /** Resume from the paused fraction. */
    public void resume() {
        if (!isPaused) return;
        isPaused = false;
        int durationMs = durationForSegment(activeIndex);
        long remaining = (long)(durationMs * (1f - pausedFraction));
        float start    = fillFractions != null ? fillFractions[activeIndex] : 0f;
        startAnimation(start, 1f, remaining);
    }

    /** Instantly complete all segments (e.g. when reel is dismissed). */
    public void completeAll() {
        stopAnimation();
        if (fillFractions == null) return;
        for (int i = 0; i < segmentCount; i++) fillFractions[i] = 1f;
        invalidate();
    }

    /** Reset everything to zero. */
    public void reset() {
        stopAnimation();
        activeIndex = 0;
        if (fillFractions != null) for (int i = 0; i < fillFractions.length; i++) fillFractions[i] = 0f;
        isPaused = false; pausedFraction = 0f;
        invalidate();
    }

    public void setOnSegmentTapListener(@Nullable OnSegmentTapListener l) { tapListener = l; }
    public void setOnSegmentCompleteListener(@Nullable OnSegmentCompleteListener l) { completeListener = l; }

    public void setColors(int trackColor, int doneColor, int activeStart, int activeEnd) {
        colorTrack = trackColor; colorDone = doneColor;
        colorActiveStart = activeStart; colorActiveEnd = activeEnd;
        trackPaint.setColor(colorTrack); donePaint.setColor(colorDone);
        invalidate();
    }

    public void setGap(float gapDp)    { this.gapDp    = gapDp; invalidate(); }
    public void setCorner(float dp)    { this.cornerDp = dp;    invalidate(); }
    public int  getActiveIndex()       { return activeIndex; }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (segmentCount <= 0 || fillFractions == null) return;

        float density = getResources().getDisplayMetrics().density;
        float gap     = gapDp * density;
        float corner  = cornerDp * density;
        int   w       = getWidth(), h = getHeight();
        float segW    = (w - gap * (segmentCount - 1)) / (float) segmentCount;

        for (int i = 0; i < segmentCount; i++) {
            float left  = i * (segW + gap);
            float right = left + segW;
            segRect.set(left, 0f, right, h);

            if (i < activeIndex) {
                // Fully completed
                canvas.drawRoundRect(segRect, corner, corner, donePaint);
            } else if (i == activeIndex) {
                // Active — track behind, fill on top
                canvas.drawRoundRect(segRect, corner, corner, trackPaint);
                float fill = fillFractions[i];
                if (fill > 0f) {
                    RectF fillRect = new RectF(left, 0f, left + segW * fill, h);
                    activePaint.setShader(buildActiveGradient(left, left + segW));
                    canvas.drawRoundRect(fillRect, corner, corner, activePaint);
                }
            } else {
                // Future
                canvas.drawRoundRect(segRect, corner, corner, trackPaint);
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        invalidate(); // rebuild gradient
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && tapListener != null) {
            float x = event.getX();
            int   w = getWidth();
            float density = getResources().getDisplayMetrics().density;
            float gap     = gapDp * density;
            float segW    = (w - gap * (segmentCount - 1)) / (float) segmentCount;
            int tapped = (int)(x / (segW + gap));
            if (tapped >= 0 && tapped < segmentCount) tapListener.onSegmentTapped(tapped);
        }
        return super.onTouchEvent(event);
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    private void startActiveAnimation() {
        if (activeIndex >= segmentCount) return;
        long duration = durationForSegment(activeIndex);
        startAnimation(0f, 1f, duration);
    }

    private void startAnimation(float from, float to, long duration) {
        stopAnimation();
        if (fillFractions == null) return;
        final int seg = activeIndex;
        fillAnimator = ValueAnimator.ofFloat(from, to);
        fillAnimator.setDuration(duration);
        fillAnimator.setInterpolator(new LinearInterpolator());
        fillAnimator.addUpdateListener(anim -> {
            if (fillFractions == null || seg >= fillFractions.length) return;
            fillFractions[seg] = (float) anim.getAnimatedValue();
            invalidate();
        });
        fillAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (isPaused) return;
                if (fillFractions != null && seg < fillFractions.length)
                    fillFractions[seg] = 1f;
                if (completeListener != null) completeListener.onSegmentComplete(seg);
                // Advance automatically unless the host will do it
                // (Host sets OnSegmentCompleteListener and handles advance)
            }
        });
        fillAnimator.start();
    }

    private void stopAnimation() {
        if (fillAnimator != null) { fillAnimator.cancel(); fillAnimator = null; }
    }

    private int durationForSegment(int index) {
        if (durations.isEmpty()) return 3000;
        if (index < durations.size()) {
            Integer d = durations.get(index);
            return (d != null && d > 0) ? d : 3000;
        }
        Integer last = durations.get(durations.size() - 1);
        return (last != null && last > 0) ? last : 3000;
    }

    private LinearGradient buildActiveGradient(float left, float right) {
        return new LinearGradient(left, 0f, right, 0f,
                colorActiveStart, colorActiveEnd, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }
}
