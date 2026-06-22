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

public class ChatThemeBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ChatThemeBottomSheet";

    public interface OnThemeSelectedListener {
        void onThemeSelected(int themeIndex);
    }

    private OnThemeSelectedListener listener;

    public static ChatThemeBottomSheet newInstance() {
        return new ChatThemeBottomSheet();
    }

    public void setOnThemeSelectedListener(OnThemeSelectedListener l) {
        this.listener = l;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(buildSheetBg());
        root.setPadding(dp(20), 0, dp(20), dp(24));

        View handle = new View(ctx);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(40), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        FrameLayout handleBar = new FrameLayout(ctx);
        handleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        handle.setLayoutParams(hp);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(0x44FFFFFF);
        hBg.setCornerRadius(dp(2));
        handle.setBackground(hBg);
        handleBar.addView(handle);
        root.addView(handleBar);

        TextView title = new TextView(ctx);
        title.setText("Bubble Theme");
        title.setTextSize(16f);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(16);
        title.setLayoutParams(tlp);
        root.addView(title);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0x22FFFFFF);
        cardBg.setCornerRadius(dp(12));
        cardBg.setStroke(dp(1), 0x441E293B);
        card.setBackground(cardBg);

        View dot = new View(ctx);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMarginEnd(dp(14));
        dotLp.gravity = Gravity.CENTER_VERTICAL;
        dot.setLayoutParams(dotLp);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xFF1E293B);
        dot.setBackground(dotBg);
        card.addView(dot);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(ctx);
        name.setText("Midnight");
        name.setTextSize(14f);
        name.setTextColor(0xFFFFFFFF);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        textCol.addView(name);

        TextView desc = new TextView(ctx);
        desc.setText("Clean dark theme — active");
        desc.setTextSize(11.5f);
        desc.setTextColor(0x99FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(2);
        desc.setLayoutParams(dlp);
        textCol.addView(desc);

        card.addView(textCol);

        TextView check = new TextView(ctx);
        check.setText("✓");
        check.setTextSize(18f);
        check.setTextColor(0xFF60A5FA);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(check);

        root.addView(card);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            BottomSheetBehavior<FrameLayout> behavior = d.getBehavior();
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int h = (int) (dm.heightPixels * 0.35f);
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
