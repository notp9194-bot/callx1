package com.callx.app.utils;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
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
            fixDialogTextContrast(dialog);
        }
    }

    // ── Dark/light mode text-contrast fix ───────────────────────────────
    // Custom dialog content across chat/group/reels/status/calls — the
    // hand-built TextViews devs pass into setView(...) — was hardcoded to
    // near-black text colors (#222222, #111111, plain black, etc) back
    // when every dialog had a plain white background. Now that the window
    // background follows @color/dialog_surface (white in light mode,
    // #1E1E1E in night — see bg_rounded_alert_dialog.xml), any of that
    // hardcoded near-black text goes invisible against a dark surface.
    //
    // This walks the dialog's OWN decor view only (nothing else on
    // screen is touched) right after show() and remaps just those known
    // near-black literals to @color/dialog_text_primary for the mode the
    // device is currently in. Everything else — brand/accent colors,
    // greys already light enough to read on dark, the button labels
    // (forced white by styleActionButtons) — is left alone.
    private static final int[] LEGACY_DARK_TEXT_COLORS = {
        0xFF000000, 0xFF111111, 0xFF1C1C1E, 0xFF212121, 0xFF222222, 0xFF333333, 0xDE000000
    };

    private static void fixDialogTextContrast(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;

        boolean isNight = (dialog.getContext().getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        // Light mode: the hardcoded near-black text is already correct
        // against the white surface — nothing to fix.
        if (!isNight) return;

        int fixedColor = ContextCompat.getColor(dialog.getContext(), com.callx.app.core.R.color.dialog_text_primary);
        walkAndFixTextColor(window.getDecorView(), fixedColor);
    }

    private static void walkAndFixTextColor(View view, int fixedColor) {
        // Action buttons are colored intentionally (white-on-brand-color)
        // by styleActionButtons() — never touch those here.
        if (view instanceof android.widget.Button) return;

        if (view instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) view;
            int current = tv.getCurrentTextColor();
            for (int legacy : LEGACY_DARK_TEXT_COLORS) {
                if (current == legacy) {
                    tv.setTextColor(fixedColor);
                    break;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                walkAndFixTextColor(vg.getChildAt(i), fixedColor);
            }
        }
    }

    // ── Action-button styling (Canvas-rendered, spaced, color-by-action) ──
    // Pehle sirf COMPACT dialogs pe tha, ab har showRounded() dialog
    // (COMPACT/WIDE/DEFAULT) isi style ko use karta hai — consistency.

    private static final int COLOR_DESTRUCTIVE = Color.parseColor("#E53935"); // red
    private static final int COLOR_NEUTRAL     = Color.parseColor("#8E24AA"); // purple
    private static final int COLOR_PRIMARY     = Color.parseColor("#2E7D32"); // green
    private static final float BUTTON_RADIUS_DP = 8f; // tight corners, not a full pill

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

        int gapPx = Math.round(6 * dialog.getContext().getResources().getDisplayMetrics().density);
        applyCanvasButtonStyle(pos, gapPx);
        applyCanvasButtonStyle(neg, gapPx);
        applyCanvasButtonStyle(neu, gapPx);
    }

    private static void applyCanvasButtonStyle(android.widget.Button btn, int gapPx) {
        if (btn == null) return;

        int color = colorForButtonText(btn.getText());
        float radiusPx = BUTTON_RADIUS_DP * btn.getResources().getDisplayMetrics().density;
        btn.setBackground(getCachedButtonBackground(color, radiusPx));
        // Material/AppCompat auto-applies a backgroundTintList to every
        // Button inflated inside an AppCompatActivity (even framework
        // AlertDialog buttons — the activity's LayoutInflater intercepts
        // them). That tint recolors whatever background we set via a
        // PorterDuff filter, which is why the canvas drawable's color
        // wasn't visibly changing. Clearing it lets our color through.
        btn.setBackgroundTintList(null);
        btn.setStateListAnimator(null);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        // Compact "premium" size: AppCompat's default Button style carries a
        // built-in minWidth (~88dp) and minHeight (~48dp) that padding alone
        // can't shrink past — clearing those first is what actually lets the
        // box get small instead of just padding a still-oversized min box.
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        int padH = Math.round(14 * btn.getResources().getDisplayMetrics().density);
        int padV = Math.round(6 * btn.getResources().getDisplayMetrics().density);
        btn.setPadding(padH, padV, padH, padV);

        android.view.ViewGroup.LayoutParams lp = btn.getLayoutParams();
        if (lp instanceof android.widget.LinearLayout.LayoutParams) {
            android.widget.LinearLayout.LayoutParams llp = (android.widget.LinearLayout.LayoutParams) lp;
            llp.leftMargin = gapPx;
            llp.rightMargin = gapPx;
            llp.weight = 0; // stop equal-width stretch so buttons hug their own text
            llp.width = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT;
            llp.height = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT;
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

    // ── Paint pool + pre-cached button backgrounds ─────────────────────
    // Only 3 button colors exist app-wide (destructive/neutral/primary), so
    // there's no reason to `new Paint()` on every single dialog show() —
    // that's one GC-eligible allocation per button, times every dialog,
    // for the entire life of the app. Instead: 3 Paint objects total, ever.
    //
    // A raw Drawable instance still can't be shared directly across buttons
    // (Android calls setBounds() on whatever Drawable a View's background
    // points to, so two Buttons on screen at once sharing literally the same
    // Drawable object would fight over one bounds rect). We solve that the
    // same way the Android framework itself solves it for XML drawables:
    // Drawable.ConstantState. One ConstantState per color is built once and
    // cached; every dialog after that just calls .newDrawable() on it, which
    // is a cheap object wrapping the *same* pooled Paint with independent
    // bounds — no new Paint, no new Canvas parsing, just a thin wrapper.
    private static final android.util.SparseArray<android.graphics.Paint> PAINT_POOL =
        new android.util.SparseArray<>(3);
    private static final java.util.HashMap<Long, Drawable.ConstantState> BUTTON_BG_CACHE =
        new java.util.HashMap<>(4);

    static {
        // Build all 3 Paints right now, at class-load (app startup) time —
        // not the first time a dialog happens to show. The drawable cache
        // below still can't be pre-warmed the same way since it needs a
        // Context (for screen density) to compute radiusPx, so that part
        // warms lazily on the very first dialog instead.
        pooledPaint(COLOR_DESTRUCTIVE);
        pooledPaint(COLOR_NEUTRAL);
        pooledPaint(COLOR_PRIMARY);
    }

    private static android.graphics.Paint pooledPaint(int color) {
        android.graphics.Paint p = PAINT_POOL.get(color);
        if (p == null) {
            p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setStyle(android.graphics.Paint.Style.FILL);
            PAINT_POOL.put(color, p);
        }
        return p;
    }

    private static Drawable getCachedButtonBackground(int color, float radiusPx) {
        // radiusPx is derived from a fixed dp value * device density, which
        // doesn't change at runtime, but we key on both anyway so this stays
        // correct even if BUTTON_RADIUS_DP is ever tuned per-call in future.
        long key = ((long) color << 32) ^ (Float.floatToIntBits(radiusPx) & 0xFFFFFFFFL);
        Drawable.ConstantState state = BUTTON_BG_CACHE.get(key);
        if (state == null) {
            CanvasButtonDrawable prototype = new CanvasButtonDrawable(pooledPaint(color), radiusPx);
            state = prototype.getConstantState();
            BUTTON_BG_CACHE.put(key, state);
            return prototype;
        }
        return state.newDrawable();
    }

    /**
     * Rounded-rect button background, drawn directly on Canvas (no XML drawable).
     * Paint is shared/pooled by color (see {@link #pooledPaint}) — draw() reads
     * it directly for the common case, which is the fast path for every button
     * on screen. If the framework ever calls setAlpha()/setColorFilter() with a
     * non-default value (e.g. a disabled-state dim), this instance transparently
     * copy-on-writes into its own private Paint instead of mutating the shared
     * one — so one dimmed/disabled button can never bleed its alpha/filter into
     * every other button of the same color elsewhere in the app.
     */
    private static class CanvasButtonDrawable extends Drawable {
        private final android.graphics.Paint sharedPaint;
        private android.graphics.Paint ownPaint; // lazily created only if this instance diverges
        private final float radiusPx;

        CanvasButtonDrawable(android.graphics.Paint sharedPaint, float radiusPx) {
            this.sharedPaint = sharedPaint;
            this.radiusPx = radiusPx;
        }

        private android.graphics.Paint activePaint() {
            return ownPaint != null ? ownPaint : sharedPaint;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.RectF bounds = new android.graphics.RectF(getBounds());
            canvas.drawRoundRect(bounds, radiusPx, radiusPx, activePaint());
        }

        @Override
        public void setAlpha(int alpha) {
            if (alpha == 255) { ownPaint = null; return; } // back to the fast shared path
            if (ownPaint == null) ownPaint = new android.graphics.Paint(sharedPaint); // copy-on-write
            ownPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            if (colorFilter == null && ownPaint == null) return;
            if (ownPaint == null) ownPaint = new android.graphics.Paint(sharedPaint);
            ownPaint.setColorFilter(colorFilter);
        }

        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }

        @Override
        public ConstantState getConstantState() {
            final android.graphics.Paint paintRef = sharedPaint;
            final float r = radiusPx;
            return new ConstantState() {
                @Override public Drawable newDrawable() {
                    return new CanvasButtonDrawable(paintRef, r);
                }
                @Override public int getChangingConfigurations() { return 0; }
            };
        }
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

    // ── Singleton reusable dialog cache ─────────────────────────────────
    // For dialogs of a *kind* that reopen a lot with the same shape but
    // different text each time — the classic case being a delete-confirm
    // dialog tapped over and over as the user deletes message after
    // message — `new AlertDialog.Builder(...).create()` is a fresh inflate
    // + measure of the whole dialog view tree every single time, even
    // though title/message/button-labels are the only things that
    // actually change. showReusableConfirm() keeps ONE Dialog instance
    // per (Activity, tag) and just swaps its text + click listeners on
    // each call instead.
    //
    // Keyed by Activity/Context identity via a WeakHashMap so a cached
    // dialog never outlives (and never leaks past) the Activity it was
    // built for. Keyed additionally by a caller-chosen `tag` since one
    // screen can have more than one *kind* of repeatable confirm dialog
    // (e.g. "delete message" vs "clear chat").
    private static final java.util.WeakHashMap<Context, java.util.Map<String, androidx.appcompat.app.AlertDialog>>
        CONFIRM_CACHE = new java.util.WeakHashMap<>();

    /** Callback for a reusable confirm dialog's positive/neutral action. */
    public interface ConfirmAction {
        void onConfirm();
    }

    /**
     * Show (or reuse) a compact confirm-style dialog for the given
     * context+tag. Use this in place of building a fresh
     * `new AlertDialog.Builder(...)` every time the *same kind* of confirm
     * dialog can reopen in quick succession (delete confirm, clear-chat
     * confirm, etc). The underlying Dialog is created once per
     * (context, tag) pair; subsequent calls just update the title,
     * message, and button actions on the existing instance.
     *
     * @param context      hosting Activity/Context — also the cache key
     * @param tag          identifies which *kind* of reusable dialog this is
     *                     within that context (e.g. "delete_message")
     * @param size         width variant, same as showRounded()
     * @param title        dialog title (may be null)
     * @param message      dialog message, refreshed on every call
     * @param positiveText positive button label
     * @param onPositive   positive button action (dialog auto-dismisses after)
     * @param neutralText  neutral button label, or null to omit the button
     * @param onNeutral    neutral button action, or null if neutralText is null
     * @param negativeText negative/cancel button label (dismiss-only)
     */
    public static void showReusableConfirm(Context context, String tag, DialogSize size,
            String title, String message,
            String positiveText, ConfirmAction onPositive,
            String neutralText, ConfirmAction onNeutral,
            String negativeText) {
        if (context == null) return;

        java.util.Map<String, androidx.appcompat.app.AlertDialog> perContext = CONFIRM_CACHE.get(context);
        if (perContext == null) {
            perContext = new java.util.HashMap<>();
            CONFIRM_CACHE.put(context, perContext);
        }

        androidx.appcompat.app.AlertDialog dialog = perContext.get(tag);
        if (dialog == null) {
            // Build the shell ONCE. Button click listeners are intentionally
            // left null here — they're bound fresh in setOnShowListener()
            // below on every show() call, since that's the only reliable
            // place a Builder-created dialog's Button views exist to attach
            // a new closure to (getButton() is null before the dialog is
            // shown at least once).
            androidx.appcompat.app.AlertDialog.Builder builder =
                new androidx.appcompat.app.AlertDialog.Builder(context);
            builder.setPositiveButton(positiveText, null);
            if (neutralText != null) builder.setNeutralButton(neutralText, null);
            builder.setNegativeButton(negativeText, (d, w) -> d.dismiss());
            dialog = builder.create();
            perContext.put(tag, dialog);
        }

        dialog.setTitle(title);
        dialog.setMessage(message);

        androidx.appcompat.app.AlertDialog finalDialog = dialog;
        dialog.setOnShowListener(d -> {
            android.widget.Button pos = finalDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            if (pos != null) {
                pos.setOnClickListener(v -> {
                    onPositive.onConfirm();
                    finalDialog.dismiss();
                });
            }
            if (neutralText != null && onNeutral != null) {
                android.widget.Button neu = finalDialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
                if (neu != null) {
                    neu.setOnClickListener(v -> {
                        onNeutral.onConfirm();
                        finalDialog.dismiss();
                    });
                }
            }
        });

        // Dialog is already-created after the first call — Dialog.show()
        // on an existing (non-dismissed-and-recreated) instance just
        // re-attaches the existing decor view, it doesn't re-inflate.
        showRounded(dialog, size);
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
        fixDialogTextContrast(dialog);
    }
}
