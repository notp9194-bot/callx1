package com.callx.app.utils;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;
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

    // PERF: cache the parsed shape (ConstantState) per drawable-res id instead
    // of re-parsing bg_rounded_alert_dialog.xml on every single dialog show.
    // newDrawable() below is a cheap call that shares the parsed constant
    // state but still returns an independent Drawable instance per dialog —
    // safe even if two dialogs are visible at once.
    private static final SparseArray<Drawable.ConstantState> BG_CACHE = new SparseArray<>();

    private static Drawable getCachedDrawable(Context context, @DrawableRes int backgroundRes) {
        // Key includes the current day/night bit — our shape resolves
        // @color/dialog_surface (white in values/, dark in values-night/) at
        // parse time, so a cache keyed on resId alone would keep showing
        // whichever mode it was first parsed in even after the user switches
        // theme (activity recreate reuses this same static cache).
        boolean isNight = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int key = backgroundRes * 2 + (isNight ? 1 : 0);

        Drawable.ConstantState state = BG_CACHE.get(key);
        if (state != null) {
            return state.newDrawable(context.getResources(), context.getTheme());
        }
        Drawable bg = ContextCompat.getDrawable(context, backgroundRes);
        if (bg != null) {
            Drawable.ConstantState newState = bg.getConstantState();
            if (newState != null) {
                BG_CACHE.put(key, newState);
            }
        }
        return bg;
    }

    /**
     * Width variant for the dialog window — controls whether it looks
     * "chota"/compact, normal (system default), or wide. Button layout
     * (horizontal row vs stacked-vertical) itself is decided by Android
     * based on button count/text length — this only controls the dialog's
     * width, which is what actually pushes buttons onto their own line.
     */
    public enum DialogSize { DEFAULT, COMPACT, WIDE }

    private static final int COMPACT_WIDTH_DP = 260;
    private static final int WIDE_WIDTH_PERCENT = 92; // % of screen width

    /** Show the dialog with the default shared rounded background. */
    public static void showRounded(Dialog dialog) {
        showRounded(dialog, com.callx.app.core.R.drawable.bg_rounded_alert_dialog, DialogSize.DEFAULT);
    }

    /** Show the dialog with a custom rounded background drawable. */
    public static void showRounded(Dialog dialog, @DrawableRes int backgroundRes) {
        showRounded(dialog, backgroundRes, DialogSize.DEFAULT);
    }

    /** Show the dialog with the default background at a given width variant. */
    public static void showRounded(Dialog dialog, DialogSize size) {
        showRounded(dialog, com.callx.app.core.R.drawable.bg_rounded_alert_dialog, size);
    }

    /** Show the dialog with a custom background AND a given width variant. */
    public static void showRounded(Dialog dialog, @DrawableRes int backgroundRes, DialogSize size) {
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
            Drawable bg = getCachedDrawable(dialog.getContext(), backgroundRes);
            window.getDecorView().setBackground(bg);
            applySize(window, size);
            styleActionButtons(dialog);
        }
    }

    // ── Action-button styling (Canvas-rendered, spaced, color-by-action) ──
    // Pehle sirf COMPACT dialogs pe tha, ab har showRounded() dialog
    // (COMPACT/WIDE/DEFAULT) isi style ko use karta hai — consistency.

    private static final int COLOR_DESTRUCTIVE = Color.parseColor("#E53935"); // red
    private static final int COLOR_NEUTRAL     = Color.parseColor("#757575"); // grey
    private static final int COLOR_PRIMARY     = Color.parseColor("#2979FF"); // blue

    private static void styleActionButtons(Dialog dialog) {
        android.widget.Button pos = null, neg = null, neu = null;
        if (dialog instanceof androidx.appcompat.app.AlertDialog) {
            androidx.appcompat.app.AlertDialog ad = (androidx.appcompat.app.AlertDialog) dialog;
            pos = ad.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            neg = ad.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
            neu = ad.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
        } else if (dialog instanceof android.app.AlertDialog) {
            android.app.AlertDialog ad = (android.app.AlertDialog) dialog;
            pos = ad.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            neg = ad.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
            neu = ad.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
        } else {
            return; // unknown dialog type — leave default look
        }

        int gapPx = Math.round(10 * dialog.getContext().getResources().getDisplayMetrics().density);
        applyCanvasButtonStyle(pos, gapPx);
        applyCanvasButtonStyle(neg, gapPx);
        applyCanvasButtonStyle(neu, gapPx);
    }

    private static void applyCanvasButtonStyle(android.widget.Button btn, int gapPx) {
        if (btn == null) return;

        btn.setBackground(new CanvasButtonDrawable(colorForButtonText(btn.getText())));
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);

        android.view.ViewGroup.LayoutParams lp = btn.getLayoutParams();
        if (lp instanceof android.widget.LinearLayout.LayoutParams) {
            android.widget.LinearLayout.LayoutParams llp = (android.widget.LinearLayout.LayoutParams) lp;
            llp.leftMargin = gapPx;
            llp.rightMargin = gapPx;
            llp.weight = 0; // stop equal-width stretch so buttons hug their own text
            llp.width = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT;
            btn.setLayoutParams(llp);
        }
    }

    /** Delete/Remove/Block/Clear/Leave → destructive red; Cancel → grey; sab kuch else → primary blue. */
    private static int colorForButtonText(CharSequence text) {
        if (text == null) return COLOR_PRIMARY;
        String t = text.toString().toLowerCase();
        if (t.contains("delete") || t.contains("remove") || t.contains("block")
                || t.contains("clear") || t.contains("leave")) {
            return COLOR_DESTRUCTIVE;
        }
        if (t.contains("cancel")) {
            return COLOR_NEUTRAL;
        }
        return COLOR_PRIMARY;
    }

    /** Rounded-pill button background, drawn directly on Canvas (no XML drawable). */
    private static class CanvasButtonDrawable extends Drawable {
        private final android.graphics.Paint paint;

        CanvasButtonDrawable(int color) {
            paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            paint.setStyle(android.graphics.Paint.Style.FILL);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.RectF bounds = new android.graphics.RectF(getBounds());
            float radius = bounds.height() / 2f; // pill shape
            canvas.drawRoundRect(bounds, radius, radius, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static void applySize(Window window, DialogSize size) {
        if (size == null || size == DialogSize.DEFAULT) return;
        Context ctx = window.getContext();
        int widthPx;
        if (size == DialogSize.COMPACT) {
            widthPx = Math.round(COMPACT_WIDTH_DP * ctx.getResources().getDisplayMetrics().density);
        } else { // WIDE
            int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
            widthPx = screenWidth * WIDE_WIDTH_PERCENT / 100;
        }
        window.setLayout(widthPx, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
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
        Drawable bg = getCachedDrawable(dialog.getContext(), backgroundRes);
        dialog.getWindow().getDecorView().setBackground(bg);
    }
}
