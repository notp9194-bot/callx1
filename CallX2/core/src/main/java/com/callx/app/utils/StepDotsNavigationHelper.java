package com.callx.app.utils;

import android.view.View;
import android.widget.TextView;

/**
 * StepDotsNavigationHelper — shared tap-to-navigate wiring for the numbered
 * step-dot stepper used across the app's multi-step wizard screens (Reel
 * Editor, Reel Photo Editor, Reel Upload, Add Status, ...).
 *
 * Rule (same on every screen that uses it): tapping a step dot for a step
 * ALREADY REACHED (index <= the screen's current step) jumps straight there.
 * Tapping a dot AHEAD of the current step does nothing — moving forward only
 * happens through that screen's own "Next" button, which may run its own
 * per-step validation (e.g. Reel Upload's "pick a video first" on step 0).
 *
 * This is the single place that rule lives — every stepper screen wires its
 * dot views through {@link #bindStepDots} instead of each re-implementing the
 * same click-guard.
 */
public final class StepDotsNavigationHelper {

    private StepDotsNavigationHelper() {}

    /** Tells the helper how to read and change the screen's current step. */
    public interface StepNavigator {
        /** The step index (0-based) the screen is currently showing. */
        int getCurrentStep();
        /** Called only for taps on step <= getCurrentStep(); moves the screen there. */
        void goToStep(int step);
    }

    /**
     * Wires each view in {@code dots} so tapping it jumps back to that step —
     * only if that step has already been reached. Taps on a dot ahead of the
     * current step are ignored. Safe to call with null/empty arrays or null
     * entries (e.g. a step whose dot view doesn't exist in a given layout).
     */
    public static void bindStepDots(TextView[] dots, StepNavigator navigator) {
        if (dots == null || navigator == null) return;
        for (int i = 0; i < dots.length; i++) {
            TextView dot = dots[i];
            if (dot == null) continue;
            final int step = i;
            dot.setOnClickListener((View v) -> {
                if (step <= navigator.getCurrentStep()) {
                    navigator.goToStep(step);
                }
                // step > getCurrentStep() → ignored; only "Next" may advance.
            });
        }
    }
}
