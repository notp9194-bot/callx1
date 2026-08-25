package com.callx.app.chat.performance;

import android.os.Build;
import android.widget.EdgeEffect;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RubberBandEdgeEffectFactory — v2 (ultra-advanced pass).
 *
 * ── WHAT CHANGED FROM v1 ───────────────────────────────────────────────────
 * v1 used a linear-clamped pull + a fresh ValueAnimator(OvershootInterpolator)
 * allocated on every single release. That's functionally a rubber-band but
 * has three real costs on a fast fling-heavy chat screen:
 *   1. Linear pull has no "resistance" curve — it feels like a rigid stop,
 *      not rubber, and a fast flung finger can hit MAX_TRANSLATE_DP instantly.
 *   2. A brand-new ValueAnimator object (+ its internal AnimationHandler
 *      registration) was allocated per release — GC churn on repeated
 *      overscroll during fast scroll-to-top/bottom spam.
 *   3. Top and bottom EdgeEffect instances each blindly called
 *      rv.setTranslationY() independent of one another — a fast direction
 *      reversal (fling up, then instantly fling back down before the spring
 *      settles) could have both effects fighting over the same property.
 *
 * ── v2 FIXES ────────────────────────────────────────────────────────────
 * 1. NON-LINEAR RESISTANCE — real iOS-style rubber-band formula
 *        f(pull, dim, c) = (pull * dim * c) / (dim + c * pull)
 *    (Apple's UIScrollView constant, c = 0.55). Displacement grows fast at
 *    first then asymptotically approaches `dim` — genuine "stretchy" feel
 *    instead of a hard linear clamp, and mathematically can never exceed
 *    the cap, so no min/max branching needed either.
 * 2. ONE SHARED SpringAnimation PER RecyclerView, created lazily and reused
 *    for the lifetime of the view (stored via RubberState, attached with
 *    View.setTag()) — zero animator allocation on the hot path (every pull
 *    and every release reuse the same object; only .cancel()/.start() are
 *    called, no `new`).
 * 3. VELOCITY HAND-OFF — onAbsorb(velocity) (real fling velocity, px/s,
 *    already in the exact units DynamicAnimation expects) is fed straight
 *    into SpringAnimation.setStartVelocity(), so a fast fling into the edge
 *    produces a proportionally fast, physically correct snap-back instead
 *    of the same fixed-duration animation regardless of how hard the user
 *    flung — this is the actual "advanced scrolling" feel (matches iOS/
 *    Material 3 spring physics, not just a canned easing curve).
 * 4. SHARED STATE — top and bottom EdgeEffect instances for the same
 *    RecyclerView both read/write ONE RubberState instance instead of each
 *    fighting over translationY independently, so a fast direction reversal
 *    can never leave the view in a torn/half-animated position.
 * 5. Android 12+ (API 31) still defers entirely to the platform's own
 *    GPU-composited stretch EdgeEffect — cheapest possible correct
 *    behavior, this whole custom path only runs pre-S.
 */
public final class RubberBandEdgeEffectFactory extends RecyclerView.EdgeEffectFactory {

    // Apple's rubber-band resistance constant — higher = stiffer/less travel
    // for the same raw drag distance. 0.55 matches the familiar iOS feel.
    private static final float RESISTANCE_C = 0.55f;
    private static final float MAX_TRANSLATE_DP = 56f; // asymptotic cap (never fully reached, see formula)

    private static final float SPRING_STIFFNESS = 550f;   // snappy but not harsh
    private static final float SPRING_DAMPING_RATIO = 0.62f; // <1 = slight overshoot bounce ("rubber" snap-back)

    @Override
    protected EdgeEffect createEdgeEffect(RecyclerView recyclerView, int direction) {
        if (Build.VERSION.SDK_INT >= 31) {
            // Native GPU stretch — cheapest correct rubber-band on modern Android;
            // don't shadow it with the manual path below.
            return super.createEdgeEffect(recyclerView, direction);
        }
        RubberState state = RubberState.getOrCreate(recyclerView);
        boolean isTop = direction == RecyclerView.EdgeEffectFactory.DIRECTION_TOP;
        return new ManualRubberBandEdgeEffect(recyclerView, state, isTop);
    }

    /**
     * One instance lives per RecyclerView (shared by its top AND bottom
     * EdgeEffect), holding the single reusable SpringAnimation and the
     * current raw (pre-resistance-curve) pull distance per edge.
     */
    private static final class RubberState {
        final RecyclerView rv;
        final SpringAnimation spring;
        final float maxTranslatePx;
        float rawTopPull = 0f;    // unbounded accumulated drag distance, top edge
        float rawBottomPull = 0f; // unbounded accumulated drag distance, bottom edge
        boolean hwLayerActive = false; // v241: tracks whether rv currently has a hardware layer

        private RubberState(RecyclerView rv) {
            this.rv = rv;
            float density = rv.getContext().getResources().getDisplayMetrics().density;
            this.maxTranslatePx = MAX_TRANSLATE_DP * density;
            SpringForce force = new SpringForce(0f)
                    .setStiffness(SPRING_STIFFNESS)
                    .setDampingRatio(SPRING_DAMPING_RATIO);
            this.spring = new SpringAnimation(rv, DynamicAnimation.TRANSLATION_Y)
                    .setSpring(force);
            // v241: strip the layer the instant the spring settles back to 0 —
            // covers both the release-triggered snap-back AND any interruption
            // (e.g. a new pull cancels the spring, see pull() below, which also
            // clears the layer on its own path).
            this.spring.addEndListener((animation, canceled, value, velocity) -> {
                if (!canceled) setHardwareLayer(false);
            });
        }

        /**
         * v241: ULTRA-ADVANCED — hardware layer only while the RecyclerView is
         * actually being translated (active drag pull OR settling spring).
         * translationY forces the RecyclerView (and everything it draws — every
         * visible row's canvas content) to be re-rastered and re-composited each
         * frame; without a layer, that repaint cost is charged to EVERY frame of
         * the bounce. A hardware layer bakes one GPU texture once, then the
         * translation becomes a cheap texture-matrix transform per frame — same
         * technique the story-ring/badge canvas views don't need (they don't
         * animate continuously) but this DOES, since it drives real per-frame
         * translation for potentially hundreds of ms per bounce.
         * Toggled off the moment the spring settles so idle scroll — the vast
         * majority of the screen's lifetime — never pays the extra texture
         * memory/composite cost of a layer sitting on unnecessarily.
         */
        private void setHardwareLayer(boolean on) {
            if (on == hwLayerActive) return;
            hwLayerActive = on;
            rv.setLayerType(on ? android.view.View.LAYER_TYPE_HARDWARE
                               : android.view.View.LAYER_TYPE_NONE, null);
        }

        static RubberState getOrCreate(RecyclerView rv) {
            Object existing = rv.getTag();
            if (existing instanceof RubberState) return (RubberState) existing;
            RubberState fresh = new RubberState(rv);
            rv.setTag(fresh);
            return fresh;
        }

        /** iOS-style diminishing-resistance curve — see class javadoc. */
        private float resistanceCurve(float rawPull) {
            float dim = maxTranslatePx;
            return (rawPull * dim * RESISTANCE_C) / (dim + RESISTANCE_C * rawPull);
        }

        void pull(boolean isTop, float rawDelta) {
            setHardwareLayer(true); // v241: drag itself also translates every frame
            spring.cancel(); // an active drag always wins over a settling spring
            if (isTop) rawTopPull = Math.max(0f, rawTopPull + rawDelta);
            else rawBottomPull = Math.max(0f, rawBottomPull + rawDelta);
            float topDisp = resistanceCurve(rawTopPull);
            float bottomDisp = resistanceCurve(rawBottomPull);
            // Top pull pushes content down (+), bottom pull pushes content up (-).
            rv.setTranslationY(topDisp - bottomDisp);
        }

        void release(boolean isTop, float startVelocityPxPerSec) {
            if (isTop) rawTopPull = 0f;
            else rawBottomPull = 0f;
            spring.setStartVelocity(startVelocityPxPerSec);
            spring.animateToFinalPosition(0f);
        }
    }

    /** Pre-S fallback EdgeEffect: delegates all displacement math to the shared RubberState. */
    private static final class ManualRubberBandEdgeEffect extends EdgeEffect {
        private final RubberState state;
        private final boolean isTop;

        ManualRubberBandEdgeEffect(RecyclerView rv, RubberState state, boolean isTop) {
            super(rv.getContext());
            this.state = state;
            this.isTop = isTop;
        }

        @Override
        public void onPull(float deltaDistance) {
            super.onPull(deltaDistance);
            state.pull(isTop, deltaDistance * state.rv.getHeight());
        }

        @Override
        public void onPull(float deltaDistance, float displacement) {
            super.onPull(deltaDistance, displacement);
            state.pull(isTop, deltaDistance * state.rv.getHeight());
        }

        @Override
        public void onRelease() {
            super.onRelease();
            state.release(isTop, 0f);
        }

        @Override
        public void onAbsorb(int velocity) {
            super.onAbsorb(velocity);
            // velocity is already px/s in the direction of travel — hand it straight
            // to the spring so a hard fling produces a proportionally fast snap-back.
            float signedVelocity = isTop ? velocity : -velocity;
            state.release(isTop, signedVelocity);
        }
    }
}
