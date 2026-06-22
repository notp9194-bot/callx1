package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ChatCustomizationBottomSheet — Minimal clean chat, no customization options.
 */
public class ChatCustomizationBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ChatCustomizationBottomSheet";

    public static final int OPTION_WALLPAPER   = 0;
    public static final int OPTION_THEME       = 1;
    public static final int OPTION_BUBBLE      = 2;
    public static final int OPTION_TYPING      = 3;
    public static final int OPTION_FONT_SIZE   = 4;

    public interface OnOptionSelectedListener {
        void onOptionSelected(int option);
    }

    private OnOptionSelectedListener listener;

    public static ChatCustomizationBottomSheet newInstance() {
        return new ChatCustomizationBottomSheet();
    }

    public void setOnOptionSelectedListener(OnOptionSelectedListener l) {
        this.listener = l;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL,
                com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackground(buildSheetBg());
        root.setPadding(dp(24), 0, dp(24), dp(32));

        FrameLayout handleBar = new FrameLayout(ctx);
        handleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        View handle = new View(ctx);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(40), dp(4));
        hp.gravity = Gravity.CENTER;
        handle.setLayoutParams(hp);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(0x44FFFFFF);
        hBg.setCornerRadius(dp(2));
        handle.setBackground(hBg);
        handleBar.addView(handle);
        root.addView(handleBar);

        TextView icon = new TextView(ctx);
        icon.setText("💬");
        icon.setTextSize(36f);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(8);
        ilp.bottomMargin = dp(12);
        icon.setLayoutParams(ilp);
        root.addView(icon);

        TextView title = new TextView(ctx);
        title.setText("Clean Chat");
        title.setTextSize(18f);
        title.setTextColor(0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("Minimal theme · Rounded bubbles · Normal text");
        sub.setTextSize(13f);
        sub.setTextColor(0x88FFFFFF);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        slp.bottomMargin = dp(20);
        sub.setLayoutParams(slp);
        root.addView(sub);

        LinearLayout badge = new LinearLayout(ctx);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0x1A60A5FA);
        badgeBg.setCornerRadius(dp(10));
        badgeBg.setStroke(dp(1), 0x3360A5FA);
        badge.setBackground(badgeBg);

        TextView checkMark = new TextView(ctx);
        checkMark.setText("✓  ");
        checkMark.setTextSize(14f);
        checkMark.setTextColor(0xFF60A5FA);
        checkMark.setTypeface(Typeface.DEFAULT_BOLD);
        badge.addView(checkMark);

        TextView activeLabel = new TextView(ctx);
        activeLabel.setText("Optimized for performance");
        activeLabel.setTextSize(13f);
        activeLabel.setTextColor(0xFF60A5FA);
        badge.addView(activeLabel);

        root.addView(badge);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            BottomSheetBehavior<FrameLayout> behavior = d.getBehavior();
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int h = (int) (dm.heightPixels * 0.45f);
            behavior.setPeekHeight(h, false);
            behavior.setMaxHeight(h);
            behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            behavior.setHideable(true);
            behavior.setDraggable(true);
            behavior.setFitToContents(true);
        }
    }

    private GradientDrawable buildSheetBg() {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF111827, 0xFF0D1117});
        gd.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        return gd;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
