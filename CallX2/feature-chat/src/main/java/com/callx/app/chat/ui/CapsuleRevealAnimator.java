package com.callx.app.chat.ui;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * v817: reusable "bottom-anchored height reveal" for the input capsule — the same technique
 * ChatActivity#setupInputCapsuleAnimations uses, extracted so GroupChatActivity gets the
 * identical expand feel when the reply row (or extra text lines) grows the capsule.
 *
 * <p>The real layout is already final in one pass; we only reveal it gradually by clipping the
 * capsule's drawn region (View.setClipBounds, draw-only, no requestLayout), so the message
 * list is never re-laid-out frame by frame.</p>
 */
public final class CapsuleRevealAnimator {

    private final View capsule;
    private SpringAnimation spring;
    private boolean animating;

    private CapsuleRevealAnimator(View capsule) { this.capsule = capsule; }

    /** Attach to a capsule; call once after the view exists. */
    @NonNull
    public static CapsuleRevealAnimator attach(@NonNull View capsule) {
        CapsuleRevealAnimator a = new CapsuleRevealAnimator(capsule);
        capsule.addOnLayoutChangeListener((v, left, top, right, bottom,
                                           oldLeft, oldTop, oldRight, oldBottom) -> {
            if (a.animating) return; // our own clip updates must not re-trigger
            int newH = bottom - top;
            int oldH = oldBottom - oldTop;
            if (oldH <= 0 || newH <= 0 || oldH == newH) return;
            if (newH > oldH) a.revealTo(oldH, newH); // only grow; shrink is instant
        });
        return a;
    }

    private void revealTo(int fromHeight, int toHeight) {
        final int width = capsule.getWidth();
        if (width <= 0) return;

        if (spring != null) spring.cancel();
        animating = true;

        final Rect clip = new Rect(0, Math.max(0, toHeight - fromHeight), width, toHeight);
        capsule.setClipBounds(clip);

        FloatValueHolder holder = new FloatValueHolder(fromHeight);
        spring = new SpringAnimation(holder);
        spring.setSpring(new SpringForce(toHeight)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS);
        spring.addUpdateListener((animation, value, velocity) -> {
            int h = Math.round(value);
            clip.set(0, Math.max(0, toHeight - h), width, toHeight);
            capsule.setClipBounds(clip);
        });
        spring.addEndListener((animation, canceled, value, velocity) -> {
            animating = false;
            capsule.setClipBounds(null);
        });
        spring.start();
    }
}
