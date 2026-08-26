package com.callx.app.feed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.EdgeEffect;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

/**
 * ReelLiquidScrollEffect (v2 — "ultra advanced") — "paani behta hai"
 * water-flowing feel for the main Reels tab feed (ReelsFragment#vpReels).
 *
 * Still deliberately kept OFF the per-frame drag-between-pages path — see
 * the PERF note on ReelsFragment#configurePagerForVideoScroll(): a
 * scale/alpha PageTransformer on full-screen video pages forces the system
 * to composite two full video surfaces every scroll frame, which this class
 * avoids. ViewPager2 keeps its native, cheap page-slide during an active
 * drag. Everything below only runs (a) at the feed's edges, during an
 * overscroll gesture, or (b) once, briefly, after a page has already
 * finished settling — never across the whole swipe.
 *
 * v2 advances over v1:
 *  - Dual-layer sine-based wave (not a single quad bulge) for a more
 *    realistic liquid silhouette, with a lighter "wet-shine" crest stroke.
 *  - Velocity-reactive: a hard fling into the edge produces a bigger initial
 *    bulge AND a decaying "slosh" oscillation on top of the release ease,
 *    instead of a flat exponential fade — mimics water actually sloshing
 *    back after an impact rather than just draining away.
 *  - Haptic tick (once per gesture) when the pull crosses a "you've hit the
 *    end" threshold, like the edge has some resistance.
 *  - settleRipple() now takes the feed's already-computed scroll velocity
 *    (ReelsFragment already derives this for the predictive preloader — see
 *    lastScrollVelocity) and scales the settle wobble + adds a tiny
 *    rotation "tilt" from it, so a fast flick settles with visibly more
 *    liquid inertia than a slow, deliberate swipe.
 *  - Thermal-gated exactly like the feed's existing preloading logic
 *    (ReelThermalManager): call {@link #setEnabled(boolean)} from
 *    ReelsFragment#onThermalChanged() so the whole effect turns itself off
 *    under HOT instead of spending extra GPU/CPU on a device that's already
 *    throttling — same philosophy as cancelling byte preloads there.
 */
public final class ReelLiquidScrollEffect {

    private ReelLiquidScrollEffect() {}

    // Thermal gate — mirrors ReelThermalManager's HOT cutoff used elsewhere
    // in ReelsFragment for preloading. Volatile: flipped from the thermal
    // change listener, read from the draw()/settle paths.
    private static volatile boolean enabled = true;

    public static void setEnabled(boolean e) {
        enabled = e;
    }

    /**
     * Wires the custom water-ripple overscroll effect onto the ViewPager2's
     * inner RecyclerView. Requires overScrollMode != OVER_SCROLL_NEVER on
     * that RecyclerView, since edge effects never fire otherwise.
     */
    public static void applyWaterEdgeEffect(@NonNull RecyclerView rv) {
        rv.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        rv.setEdgeEffectFactory(new RecyclerView.EdgeEffectFactory() {
            @NonNull
            @Override
            protected EdgeEffect createEdgeEffect(@NonNull RecyclerView recyclerView, int direction) {
                boolean top = direction == DIRECTION_TOP;
                return new WaterEdgeEffect(recyclerView, top);
            }
        });
    }

    /**
     * Fires once when a reel page finishes snapping into place (call from
     * onPageScrollStateChanged() when state == ViewPager2.SCROLL_STATE_IDLE).
     * Droplet-settle wobble whose amplitude and tilt scale with how fast the
     * user was flicking — a slow deliberate swipe barely wobbles, a hard
     * flick settles with a visibly bigger, longer "slosh".
     *
     * @param page           the settled page's root view
     * @param scrollVelocity px/ms, as already tracked by ReelsFragment's
     *                       page-change callback (0 if unknown/unavailable)
     */
    public static void settleRipple(@NonNull View page, float scrollVelocity) {
        if (!enabled) return;
        page.animate().cancel();

        // Clamp + normalize velocity into a 0..1 "how hard did it land" factor.
        float velocityFactor = Math.max(0f, Math.min(1f, scrollVelocity / 6f));
        float stretchAmp = 0.012f + 0.022f * velocityFactor;   // 1.2%..3.4% scale stretch
        float tiltDeg    = 0.4f  + 1.6f  * velocityFactor;     // 0.4°..2.0° micro-tilt
        float dipPx      = page.getResources().getDisplayMetrics().density * (1f + 3f * velocityFactor);

        // Start slightly stretched/tilted/dipped — as if the page just
        // "landed" — then let the springs pull everything back to rest;
        // that pull-back IS the settle/flow sensation.
        page.setScaleX(1f - stretchAmp * 0.5f);
        page.setScaleY(1f + stretchAmp);
        page.setRotation(velocityFactor > 0.02f ? tiltDeg : 0f);
        page.setTranslationY(dipPx);

        float dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                - (0.08f * velocityFactor); // harder flick → slightly bouncier settle

        springTo(page, SpringAnimation.SCALE_X, 1f, dampingRatio);
        springTo(page, SpringAnimation.SCALE_Y, 1f, dampingRatio);
        springTo(page, SpringAnimation.ROTATION, 0f, dampingRatio);
        springTo(page, SpringAnimation.TRANSLATION_Y, 0f, dampingRatio);
    }

    private static void springTo(View view, DynamicAnimation.ViewProperty property,
                                  float target, float dampingRatio) {
        SpringAnimation anim = new SpringAnimation(view, property, target);
        anim.setSpring(new SpringForce(target)
                .setDampingRatio(dampingRatio)
                .setStiffness(SpringForce.STIFFNESS_LOW));
        anim.start();
    }

    /** Custom translucent, velocity-reactive "water surface" overscroll glow. */
    private static class WaterEdgeEffect extends EdgeEffect {
        private final boolean topEdge;
        private final View hostView; // for haptic feedback only
        private final Paint fillPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int width, height;

        private float pull = 0f;           // eased 0..1 base stretch
        private float slosh = 0f;          // decaying oscillation energy on top of pull
        private float sloshPhase = 0f;
        private boolean hapticFired = false;

        WaterEdgeEffect(RecyclerView host, boolean topEdge) {
            super(host.getContext());
            this.hostView = host;
            this.topEdge = topEdge;
            fillPaint.setStyle(Paint.Style.FILL);
            shinePaint.setStyle(Paint.Style.STROKE);
            shinePaint.setStrokeWidth(2f * host.getResources().getDisplayMetrics().density);
        }

        @Override
        public void setSize(int width, int height) {
            super.setSize(width, height);
            this.width = width;
            this.height = height;
        }

        @Override
        public void onPull(float deltaDistance) {
            super.onPull(deltaDistance);
            addPull(deltaDistance);
        }

        @Override
        public void onPull(float deltaDistance, float displacement) {
            super.onPull(deltaDistance, displacement);
            addPull(deltaDistance);
        }

        private void addPull(float deltaDistance) {
            pull = Math.min(1f, pull + deltaDistance);
            if (!hapticFired && pull > 0.6f) {
                hapticFired = true;
                hostView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        }

        @Override
        public void onRelease() {
            super.onRelease();
            hapticFired = false;
            // Convert whatever pull had built up into slosh energy so the
            // release reads as a wave rocking back, not just fading flat.
            slosh = Math.max(slosh, pull * 0.6f);
            sloshPhase = 0f;
        }

        @Override
        public void onAbsorb(int velocity) {
            super.onAbsorb(velocity);
            hapticFired = false;
            // Fling straight into the edge (fast swipe past the last reel) —
            // bigger initial bump AND a stronger slosh, scaled by fling speed.
            float velocityFactor = Math.max(0f, Math.min(1f, velocity / 8000f));
            pull  = Math.min(1f, pull + 0.30f + 0.25f * velocityFactor);
            slosh = Math.min(1f, slosh + 0.45f + 0.35f * velocityFactor);
            sloshPhase = 0f;
        }

        @Override
        public boolean draw(@NonNull Canvas canvas) {
            if (!enabled || width <= 0 || height <= 0) return false;
            if (pull <= 0.004f && slosh <= 0.004f) return false;

            // Ease the base stretch back toward 0, and decay the slosh
            // oscillation independently — this combination is what gives the
            // "wave rocking to rest" feel instead of a flat fade.
            boolean stillAnimating = pull > 0.008f || slosh > 0.008f;
            pull  *= 0.90f;
            slosh *= 0.86f;
            sloshPhase += 0.55f; // radians/frame — oscillation speed
            if (pull  <= 0.008f) pull  = 0f;
            if (slosh <= 0.008f) slosh = 0f;

            float maxStretch = height * 0.22f;
            float oscillation = (float) Math.sin(sloshPhase) * slosh * height * 0.06f;
            float stretch = Math.max(0f, maxStretch * pull + oscillation);
            if (stretch <= 0f) return stillAnimating;

            int save = canvas.save();
            Path wave = new Path();
            Path crest = new Path(); // thin highlight along the top of the bulge — "wet shine"

            if (topEdge) {
                wave.moveTo(0, 0);
                wave.lineTo(0, stretch);
                // Dual control points instead of one → a gentler double-hump
                // silhouette rather than a single perfect arc.
                wave.cubicTo(width * 0.28f, stretch * 2.5f, width * 0.72f, stretch * 1.7f, width, stretch);
                wave.lineTo(width, 0);
                wave.close();

                crest.moveTo(0, stretch);
                crest.cubicTo(width * 0.28f, stretch * 2.5f, width * 0.72f, stretch * 1.7f, width, stretch);

                fillPaint.setShader(new LinearGradient(
                        0, 0, 0, stretch * 2.5f,
                        0x5A5B5BF6, 0x005B5BF6, Shader.TileMode.CLAMP));
            } else {
                canvas.translate(0, height - stretch * 2.5f);
                wave.moveTo(0, stretch * 2.5f);
                wave.lineTo(0, stretch);
                wave.cubicTo(width * 0.28f, -stretch * 0.7f, width * 0.72f, -stretch * 0.1f, width, stretch);
                wave.lineTo(width, stretch * 2.5f);
                wave.close();

                crest.moveTo(0, stretch);
                crest.cubicTo(width * 0.28f, -stretch * 0.7f, width * 0.72f, -stretch * 0.1f, width, stretch);

                fillPaint.setShader(new LinearGradient(
                        0, stretch, 0, stretch * 2.5f,
                        0x005B5BF6, 0x5A5B5BF6, Shader.TileMode.CLAMP));
            }

            canvas.drawPath(wave, fillPaint);

            int shineAlpha = (int) (110 * Math.min(1f, pull + slosh));
            shinePaint.setColor((shineAlpha << 24) | 0xFFFFFF);
            canvas.drawPath(crest, shinePaint);

            canvas.restoreToCount(save);
            return stillAnimating;
        }

        @Override
        public boolean isFinished() {
            return pull <= 0f && slosh <= 0f;
        }
    }
}
