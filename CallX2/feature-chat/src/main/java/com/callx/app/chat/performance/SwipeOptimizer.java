package com.callx.app.chat.performance;

import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

/**
 * SwipeOptimizer — Performance utilities for lag-free swipe-to-reply.
 *
 * Key principles:
 *   • ONLY translationX during swipe — never requestLayout()
 *   • Disable RecyclerView change animations (prevents flicker)
 *   • Spring-back uses DynamicAnimation (hardware-accelerated)
 *   • No object allocation in the critical swipe path
 */
public final class SwipeOptimizer {

    private SwipeOptimizer() {}

    /**
     * Set translationX safely — hardware layers enabled for duration of animation.
     * Never call invalidate() or requestLayout().
     */
    public static void setTranslationXSafe(View view, float dx) {
        view.setTranslationX(dx);
    }

    /**
     * Spring-back animation: translationX → 0 with MEDIUM stiffness.
     * Uses DynamicAnimation for smooth, physics-based return.
     */
    public static void springBack(View view) {
        float currentTx = view.getTranslationX();
        if (Math.abs(currentTx) < 0.5f) {
            view.setTranslationX(0f);
            return;
        }
        SpringAnimation spring = new SpringAnimation(view, DynamicAnimation.TRANSLATION_X, 0f);
        spring.getSpring()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(0.7f);
        spring.setStartVelocity(-currentTx * 2f);
        spring.start();
    }

    /**
     * Disable default RecyclerView item animator change animations.
     * Must be called once on RecyclerView setup.
     * Prevents flicker during swipe and list updates.
     */
    public static void disableChangeAnimations(RecyclerView rv) {
        RecyclerView.ItemAnimator animator = rv.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    /**
     * Enable hardware layer on a view for the duration of a gesture.
     * Call at gesture start; call clearHardwareLayer() at gesture end.
     */
    public static void enableHardwareLayer(View v) {
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public static void clearHardwareLayer(View v) {
        v.setLayerType(View.LAYER_TYPE_NONE, null);
    }
}
