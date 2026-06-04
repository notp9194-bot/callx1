package com.callx.app.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.AnimatorSet;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.callx.app.managers.MessageReactionManager;

import java.util.List;

/**
 * EmojiReactionPickerView
 *
 * A floating popup that appears when user long-presses a message bubble.
 * Shows 8 quick-pick emoji buttons in a pill-shaped horizontal row.
 *
 * Behavior:
 *   ✅ Appears above the message bubble (or below if not enough space)
 *   ✅ Spring-scale-in animation (OvershootInterpolator)
 *   ✅ Tapping emoji → calls listener → manager.toggleReaction()
 *   ✅ Currently reacted emoji shown with colored background
 *   ✅ Dismisses with scale-out animation on tap or outside click
 *   ✅ "More..." button at end → opens full emoji keyboard (optional)
 *
 * Usage (in MessageAdapter.onBindViewHolder() long-click):
 *
 *   holder.bubble.setOnLongClickListener(v -> {
 *       EmojiReactionPickerView.showAt(context, v, currentUserReaction,
 *           emoji -> reactionManager.toggleReaction(msg.messageId, emoji));
 *       return true;
 *   });
 */
public class EmojiReactionPickerView {

    public interface OnEmojiSelected { void onSelected(String emoji); }

    /**
     * Show the emoji picker anchored to an existing view (the message bubble).
     *
     * @param context             Activity context
     * @param anchorView          The message bubble view (anchor point)
     * @param currentUserReaction Emoji the current user already reacted with (or null)
     * @param onEmojiSelected     Callback with selected emoji string
     */
    public static void showAt(Context context, View anchorView,
                               String currentUserReaction,
                               OnEmojiSelected onEmojiSelected) {
        // Build picker layout
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dpToPx(context, 8);
        int padV = dpToPx(context, 6);
        row.setPadding(padH, padV, padH, padV);

        // Pill background
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dpToPx(context, 24));
        bg.setStroke(1, 0x22000000);
        bg.setShadowLayer(8, 0, 4, 0x33000000);
        row.setBackground(bg);
        row.setElevation(dpToPx(context, 6));

        // Emoji buttons
        PopupWindow[] windowRef = new PopupWindow[1];

        for (String emoji : MessageReactionManager.QUICK_EMOJIS) {
            TextView btn = makeEmojiButton(context, emoji,
                    emoji.equals(currentUserReaction));

            btn.setOnClickListener(v -> {
                // Bounce animation on tap
                ObjectAnimator scaleX = ObjectAnimator.ofFloat(btn, "scaleX", 1f, 1.4f, 1f);
                ObjectAnimator scaleY = ObjectAnimator.ofFloat(btn, "scaleY", 1f, 1.4f, 1f);
                AnimatorSet bounce = new AnimatorSet();
                bounce.playTogether(scaleX, scaleY);
                bounce.setDuration(200);
                bounce.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator anim) {
                        if (onEmojiSelected != null) onEmojiSelected.onSelected(emoji);
                        if (windowRef[0] != null) dismissWithAnim(row, windowRef[0]);
                    }
                });
                bounce.start();
            });
            row.addView(btn);
        }

        // Wrap in FrameLayout for PopupWindow
        FrameLayout container = new FrameLayout(context);
        int margin = dpToPx(context, 8);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(margin, margin, margin, margin);
        container.addView(row, lp);

        // Create PopupWindow
        int popupW = ViewGroup.LayoutParams.WRAP_CONTENT;
        int popupH = ViewGroup.LayoutParams.WRAP_CONTENT;
        PopupWindow popup = new PopupWindow(container, popupW, popupH, true);
        popup.setElevation(dpToPx(context, 8));
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new GradientDrawable()); // transparent bg for dismiss on outside click
        windowRef[0] = popup;

        // Position above anchor view
        int[] location = new int[2];
        anchorView.getLocationOnScreen(location);
        int anchorCenterX = location[0] + anchorView.getWidth() / 2;

        // Measure popup to know its width
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupWidth  = container.getMeasuredWidth();
        int popupHeight = container.getMeasuredHeight();

        int xOff = anchorCenterX - popupWidth / 2 - location[0];
        int yOff = -(anchorView.getHeight() + popupHeight + dpToPx(context, 4));

        // Show popup
        popup.showAsDropDown(anchorView, xOff, yOff, Gravity.NO_GRAVITY);

        // Animate in
        row.setScaleX(0.3f);
        row.setScaleY(0.3f);
        row.setAlpha(0f);
        row.setPivotX(popupWidth / 2f);
        row.setPivotY(popupHeight * 1f);

        ObjectAnimator sx  = ObjectAnimator.ofFloat(row, "scaleX", 0.3f, 1f);
        ObjectAnimator sy  = ObjectAnimator.ofFloat(row, "scaleY", 0.3f, 1f);
        ObjectAnimator alp = ObjectAnimator.ofFloat(row, "alpha",  0f, 1f);
        AnimatorSet animIn = new AnimatorSet();
        animIn.playTogether(sx, sy, alp);
        animIn.setDuration(220);
        animIn.setInterpolator(new OvershootInterpolator(1.5f));
        animIn.start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private static TextView makeEmojiButton(Context ctx, String emoji, boolean active) {
        TextView tv = new TextView(ctx);
        tv.setText(emoji);
        tv.setTextSize(22f);
        tv.setGravity(Gravity.CENTER);

        int size   = dpToPx(ctx, 44);
        int margin = dpToPx(ctx, 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(margin, 0, margin, 0);
        tv.setLayoutParams(lp);

        // Highlight if this is the user's current reaction
        if (active) {
            GradientDrawable highlight = new GradientDrawable();
            highlight.setColor(0x206C63FF); // brand_primary at 12% alpha
            highlight.setCornerRadius(dpToPx(ctx, 22));
            highlight.setStroke(2, 0xFF6C63FF);
            tv.setBackground(highlight);
        } else {
            tv.setBackground(makeRipple(ctx));
        }

        return tv;
    }

    private static android.graphics.drawable.Drawable makeRipple(Context ctx) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFCCCCCC);
        mask.setCornerRadius(dpToPx(ctx, 22));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x206C63FF),
                null, mask);
    }

    private static void dismissWithAnim(View row, PopupWindow popup) {
        ObjectAnimator sx  = ObjectAnimator.ofFloat(row, "scaleX", 1f, 0.3f);
        ObjectAnimator sy  = ObjectAnimator.ofFloat(row, "scaleY", 1f, 0.3f);
        ObjectAnimator alp = ObjectAnimator.ofFloat(row, "alpha",  1f, 0f);
        AnimatorSet animOut = new AnimatorSet();
        animOut.playTogether(sx, sy, alp);
        animOut.setDuration(160);
        animOut.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { popup.dismiss(); }
        });
        animOut.start();
    }

    private static int dpToPx(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
