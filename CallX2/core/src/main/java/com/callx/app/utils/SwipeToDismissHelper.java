package com.callx.app.utils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * SwipeToDismissHelper — WhatsApp / iPhone style "swipe down to close" gesture.
 *
 * Attach to any full-screen media / avatar view (PhotoView, ImageView, PlayerView, etc).
 * As the user drags down:
 *   • the target view translates down + scales/fades slightly
 *   • the background dims out proportionally
 * On release:
 *   • if dragged past threshold (or fast fling down) → onDismiss() is called
 *   • otherwise the view springs back to its original position
 *
 * Usage:
 *   SwipeToDismissHelper.attach(photoView, rootBackgroundView, () -> finish());
 *
 * Note: For zoomable views like PhotoView, pass a scale-checker so the swipe
 * only triggers when the image is NOT zoomed in (zoomed-in pinch/pan should
 * not trigger dismiss). Use attach(view, background, scaleProvider, onDismiss).
 */
public final class SwipeToDismissHelper {

    /** Provide current zoom scale of the target view (1f = not zoomed). Return 1f if N/A. */
    public interface ScaleProvider {
        float getScale();
    }

    /** Called once the swipe-down dismiss gesture is confirmed. */
    public interface OnDismissListener {
        void onDismiss();
    }

    private SwipeToDismissHelper() {}

    /** Simple attach — no zoom awareness (use for plain ImageViews / avatar dialogs). */
    public static void attach(View target, View dimBackground, OnDismissListener onDismiss) {
        attach(target, dimBackground, () -> 1f, onDismiss);
    }

    /**
     * Full attach with zoom-awareness for PhotoView-based viewers.
     *
     * @param target        the view that will be dragged (image / player)
     * @param dimBackground the background view whose alpha is dimmed while dragging (can be same as target's parent background, nullable)
     * @param scaleProvider returns current zoom scale; swipe-to-dismiss is ignored if scale > 1.02f
     * @param onDismiss     called when the swipe-down gesture completes past threshold
     */
    public static void attach(View target, View dimBackground, ScaleProvider scaleProvider, OnDismissListener onDismiss) {
        Context ctx = target.getContext();
        final int touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        final float dismissThresholdPx = dpToPx(ctx, 120); // drag distance to confirm close
        final float velocityThreshold = dpToPx(ctx, 800);  // fling velocity to confirm close

        final float[] startY = {0f};
        final float[] startX = {0f};
        final boolean[] dragging = {false};
        final boolean[] verticalIntent = {false};

        GestureDetector.SimpleOnGestureListener flingListener = new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (dragging[0] && verticalIntent[0] && velocityY > velocityThreshold) {
                    animateDismiss(target, dimBackground, onDismiss);
                    return true;
                }
                return false;
            }
        };
        GestureDetector gestureDetector = new GestureDetector(ctx, flingListener);

        target.setOnTouchListener((v, event) -> {
            if (scaleProvider.getScale() > 1.02f) {
                // Zoomed in — let PhotoView handle pinch/pan, don't intercept.
                return false;
            }

            gestureDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY[0] = event.getRawY();
                    startX[0] = event.getRawX();
                    dragging[0] = false;
                    verticalIntent[0] = false;
                    return false; // allow click/tap listeners to still fire

                case MotionEvent.ACTION_MOVE: {
                    float dy = event.getRawY() - startY[0];
                    float dx = event.getRawX() - startX[0];

                    if (!dragging[0]) {
                        if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                            dragging[0] = true;
                            verticalIntent[0] = true;
                        } else if (Math.abs(dx) > touchSlop) {
                            // horizontal swipe — not our gesture
                            verticalIntent[0] = false;
                            return false;
                        } else {
                            return false;
                        }
                    }

                    if (dragging[0] && verticalIntent[0]) {
                        // Only respond to downward drag (swipe down to close)
                        float clampedDy = Math.max(0, dy);
                        target.setTranslationY(clampedDy);

                        float dragRatio = Math.min(1f, clampedDy / (dismissThresholdPx * 2.5f));
                        float scale = 1f - (0.3f * dragRatio);
                        target.setScaleX(scale);
                        target.setScaleY(scale);
                        target.setAlpha(1f - (0.6f * dragRatio));

                        if (dimBackground != null) {
                            dimBackground.setAlpha(1f - dragRatio);
                        }
                        return true;
                    }
                    return false;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (dragging[0] && verticalIntent[0]) {
                        float dy = target.getTranslationY();
                        if (dy > dismissThresholdPx) {
                            animateDismiss(target, dimBackground, onDismiss);
                        } else {
                            springBack(target, dimBackground);
                        }
                        dragging[0] = false;
                        verticalIntent[0] = false;
                        return true;
                    }
                    return false;
                }
            }
            return false;
        });
    }

    private static void springBack(View target, View dimBackground) {
        target.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(200)
            .start();
        if (dimBackground != null) {
            dimBackground.animate().alpha(1f).setDuration(200).start();
        }
    }

    private static void animateDismiss(View target, View dimBackground, OnDismissListener onDismiss) {
        float targetTranslation = target.getHeight() > 0
            ? target.getHeight()
            : dpToPx(target.getContext(), 600);

        target.animate()
            .translationY(targetTranslation)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .alpha(0f)
            .setDuration(180)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (onDismiss != null) onDismiss.onDismiss();
                }
            })
            .start();

        if (dimBackground != null) {
            dimBackground.animate().alpha(0f).setDuration(180).start();
        }
    }

    private static float dpToPx(Context ctx, float dp) {
        return dp * ctx.getResources().getDisplayMetrics().density;
    }
}
