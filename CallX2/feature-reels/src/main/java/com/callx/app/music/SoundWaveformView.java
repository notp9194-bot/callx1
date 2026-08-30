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

import com.callx.app.reels.R;

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

    // Premium refine: idle bars dimmer (0x44→0x30). The playing color is
    // resolved at runtime from @color/brand_primary (see colorPlaying
    // field below) instead of a hardcoded literal — this constant used to
    // be a hardcoded indigo (#5B5BF6) which silently drifted out of sync
    // with the rest of the screen once the seekbar/chips/follow button
    // were switched to reference @color/brand_primary directly, which
    // resolves to the app's real green (#4CAF50, defined in the app/
    // module). Resolving the same resource here keeps the waveform's
    // "playing" color locked to whatever brand_primary actually is.
    private static final int COLOR_IDLE = 0x30FFFFFF;
    private final int colorPlaying;

    /**
     * PERF (ULTRA): per-bar animation constants, precomputed ONCE.
     *
     * onDraw() below used to recompute {@code durMs} and {@code targetPx}
     * for all 36 bars on every single frame — a modulo + a multiply-add per
     * bar per bar per tick, forever, for as long as the animation runs.
     * Both formulas depend ONLY on the bar index {@code i}, never on the
     * elapsed time or anything else that changes frame to frame, so
     * there's nothing to gain from redoing that arithmetic every tick.
     *
     * - {@link #BAR_DUR_MS_PER_INDEX} / {@link #BAR_CYCLE_MS_PER_INDEX}: pure
     *   functions of {@code i} — identical for every SoundWaveformView
     *   instance in the process, so these are `static final`, computed once
     *   per class-load, same lifetime/sharing rationale as
     *   SoundDetailFragment's WAVEFORM_CACHE.
     * - {@link #targetPxByIndex}: the target-height FRACTION
     *   (0.4f + (i % 7) * 0.08f) is likewise a pure function of {@code i}
     *   (see {@link #BAR_TARGET_FRACTION}, also static/shared) — but the
     *   final pixel value also depends on minHPx/maxHPx, which come from
     *   this device's display density. Density is effectively fixed for a
     *   given View instance (it can't change frame-to-frame), so this one
     *   is cached per-instance, lazily, the first time onDraw() runs — see
     *   {@link #ensureTargetPxCached}.
     */
    private static final int[]   BAR_DUR_MS_PER_INDEX   = new int[BAR_COUNT];
    private static final long[]  BAR_CYCLE_MS_PER_INDEX = new long[BAR_COUNT];
    private static final float[] BAR_TARGET_FRACTION    = new float[BAR_COUNT];
    static {
        for (int i = 0; i < BAR_COUNT; i++) {
            int durMs = 400 + (i % 5) * 80;
            BAR_DUR_MS_PER_INDEX[i]   = durMs;
            BAR_CYCLE_MS_PER_INDEX[i] = durMs * 2L;
            BAR_TARGET_FRACTION[i]    = 0.4f + (i % 7) * 0.08f;
        }
    }

    /** Lazily-built, per-instance cache of each bar's target height in px —
     *  see {@link #ensureTargetPxCached} for why this can't be static like
     *  the arrays above. */
    private final float[] targetPxByIndex = new float[BAR_COUNT];
    private boolean targetPxCached = false;
    private float cachedDp = -1f; // density this cache was built for; rebuild if it ever changes

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect  = new RectF();
    private final AccelerateDecelerateInterpolator interpolator = new AccelerateDecelerateInterpolator();

    /** Idle-state bar heights (dp), seeded once per sound — mirrors the old buildStaticWaveform(). */
    private final float[] staticHeightDp = new float[BAR_COUNT];

    private ValueAnimator driver; // cheap: only pumps invalidate(), never touches layout
    private long animStartUptime;

    private boolean playing     = false;
    private boolean forceStatic = false; // true on HOT thermal — skip the animation loop

    /**
     * PERF (opt-in, ULTRA): GPU-composited hardware layer for the animation
     * window only. OFF by default — see {@link #setHardwareLayerEnabled}.
     *
     * Why opt-in and not just always-on: onDraw() below recomputes ALL 36
     * bar heights fresh every single invalidate() (that's the whole point
     * of this view vs. the old per-bar ValueAnimators — see class doc).
     * That means there's no unchanged content for a hardware layer to
     * cache and reuse across frames the way it would for, say, a view
     * that's only translating/fading — every tick still redraws every bar
     * into the layer's backing texture. So the benefit here is narrower
     * (mainly: draw calls happen on RenderThread instead of the UI
     * thread), and it isn't free: a hardware layer pins a GPU-backed
     * bitmap the size of this view for as long as it's active, and
     * toggling layer type is itself not free. Flip this on only after a
     * profiler run (Android Studio's GPU rendering profile / Layout
     * Inspector / systrace) actually shows this view's onDraw() or the
     * driver's invalidate() cadence as a jank source during playback —
     * don't enable it speculatively.
     */
    private boolean hardwareLayerEnabled = false;

    public SoundWaveformView(Context context) { this(context, null); }
    public SoundWaveformView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public SoundWaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int resolved;
        try {
            resolved = context.getResources().getColor(R.color.brand_primary, null);
        } catch (Exception e) {
            resolved = 0xFF4CAF50; // fallback, matches app/'s brand_primary in case the resource can't resolve
        }
        colorPlaying = resolved;
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

    /**
     * Turn the hardware layer optimization on/off — call this from the host
     * once (e.g. wired to a remote/debug flag or a profiler-driven build
     * config), NOT per-frame. Safe to call any time; takes effect
     * immediately if currently animating, otherwise on the next time
     * animation starts. See {@link #hardwareLayerEnabled}'s doc for why
     * this defaults to false and should only be flipped on with profiler
     * evidence in hand.
     */
    public void setHardwareLayerEnabled(boolean enabled) {
        if (this.hardwareLayerEnabled == enabled) return;
        this.hardwareLayerEnabled = enabled;
        applyLayerTypeForCurrentState();
    }

    private void refreshDriverState() {
        boolean shouldAnimate = playing && !forceStatic;
        if (shouldAnimate) startDriver(); else stopDriver();
        applyLayerTypeForCurrentState();
        invalidate(); // one redraw to reflect the new state even if the driver just stopped
    }

    /**
     * Layer type only tracks the ANIMATING window, never left on while
     * idle/static — a static waveform is drawn once and left alone (no
     * invalidate loop), so a hardware layer buys it nothing while
     * permanently costing the backing texture's GPU memory. Also reset to
     * NONE on stop/detach (see stopDriver()/onDetachedFromWindow()) so
     * backgrounding playback or leaving the screen releases it instead of
     * holding a GPU-backed bitmap for a view that's no longer animating.
     */
    private void applyLayerTypeForCurrentState() {
        boolean animateNow = playing && !forceStatic;
        int wanted = (hardwareLayerEnabled && animateNow) ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
        if (getLayerType() != wanted) setLayerType(wanted, null);
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
        // Belt-and-braces: refreshDriverState() already calls
        // applyLayerTypeForCurrentState() after this, but stopDriver() is
        // also reached directly from onDetachedFromWindow()/release()
        // below, which don't — so drop the layer here too rather than
        // leaving a GPU-backed bitmap pinned on a view that just stopped.
        if (getLayerType() != LAYER_TYPE_NONE) setLayerType(LAYER_TYPE_NONE, null);
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

    /**
     * Builds/rebuilds {@link #targetPxByIndex} from {@link #BAR_TARGET_FRACTION}
     * and the current minHPx/maxHPx. Cheap (36 multiply-adds) and only runs
     * once per density — a no-op fast-path check on every other call.
     */
    private void ensureTargetPxCached(float dp, float minHPx, float maxHPx) {
        if (targetPxCached && dp == cachedDp) return;
        for (int i = 0; i < BAR_COUNT; i++) {
            targetPxByIndex[i] = minHPx + (maxHPx - minHPx) * BAR_TARGET_FRACTION[i];
        }
        cachedDp = dp;
        targetPxCached = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float dp = getResources().getDisplayMetrics().density;
        float slot = w / (float) BAR_COUNT;
        // Premium refine: thinner bar-to-slot ratio (0.4→0.28) for a
        // minimal look, and maxHPx brought down to actually fit inside
        // this view's own height (was 38dp against a 26dp-tall container —
        // bars were clipping) instead of just looking "less bold".
        float barWidth = Math.max(1f, slot * 0.28f);
        float cornerRadius = barWidth / 2f;
        float minHPx = 6 * dp, maxHPx = 20 * dp;
        ensureTargetPxCached(dp, minHPx, maxHPx);

        boolean animateNow = playing && !forceStatic;
        barPaint.setColor(playing ? colorPlaying : COLOR_IDLE);

        long elapsed = animateNow ? SystemClock.uptimeMillis() - animStartUptime : 0;

        for (int i = 0; i < BAR_COUNT; i++) {
            float barHPx;
            if (animateNow) {
                // durMs/cycleMs/targetPx are all precomputed — see the
                // BAR_*_PER_INDEX arrays and ensureTargetPxCached() above.
                // Only the elapsed-time-dependent part (cyclePos/frac/eased)
                // still runs fresh every frame, since that's the one part
                // that actually changes tick to tick.
                long cyclePos = elapsed % BAR_CYCLE_MS_PER_INDEX[i];
                int  durMs    = BAR_DUR_MS_PER_INDEX[i];
                float frac = cyclePos < durMs
                        ? cyclePos / (float) durMs
                        : 1f - (cyclePos - durMs) / (float) durMs; // REVERSE ping-pong, same as ValueAnimator.REVERSE
                float eased = interpolator.getInterpolation(frac);
                barHPx = minHPx + (targetPxByIndex[i] - minHPx) * eased;
            } else if (playing) {
                // HOT thermal: playing but animation loop skipped — settle at this
                // bar's target height instead of freezing mid-swing or going idle-dim.
                barHPx = targetPxByIndex[i];
            } else {
                // Clamp: staticHeightDp values (10..39dp, seeded in
                // seedStatic()) predate the thinner maxHPx above and can
                // exceed this view's own height — cap so idle bars never
                // clip past the container.
                barHPx = Math.min(staticHeightDp[i] * dp, maxHPx);
            }

            float cx  = slot * i + slot / 2f;
            float top = Math.max(0f, h - barHPx);
            barRect.set(cx - barWidth / 2f, top, cx + barWidth / 2f, h);
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint);
        }
    }
}
