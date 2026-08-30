package com.callx.app.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.Random;

/**
 * SoundWaveformView — single-View replacement for the old 36-child
 * LinearLayout + 36 concurrent infinite {@link ValueAnimator}s that
 * SoundDetailFragment used to drive during playback.
 *
 * WHY THIS EXISTS:
 * ─────────────────
 * Each of the old 36 per-bar animators called {@code View.setLayoutParams()}
 * on every single frame tick. setLayoutParams() on a LinearLayout child
 * always triggers a full requestLayout() -> measure/layout pass for the
 * WHOLE waveform container (not just that one bar). That's 36 layout
 * passes per frame, continuously, for as long as audio is playing —
 * a large, easily avoidable chunk of CPU/battery burn for something
 * that's purely decorative.
 *
 * This view draws all 36 bars itself in {@link #onDraw}, driven by a
 * single lightweight {@link ValueAnimator} whose only job is to call
 * {@link #invalidate()} each tick. invalidate() only schedules a redraw —
 * it never touches layout/measure. The actual per-bar heights are
 * recomputed fresh every frame from a shared clock
 * ({@link SystemClock#uptimeMillis()}), using the exact same per-bar
 * duration/target/interpolator formulas the old per-bar ValueAnimators
 * used, so the animation looks the same as before — just with zero
 * layout passes and one animator instead of 36.
 *
 * Also thermal-aware (see {@link #setForceStatic}): on a HOT device the
 * animation loop is skipped entirely (driver stopped, no invalidate loop)
 * while still showing the bars in a settled "playing" pose instead of
 * silently freezing mid-animation.
 */
public class SoundWaveformView extends View {

    private static final int BAR_COUNT = 36;

    private static final int COLOR_IDLE    = 0x44FFFFFF;
    private static final int COLOR_PLAYING = 0xFFFF3B5C;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect  = new RectF();
    private final AccelerateDecelerateInterpolator interpolator = new AccelerateDecelerateInterpolator();

    /** Idle-state bar heights (dp), seeded once per sound — mirrors the old buildStaticWaveform(). */
    private final float[] staticHeightDp = new float[BAR_COUNT];

    private ValueAnimator driver; // cheap: only pumps invalidate(), never touches layout
    private long animStartUptime;

    private boolean playing     = false;
    private boolean forceStatic = false; // true on HOT thermal — skip the animation loop

    public SoundWaveformView(Context context) { this(context, null); }
    public SoundWaveformView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public SoundWaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        seedStatic(0);
    }

    /**
     * Re-seed the idle bar pattern for a new sound — call once per sound, same
     * timing as old buildStaticWaveform(). Returns the generated heights (a
     * defensive clone) so the caller — SoundDetailFragment's per-soundId
     * LruCache — can stash them and hand them back via {@link #setStaticHeights}
     * next time the same sound's detail screen opens, instead of paying for
     * this Random-based generation again.
     */
    public float[] seedStatic(int seed) {
        Random rng = new Random(seed);
        for (int i = 0; i < BAR_COUNT; i++) {
            staticHeightDp[i] = 10 + rng.nextInt(30); // matches old (10..39)dp static range
        }
        invalidate();
        return staticHeightDp.clone();
    }

    /**
     * Apply previously-computed idle bar heights (e.g. a cache hit from
     * SoundDetailFragment's per-soundId LruCache) directly, skipping the
     * Random generation {@link #seedStatic} would otherwise redo.
     */
    public void setStaticHeights(float[] heights) {
        if (heights == null || heights.length != BAR_COUNT) return;
        System.arraycopy(heights, 0, staticHeightDp, 0, BAR_COUNT);
        invalidate();
    }

    /** Start/stop the waveform animation (mirrors old startWaveAnimation()/stopWaveAnimation()). */
    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        refreshDriverState();
    }

    /**
     * Thermal HOT override. true = never run the per-frame animation loop,
     * even while playing — bars are drawn once in a settled "playing" pose
     * and left alone until this flips back or playback stops. Safe to call
     * every time the thermal level changes; it's a no-op if unchanged.
     */
    public void setForceStatic(boolean forceStatic) {
        if (this.forceStatic == forceStatic) return;
        this.forceStatic = forceStatic;
        refreshDriverState();
    }

    private void refreshDriverState() {
        boolean shouldAnimate = playing && !forceStatic;
        if (shouldAnimate) startDriver(); else stopDriver();
        invalidate(); // one redraw to reflect the new state even if the driver just stopped
    }

    private void startDriver() {
        if (driver != null) return;
        animStartUptime = SystemClock.uptimeMillis();
        driver = ValueAnimator.ofFloat(0f, 1f);
        driver.setDuration(16); // ~1 frame; repeats forever purely to pump invalidate() at display cadence
        driver.setRepeatCount(ValueAnimator.INFINITE);
        driver.addUpdateListener(a -> invalidate());
        driver.start();
    }

    private void stopDriver() {
        if (driver != null) { driver.cancel(); driver = null; }
    }

    /** Call from the host Fragment's onDestroyView(), same lifecycle spot the old stopWaveAnimation() was called from. */
    public void release() {
        stopDriver();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopDriver();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float dp = getResources().getDisplayMetrics().density;
        float slot = w / (float) BAR_COUNT;
        float barWidth = Math.max(1f, slot * 0.4f); // ~4dp bar in a ~10dp slot, same proportions as the old 4dp bar / 3dp+3dp margin
        float cornerRadius = barWidth / 2f;
        float minHPx = 8 * dp, maxHPx = 38 * dp;

        boolean animateNow = playing && !forceStatic;
        barPaint.setColor(playing ? COLOR_PLAYING : COLOR_IDLE);

        long elapsed = animateNow ? SystemClock.uptimeMillis() - animStartUptime : 0;

        for (int i = 0; i < BAR_COUNT; i++) {
            float barHPx;
            if (animateNow) {
                // Same per-bar duration/target formulas the old per-bar ValueAnimator used.
                int durMs = 400 + (i % 5) * 80;
                float targetPx = minHPx + (maxHPx - minHPx) * (0.4f + (i % 7) * 0.08f);
                long cyclePos = elapsed % (durMs * 2L);
                float frac = cyclePos < durMs
                        ? cyclePos / (float) durMs
                        : 1f - (cyclePos - durMs) / (float) durMs; // REVERSE ping-pong, same as ValueAnimator.REVERSE
                float eased = interpolator.getInterpolation(frac);
                barHPx = minHPx + (targetPx - minHPx) * eased;
            } else if (playing) {
                // HOT thermal: playing but animation loop skipped — settle at this
                // bar's target height instead of freezing mid-swing or going idle-dim.
                barHPx = minHPx + (maxHPx - minHPx) * (0.4f + (i % 7) * 0.08f);
            } else {
                barHPx = staticHeightDp[i] * dp;
            }

            float cx  = slot * i + slot / 2f;
            float top = Math.max(0f, h - barHPx);
            barRect.set(cx - barWidth / 2f, top, cx + barWidth / 2f, h);
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint);
        }
    }
}
