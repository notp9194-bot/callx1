package com.callx.app.utils;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.Window;

import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;

/**
 * Applies the rounded-corner dialog style (originally built for the
 * View Once warning dialog in ChatActivity) to any AlertDialog across
 * the chat system.
 *
 * Takes {@link Dialog} (not AlertDialog) on purpose — the chat module mixes
 * both `android.app.AlertDialog` and `androidx.appcompat.app.AlertDialog`
 * across different files/controllers, and both extend Dialog, so this one
 * helper works for either without needing two overloads.
 *
 * Usage: replace `dialog.show();` with `AlertDialogStyler.showRounded(dialog);`
 * This does not touch any listeners you've already wired (positive/negative
 * button clicks, onShowListener, onDismissListener, setCancelable, etc.) —
 * it only swaps the window background before/after show().
 */
public final class AlertDialogStyler {

    private AlertDialogStyler() {}

    /** Show the dialog with the default shared rounded background. */
    public static void showRounded(Dialog dialog) {
        showRounded(dialog, com.callx.app.core.R.drawable.bg_rounded_alert_dialog);
    }

    /** Show the dialog with a custom rounded background drawable. */
    public static void showRounded(Dialog dialog, @DrawableRes int backgroundRes) {
        if (dialog == null) return;

        Window window = dialog.getWindow();
        // Clear the default square AlertDialog frame BEFORE show() so the
        // window layout pass doesn't flash the default background first.
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        dialog.show();

        // DecorView is only reliably available/rendered after show(), so the
        // real rounded background is applied post-show — same approach used
        // by the original View Once dialog.
        if (window != null) {
            Drawable bg = ContextCompat.getDrawable(dialog.getContext(), backgroundRes);
            window.getDecorView().setBackground(bg);
        }
    }

    /**
     * For dialogs already shown elsewhere (rare) — re-applies the rounded
     * background on an already-visible dialog window.
     */
    public static void applyToShownDialog(Dialog dialog) {
        applyToShownDialog(dialog, com.callx.app.core.R.drawable.bg_rounded_alert_dialog);
    }

    public static void applyToShownDialog(Dialog dialog, @DrawableRes int backgroundRes) {
        if (dialog == null || dialog.getWindow() == null) return;
        Drawable bg = ContextCompat.getDrawable(dialog.getContext(), backgroundRes);
        dialog.getWindow().getDecorView().setBackground(bg);
    }
}
