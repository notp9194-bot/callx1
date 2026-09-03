package com.callx.app.viewer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Instagram-style ONE-TIME "tilt your phone" onboarding hint. Shown the
 * very first moment a viewer's StorySpinViewController actually starts
 * (see StatusViewerActivity#startSpinViewIfAllowed()) — i.e. the first
 * time they ever land on a story/highlight that has the level-stabilizer
 * on — so the effect doesn't look like an accident the first time the
 * screen visibly reacts to the phone moving.
 *
 * Purely a transient overlay: a small icon-plus-label pill that fades in
 * above the bottom controls, gives the icon a couple of playful tilt
 * swings, then fades itself out and removes itself. Never intercepts
 * touches (so it never blocks tap-to-pause/swipe navigation underneath
 * it), and never reappears once shown — see StoryRotateHintPrefs.
 */
public final class StoryRotateHintView {

    private static final long FADE_IN_MS = 220L;
    private static final long HOLD_MS = 2200L;
    private static final long FADE_OUT_MS = 260L;
    private static final int TILT_SWING_DEG = 18;
    private static final long TILT_SWING_MS = 420L;
    private static final int TILT_SWING_COUNT = 3; // full left-right-left cycles

    private StoryRotateHintView() { }

    /** No-op if this viewer has already seen the hint (any story, ever).
     *  Otherwise builds, shows, and permanently marks it seen — the mark
     *  happens up front, not after the animation finishes, so a viewer who
     *  backs out mid-animation still won't see it again next time. */
    public static void maybeShow(Context ctx, ViewGroup root) {
        if (ctx == null || root == null) return;
        if (StoryRotateHintPrefs.hasSeenHint(ctx)) return;
        StoryRotateHintPrefs.markSeen(ctx);

        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(190, 20, 20, 20));
        bg.setCornerRadius(dp(ctx, 24));
        pill.setBackground(bg);
        pill.setAlpha(0f);

        TextView icon = new TextView(ctx);
        icon.setText("\uD83D\uDCF1"); // 📱
        icon.setTextSize(20);
        icon.setPadding(0, 0, dp(ctx, 10), 0);
        pill.addView(icon);

        TextView label = new TextView(ctx);
        label.setText("Tilt your phone \u2014 we'll keep it level");
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        pill.addView(label);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(ctx, 140);
        root.addView(pill, lp);
        // Purely visual, sits above the controls but must never eat a tap
        // meant for pause/swipe navigation on the layer underneath it.
        pill.setClickable(false);
        pill.setFocusable(false);
        // Both animations below are pure compositor transforms (alpha on
        // the pill, rotation on the icon) with no content change frame to
        // frame — a hardware layer lets the GPU re-composite the cached
        // layer on each tick instead of the view system re-drawing/
        // re-recording the pill (and its two child TextViews) every frame.
        // Dropped again in dismiss() once the whole thing is done animating.
        pill.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        pill.animate().alpha(1f).setDuration(FADE_IN_MS).withEndAction(() -> {
            icon.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            ObjectAnimator swing = ObjectAnimator.ofFloat(icon, View.ROTATION,
                    0f, -TILT_SWING_DEG, TILT_SWING_DEG, 0f);
            swing.setDuration(TILT_SWING_MS * 4);
            swing.setRepeatCount(TILT_SWING_COUNT - 1);
            swing.setInterpolator(new OvershootInterpolator(1.2f));
            swing.start();

            // pill.postDelayed(), not a freshly-allocated Handler — reuses
            // the view's own attached message queue instead of allocating
            // a new Handler object (and a new Looper lookup) just for this
            // one-shot, once-ever-per-install callback.
            pill.postDelayed(() -> dismiss(pill), HOLD_MS);
        });
    }

    private static void dismiss(View pill) {
        if (pill.getParent() == null) return; // already removed (e.g. activity gone)
        pill.animate().alpha(0f).setDuration(FADE_OUT_MS).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                ViewGroup parent = (ViewGroup) pill.getParent();
                if (parent != null) parent.removeView(pill);
                // Release the GPU-backed layer now that nothing further
                // animates it — no reason to keep holding it after the
                // view is already detached and gone.
                pill.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
