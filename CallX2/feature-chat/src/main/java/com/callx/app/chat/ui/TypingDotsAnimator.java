package com.callx.app.chat.ui;

import android.view.View;
import androidx.annotation.NonNull;

/**
 * TypingDotsAnimator — NO-OP version for performance.
 * All bounce animations removed. Dots are instantly visible/hidden.
 */
public class TypingDotsAnimator {

    private final View dot1, dot2, dot3;
    private boolean running = false;

    public TypingDotsAnimator(@NonNull View dot1, @NonNull View dot2, @NonNull View dot3) {
        this.dot1 = dot1;
        this.dot2 = dot2;
        this.dot3 = dot3;
    }

    public void start() {
        if (running) return;
        running = true;
        for (View dot : new View[]{dot1, dot2, dot3}) {
            dot.setAlpha(1f);
            dot.setTranslationY(0f);
            dot.setVisibility(View.VISIBLE);
        }
    }

    public void stop() {
        running = false;
        for (View dot : new View[]{dot1, dot2, dot3}) {
            dot.setTranslationY(0f);
            dot.setAlpha(1f);
        }
    }
}
