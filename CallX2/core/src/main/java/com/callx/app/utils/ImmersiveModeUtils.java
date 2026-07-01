package com.callx.app.utils;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.statusBars()
                    | WindowInsetsCompat.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
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
}
