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
 * ChatCustomizationBottomSheet — Single option: Wallpaper.
 */
public class ChatCustomizationBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ChatCustomizationBottomSheet";
    public static final int OPTION_WALLPAPER = 0;

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
        root.setBackground(buildSheetBackground());

        // Drag handle
        FrameLayout handleBar = new FrameLayout(ctx);
        handleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        View handle = new View(ctx);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(40), dp(4));
        hp.gravity = Gravity.CENTER;
        handle.setLayoutParams(hp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x55FFFFFF);
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        handleBar.addView(handle);
        root.addView(handleBar);

        // Header
        TextView header = new TextView(ctx);
        LinearLayout.LayoutParams hp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hp2.setMargins(dp(20), 0, dp(20), dp(16));
        header.setLayoutParams(hp2);
        header.setText("🖼️  Chat Wallpaper");
        header.setTextSize(17f);
        header.setTextColor(0xFFFFFFFF);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(header);

        // Wallpaper row
        LinearLayout card = buildRow(ctx);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(dp(12), 0, dp(12), dp(20));
        card.setLayoutParams(cp);
        card.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onOptionSelected(OPTION_WALLPAPER);
        });
        root.addView(card);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            BottomSheetBehavior<FrameLayout> behavior = d.getBehavior();
            int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.35f);
            behavior.setPeekHeight(height, false);
            behavior.setMaxHeight(height);
            behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            behavior.setHideable(true);
            behavior.setDraggable(true);
        }
    }

    private LinearLayout buildRow(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setClickable(true);
        card.setFocusable(true);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x220EA5E9, 0x116366F1});
        bg.setStroke(dp(1), 0x330EA5E9);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setMinimumHeight(dp(72));

        FrameLayout iconFrame = new FrameLayout(ctx);
        LinearLayout.LayoutParams ifLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        ifLp.setMarginEnd(dp(16));
        iconFrame.setLayoutParams(ifLp);
        GradientDrawable ifBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0EA5E9, 0xFF6366F1});
        ifBg.setCornerRadius(dp(14));
        iconFrame.setBackground(ifBg);

        TextView iconTv = new TextView(ctx);
        FrameLayout.LayoutParams itLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        itLp.gravity = Gravity.CENTER;
        iconTv.setLayoutParams(itLp);
        iconTv.setText("🖼️");
        iconTv.setTextSize(22f);
        iconFrame.addView(iconTv);

        LinearLayout textBlock = new LinearLayout(ctx);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameTv = new TextView(ctx);
        nameTv.setText("Wallpaper");
        nameTv.setTextSize(15f);
        nameTv.setTextColor(0xFFFFFFFF);
        nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        TextView descTv = new TextView(ctx);
        descTv.setText("Set a custom background image");
        descTv.setTextSize(11.5f);
        descTv.setTextColor(0x99FFFFFF);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(3);
        descTv.setLayoutParams(descLp);

        textBlock.addView(nameTv);
        textBlock.addView(descTv);

        TextView arrow = new TextView(ctx);
        arrow.setText("›");
        arrow.setTextSize(24f);
        arrow.setTextColor(0x55FFFFFF);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        arLp.setMarginStart(dp(8));
        arrow.setLayoutParams(arLp);

        card.addView(iconFrame);
        card.addView(textBlock);
        card.addView(arrow);

        return card;
    }

    private GradientDrawable buildSheetBackground() {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1A2E, 0xFF0F0F1A});
        gd.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        return gd;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
