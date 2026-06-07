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
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ChatCustomizationBottomSheet
 *
 * Single colorful bottom sheet with 5 customization options.
 * 55% screen height, scroll inside, drag-down to close.
 * Click on any option -> callback -> activity opens that specific picker.
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

    // Per-option gradient colours
    private static final int[][] OPTION_COLORS = {
        {0xFF0EA5E9, 0xFF6366F1},  // Wallpaper   — sky → indigo
        {0xFFEC4899, 0xFFF97316},  // Chat Theme  — pink → orange
        {0xFF16A34A, 0xFF0D9488},  // Bubble Shape — green → teal
        {0xFF7C3AED, 0xFFDB2777},  // Typing Style — purple → pink
        {0xFFF59E0B, 0xFFEF4444},  // Font Size   — amber → red
    };

    private static final String[] OPTION_ICONS = {
        "🖼️", "🎨", "💬", "✍️", "🔤"
    };

    private static final String[] OPTION_NAMES = {
        "Wallpaper",
        "Chat Theme",
        "Bubble Shape",
        "Typing Style",
        "Font Size",
    };

    private static final String[] OPTION_DESC = {
        "Set a custom background image or color",
        "Change sent & received bubble colors",
        "Customize the message bubble shape",
        "Pick your message font style",
        "Adjust message text size",
    };

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

        // ── Drag handle ────────────────────────────────────────────────
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

        // ── Header ─────────────────────────────────────────────────────
        TextView header = new TextView(ctx);
        LinearLayout.LayoutParams hp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hp2.setMargins(dp(20), 0, dp(20), dp(4));
        header.setLayoutParams(hp2);
        header.setText("🎨  Chat Customization");
        header.setTextSize(17f);
        header.setTextColor(0xFFFFFFFF);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(header);

        // ── Rainbow divider ─────────────────────────────────────────────
        View divider = new View(ctx);
        LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        divP.setMargins(dp(20), dp(6), dp(20), dp(10));
        divider.setLayoutParams(divP);
        divider.setBackground(buildRainbowDivider());
        root.addView(divider);

        // ── Scrollable list ─────────────────────────────────────────────
        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        listLp.setMargins(dp(12), 0, dp(12), dp(16));
        list.setLayoutParams(listLp);

        for (int i = 0; i < OPTION_NAMES.length; i++) {
            list.addView(buildRow(ctx, i));
        }

        scroll.addView(list);
        root.addView(scroll);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog d = (BottomSheetDialog) getDialog();
            BottomSheetBehavior<FrameLayout> behavior = d.getBehavior();

            DisplayMetrics dm = getResources().getDisplayMetrics();
            int sheetHeight = (int) (dm.heightPixels * 0.55f);

            behavior.setPeekHeight(sheetHeight, false);
            behavior.setMaxHeight(sheetHeight);
            behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            behavior.setSkipCollapsed(false);
            behavior.setHideable(true);
            behavior.setDraggable(true);
            behavior.setFitToContents(true);
        }
    }

    private View buildRow(Context ctx, int index) {
        int[] clrs = OPTION_COLORS[index];

        // Card
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, dp(5), 0, dp(5));
        card.setLayoutParams(cp);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackground(buildRowBackground(clrs[0], clrs[1]));
        card.setMinimumHeight(dp(72));

        // Icon bubble
        FrameLayout iconFrame = new FrameLayout(ctx);
        LinearLayout.LayoutParams ifLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        ifLp.setMarginEnd(dp(16));
        iconFrame.setLayoutParams(ifLp);
        GradientDrawable ifBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, clrs);
        ifBg.setCornerRadius(dp(14));
        iconFrame.setBackground(ifBg);

        TextView iconTv = new TextView(ctx);
        FrameLayout.LayoutParams itLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        itLp.gravity = Gravity.CENTER;
        iconTv.setLayoutParams(itLp);
        iconTv.setText(OPTION_ICONS[index]);
        iconTv.setTextSize(22f);
        iconFrame.addView(iconTv);

        // Text block
        LinearLayout textBlock = new LinearLayout(ctx);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameTv = new TextView(ctx);
        nameTv.setText(OPTION_NAMES[index]);
        nameTv.setTextSize(15f);
        nameTv.setTextColor(0xFFFFFFFF);
        nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        TextView descTv = new TextView(ctx);
        descTv.setText(OPTION_DESC[index]);
        descTv.setTextSize(11.5f);
        descTv.setTextColor(0x99FFFFFF);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(3);
        descTv.setLayoutParams(descLp);

        textBlock.addView(nameTv);
        textBlock.addView(descTv);

        // Arrow
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

        final int idx = index;
        card.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onOptionSelected(idx);
        });

        return card;
    }

    private GradientDrawable buildSheetBackground() {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1A2E, 0xFF0F0F1A});
        gd.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        return gd;
    }

    private GradientDrawable buildRowBackground(int clrStart, int clrEnd) {
        int s = (clrStart & 0x00FFFFFF) | 0x22000000;
        int e = (clrEnd   & 0x00FFFFFF) | 0x11000000;
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{s, e, 0x08FFFFFF});
        gd.setStroke(dp(1), (clrStart & 0x00FFFFFF) | 0x33000000);
        gd.setCornerRadius(dp(14));
        return gd;
    }

    private GradientDrawable buildRainbowDivider() {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFEC4899, 0xFFF97316, 0xFFEAB308,
                           0xFF22C55E, 0xFF06B6D4, 0xFF6366F1, 0xFFA855F7});
        gd.setCornerRadius(dp(1));
        return gd;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
