package com.callx.app.community;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.callx.app.chat.R;

/**
 * v31: Floating emoji reaction picker.
 * Shows 6 emoji buttons in a row above the anchor view.
 * Dismiss on outside touch; smooth scale+fade animation on show.
 */
public class CommunityReactionPickerView {

    public interface OnReactionSelectedListener {
        void onReactionSelected(String reactionType);
    }

    private final PopupWindow popup;
    private final View contentView;
    private OnReactionSelectedListener listener;

    public CommunityReactionPickerView(Context context) {
        contentView = LayoutInflater.from(context).inflate(R.layout.popup_community_reactions, null);
        popup = new PopupWindow(contentView,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(16f);
        popup.setOutsideTouchable(true);

        bindReactionButton(R.id.tv_react_like,  CommunityReaction.LIKE);
        bindReactionButton(R.id.tv_react_love,  CommunityReaction.LOVE);
        bindReactionButton(R.id.tv_react_haha,  CommunityReaction.HAHA);
        bindReactionButton(R.id.tv_react_wow,   CommunityReaction.WOW);
        bindReactionButton(R.id.tv_react_sad,   CommunityReaction.SAD);
        bindReactionButton(R.id.tv_react_angry, CommunityReaction.ANGRY);
    }

    private void bindReactionButton(int viewId, String reactionType) {
        TextView tv = contentView.findViewById(viewId);
        if (tv == null) return;
        tv.setOnClickListener(v -> {
            if (listener != null) listener.onReactionSelected(reactionType);
            popup.dismiss();
        });
        // Scale on hover (touch feedback)
        tv.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).start();
            } else if (event.getAction() == android.view.MotionEvent.ACTION_UP
                    || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
            }
            return false;
        });
    }

    public void setOnReactionSelectedListener(OnReactionSelectedListener listener) {
        this.listener = listener;
    }

    /** Show the picker above the anchor view with scale+fade animation. */
    public void showAtView(View anchorView) {
        contentView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupWidth  = contentView.getMeasuredWidth();
        int popupHeight = contentView.getMeasuredHeight();

        int[] location = new int[2];
        anchorView.getLocationInWindow(location);
        int anchorX = location[0];
        int anchorY = location[1];

        int xOff = anchorX - popupWidth / 2 + anchorView.getWidth() / 2;
        int yOff = anchorY - popupHeight - 16;

        // Clamp to screen bounds
        int screenWidth = anchorView.getResources().getDisplayMetrics().widthPixels;
        xOff = Math.max(8, Math.min(xOff, screenWidth - popupWidth - 8));

        popup.showAtLocation(anchorView, Gravity.NO_GRAVITY, xOff, yOff);

        // Animate in
        contentView.setAlpha(0f);
        contentView.setScaleX(0.7f);
        contentView.setScaleY(0.7f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(contentView, "alpha",  0f, 1f),
                ObjectAnimator.ofFloat(contentView, "scaleX", 0.7f, 1f),
                ObjectAnimator.ofFloat(contentView, "scaleY", 0.7f, 1f)
        );
        set.setDuration(160);
        set.start();
    }

    public void dismiss() {
        if (popup.isShowing()) popup.dismiss();
    }

    public boolean isShowing() {
        return popup.isShowing();
    }
}
