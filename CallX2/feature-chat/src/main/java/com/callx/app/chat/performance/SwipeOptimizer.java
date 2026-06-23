package com.callx.app.chat.performance;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

/**
 * SwipeOptimizer — NO animation version for performance.
 * Spring-back replaced with instant reset.
 */
public final class SwipeOptimizer {

    private SwipeOptimizer() {}

    public static void setTranslationXSafe(View view, float dx) {
        view.setTranslationX(dx);
    }

    /** Instant reset instead of spring animation */
    public static void springBack(View view) {
        view.setTranslationX(0f);
    }

    public static void disableChangeAnimations(RecyclerView rv) {
        RecyclerView.ItemAnimator animator = rv.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    public static void enableHardwareLayer(View v) {
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public static void clearHardwareLayer(View v) {
        v.setLayerType(View.LAYER_TYPE_NONE, null);
    }
}
