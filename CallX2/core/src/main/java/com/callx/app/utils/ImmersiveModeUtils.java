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
        // BUG FIX: setImmersiveMode() runs multiple times per session
        // (onCreate, onResume, every onWindowFocusChanged regain — dialogs,
        // notification shade, share sheet all trigger it). Each call used to
        // re-read the CURRENT padding as "base", which already included the
        // previous inset — so on devices where the status bar doesn't stay
        // hidden (MIUI etc.), padding kept stacking higher every focus event.
        // Fix: capture the true original padding exactly once via a tag.
        Object baseTag = topBar.getTag();
        final int baseLeft, baseTop, baseRight, baseBottom;
        if (baseTag instanceof int[]) {
            int[] base = (int[]) baseTag;
            baseLeft = base[0]; baseTop = base[1]; baseRight = base[2]; baseBottom = base[3];
        } else {
            baseLeft   = topBar.getPaddingLeft();
            baseTop    = topBar.getPaddingTop();
            baseRight  = topBar.getPaddingRight();
            baseBottom = topBar.getPaddingBottom();
            topBar.setTag(new int[]{baseLeft, baseTop, baseRight, baseBottom});
        }

        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            int maxInsetPx = Math.round(56 * v.getResources().getDisplayMetrics().density);
            int statusInset = Math.min(bars.top, maxInsetPx);
            v.setPadding(baseLeft, baseTop + statusInset, baseRight, baseBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(topBar);
    }

    /**
     * Pushes {@code bottomBar} (e.g. the app's own BottomNavigationView
     * container) up by the nav-bar inset height using MARGIN (not padding,
     * since the view has a fixed height and sits flush at the bottom via
     * gravity). Inset is 0 while our nav bar stays hidden, so this is a
     * no-op most of the time — but on devices/OEMs where the system nav bar
     * fails to stay hidden and re-appears, this guarantees our own bottom
     * nav is pushed up above it instead of being covered/hidden by it.
     */
    public static void applyBottomInsetMargin(View bottomBar) {
        if (bottomBar == null) return;
        if (!(bottomBar.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams)) return;

        // BUG FIX (root cause of bottom nav disappearing on Chats/Status/
        // Groups/Calls tabs): setImmersiveMode() is called on onCreate,
        // onResume AND every onWindowFocusChanged regain (dialogs,
        // notification shade, share sheet, app switch — all of these).
        // Previously "baseBottomMargin" was re-read from lp.bottomMargin
        // EVERY call, which already contained the PREVIOUS inset that was
        // added last time. On devices where the system nav bar doesn't stay
        // hidden (MIUI/ColorOS etc. — exactly the OEMs this safety-net was
        // written for), each focus regain kept stacking the inset on top of
        // itself: 48 → 96 → 144 → 192... until nav_container was pushed
        // completely below the visible screen. Fix: capture the TRUE
        // original XML margin exactly once per view via a tag, so repeated
        // calls always add the inset on top of the same fixed base.
        Object baseTag = bottomBar.getTag();
        final int baseBottomMargin;
        if (baseTag instanceof Integer) {
            baseBottomMargin = (Integer) baseTag;
        } else {
            android.view.ViewGroup.MarginLayoutParams lp =
                    (android.view.ViewGroup.MarginLayoutParams) bottomBar.getLayoutParams();
            baseBottomMargin = lp.bottomMargin;
            bottomBar.setTag(baseBottomMargin);
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomBar, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            // SAFETY CLAMP: some OEM ROMs report an abnormally large/buggy
            // navigationBars() inset under forced edge-to-edge immersive mode
            // (this is exactly what pushed the bottom nav fully off-screen).
            // No real device nav bar is taller than ~48dp, so cap at 56dp —
            // generous, but guarantees the margin can never blow up.
            int maxInsetPx = Math.round(56 * v.getResources().getDisplayMetrics().density);
            int navInset = Math.min(bars.bottom, maxInsetPx);
            android.view.ViewGroup.MarginLayoutParams params =
                    (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
            params.bottomMargin = baseBottomMargin + navInset;
            v.setLayoutParams(params);
            return insets;
        });
        ViewCompat.requestApplyInsets(bottomBar);
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
