package com.callx.app.utils;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.List;

/**
 * Small reusable helper that gives a chat screen a clean, fully edge-to-edge
 * look: BOTH the status bar and the navigation bar are hidden completely
 * (no icons, no bar — just clean space), same as the reference screenshot.
 * User can still swipe from an edge to reveal a bar temporarily.
 *
 * Used by chat screens (ChatActivity / GroupChatActivity) so opening a
 * conversation feels full-screen and clean top-to-bottom.
 */
public final class ImmersiveModeUtils {

    private ImmersiveModeUtils() {}

    /** Fully hides status bar + nav bar for this activity's window. */
    public static void enterImmersive(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        Window window = activity.getWindow();

        WindowCompat.setDecorFitsSystemWindows(window, false);

        // Without this, many devices (esp. ones with a notch/camera-cutout —
        // MIUI/Xiaomi in particular) keep reserving the cutout-safe area and
        // paint it solid black even after the status bar is "hidden", instead
        // of letting our content draw all the way to the true top edge.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                            ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
        }

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.statusBars()
                    | WindowInsetsCompat.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        // Legacy flags too — some OEM skins (MIUI, ColorOS, etc.) don't
        // reliably honor WindowInsetsController alone; setting both is the
        // standard belt-and-suspenders approach for sticky immersive mode.
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    /**
     * Pads {@code topBar} (the chat header) by the status-bar inset height —
     * this is 0 once the bar is fully hidden, but keeps the header correctly
     * placed on the brief moments a user swipes the status bar back into view.
     * Safe to call once in onCreate; re-applies automatically on every inset
     * change (rotation, cutout, etc).
     */
    public static void applyTopInsetPadding(View topBar) {
        if (topBar == null) return;
        final int baseLeft   = topBar.getPaddingLeft();
        final int baseTop    = topBar.getPaddingTop();
        final int baseRight  = topBar.getPaddingRight();
        final int baseBottom = topBar.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPadding(baseLeft, baseTop + bars.top, baseRight, baseBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(topBar);
    }

    /**
     * Manually replicates windowSoftInputMode="adjustResize" for a screen
     * that has gone edge-to-edge (setDecorFitsSystemWindows(false) stops the
     * system from auto-resizing the window for the keyboard, so the message
     * input row would otherwise sit underneath the IME). Pads {@code root}
     * by the keyboard height whenever it opens/closes, and by nothing when
     * it's hidden — the nav bar itself stays hidden/overlaying, same as Reels.
     */
    public static void applyImeBottomPadding(View root) {
        if (root == null) return;
        final int baseLeft   = root.getPaddingLeft();
        final int baseTop    = root.getPaddingTop();
        final int baseRight  = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(baseLeft, baseTop, baseRight, baseBottom + imeBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /**
     * Same job as {@link #applyImeBottomPadding(View)} — pads {@code root}
     * by the keyboard height so the floating input capsule never sits
     * underneath the IME — but with a spring/overshoot bounce instead of
     * an instant jump (Feature: capsule bottom-margin spring animation on
     * keyboard open/close).
     *
     * We deliberately DON'T track the real per-frame WindowInsetsAnimation
     * curve here: on API 30+ the system already delivers a smooth (but
     * plain, non-bouncy) animation, which is what {@link #applyImeBottomPadding}
     * gives you. Instead, {@link WindowInsetsAnimationCompat.Callback#onStart}
     * is used purely as a trigger — it fires exactly once per show/hide
     * gesture and hands us the final target inset via its bounds — and we
     * drive the padding ourselves with a single {@link ValueAnimator} using
     * {@link OvershootInterpolator} for the bounce. This also works on
     * pre-R devices, where the compat shim still calls onStart once (with
     * no real per-frame animation to fight over).
     */
    public static void applyImeBottomPaddingAnimated(View root) {
        if (root == null) return;
        final int baseLeft   = root.getPaddingLeft();
        final int baseTop    = root.getPaddingTop();
        final int baseRight  = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        final long SPRING_DURATION_MS = 380L;

        final ValueAnimator[] activeAnim = new ValueAnimator[1];

        ViewCompat.setWindowInsetsAnimationCallback(root,
                new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {

                    @NonNull
                    @Override
                    public WindowInsetsAnimationCompat.BoundsCompat onStart(
                            @NonNull WindowInsetsAnimationCompat animation,
                            @NonNull WindowInsetsAnimationCompat.BoundsCompat bounds) {

                        if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) == 0) {
                            return bounds;
                        }

                        int startPadding  = root.getPaddingBottom();
                        // bounds.getUpperBound() is NOT "the end state" — it's
                        // always the larger (keyboard-open) inset regardless of
                        // whether the keyboard is opening or closing. Using it
                        // directly meant a CLOSING keyboard would spring the
                        // capsule's padding back up to the open height instead
                        // of down to baseline. By the time onStart fires, the
                        // window has already recalculated insets for the real
                        // target state, so read the actual target from the
                        // view's current window insets instead.
                        WindowInsetsCompat current = ViewCompat.getRootWindowInsets(root);
                        int targetImeBottom = current != null
                                ? current.getInsets(WindowInsetsCompat.Type.ime()).bottom
                                : bounds.getUpperBound().bottom;
                        int endPadding    = baseBottom + targetImeBottom;

                        if (activeAnim[0] != null && activeAnim[0].isRunning()) activeAnim[0].cancel();

                        ValueAnimator anim = ValueAnimator.ofInt(startPadding, endPadding);
                        anim.setDuration(SPRING_DURATION_MS);
                        anim.setInterpolator(new OvershootInterpolator(1.1f));
                        anim.addUpdateListener(a ->
                                root.setPadding(baseLeft, baseTop, baseRight, (int) a.getAnimatedValue()));
                        anim.start();
                        activeAnim[0] = anim;

                        return bounds;
                    }

                    @NonNull
                    @Override
                    public WindowInsetsCompat onProgress(
                            @NonNull WindowInsetsCompat insets,
                            @NonNull List<WindowInsetsAnimationCompat> runningAnimations) {
                        // Real IME progress is intentionally ignored — our own
                        // ValueAnimator (started in onStart) owns the padding
                        // for the whole gesture so it can bounce/overshoot
                        // instead of following the system's plain curve.
                        return insets;
                    }
                });

        ViewCompat.requestApplyInsets(root);
    }
}
