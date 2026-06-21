package com.callx.app.chat.ui;

import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;

/**
 * TypingDotsAnimator — drives the staggered "bounce" loop for the 3 dots
 * inside ll_typing_strip (dot_typing_1/2/3), shared by both the 1:1
 * (ChatPresenceController) and group (GroupTypingController) typing strips.
 *
 * Each dot bounces up ~3dp and back with a 120ms stagger between them,
 * looping continuously while typing is active — the familiar WhatsApp/
 * iMessage "..." breathing rhythm. start()/stop() are idempotent and safe
 * to call from Firebase listener callbacks on every tick without leaking
 * duplicate loops.
 */
public class TypingDotsAnimator {

    private static final long BOUNCE_DURATION_MS = 360L;
    private static final long DOT_STAGGER_MS = 120L;
    private static final long LOOP_GAP_MS = 280L; // pause before the cycle repeats
    private static final float BOUNCE_TRANSLATION_DP = 3f;

    private final View dot1, dot2, dot3;
    private final float density;
    private boolean running = false;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable loopRunnable;

    public TypingDotsAnimator(@NonNull View dot1, @NonNull View dot2, @NonNull View dot3) {
        this.dot1 = dot1;
        this.dot2 = dot2;
        this.dot3 = dot3;
        this.density = dot1.getResources().getDisplayMetrics().density;
    }

    /** Begins the looping bounce. No-op if already running. */
    public void start() {
        if (running) return;
        running = true;
        scheduleCycle();
    }

    /** Stops the loop and resets all 3 dots to their resting position. */
    public void stop() {
        running = false;
        if (loopRunnable != null) {
            handler.removeCallbacks(loopRunnable);
            loopRunnable = null;
        }
        for (View dot : new View[]{dot1, dot2, dot3}) {
            dot.animate().cancel();
            dot.setTranslationY(0f);
            dot.setAlpha(1f);
        }
    }

    private void scheduleCycle() {
        loopRunnable = () -> {
            if (!running) return;
            bounce(dot1, 0);
            bounce(dot2, DOT_STAGGER_MS);
            bounce(dot3, DOT_STAGGER_MS * 2);
            long cycleLength = (DOT_STAGGER_MS * 2) + (BOUNCE_DURATION_MS * 2) + LOOP_GAP_MS;
            handler.postDelayed(loopRunnable, cycleLength);
        };
        handler.post(loopRunnable);
    }

    private void bounce(View dot, long delay) {
        dot.animate().cancel();
        float up = -BOUNCE_TRANSLATION_DP * density;
        dot.animate()
                .translationY(up)
                .alpha(1f)
                .setStartDelay(delay)
                .setDuration(BOUNCE_DURATION_MS / 2)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> dot.animate()
                        .translationY(0f)
                        .setDuration(BOUNCE_DURATION_MS / 2)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start())
                .start();
    }
}
