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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.TypingStyleManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * TypingStyleBottomSheet
 *
 * 55% screen height, scrollable inside, drag-down to close.
 */
public class TypingStyleBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "TypingStyleBottomSheet";

    public interface OnStyleSelectedListener {
        void onStyleSelected(int styleIndex);
    }

    private OnStyleSelectedListener listener;

    public static TypingStyleBottomSheet newInstance() {
        return new TypingStyleBottomSheet();
    }

    public void setOnStyleSelectedListener(OnStyleSelectedListener l) {
        this.listener = l;
    }

    private static final int[][] STYLE_COLORS = {
        {0xFF6366F1, 0xFF8B5CF6},  //  0 Normal
        {0xFF0EA5E9, 0xFF1D4ED8},  //  1 Bold
        {0xFFEC4899, 0xFFF43F5E},  //  2 Italic
        {0xFF7C3AED, 0xFFDB2777},  //  3 Bold Italic
        {0xFF0F766E, 0xFF0284C7},  //  4 Monospace
        {0xFF92400E, 0xFFD97706},  //  5 Serif
        {0xFF854D0E, 0xFFC2410C},  //  6 Serif Bold
        {0xFF374151, 0xFF6B7280},  //  7 Condensed
        {0xFF2563EB, 0xFF60A5FA},  //  8 Light
        {0xFF16A34A, 0xFF86EFAC},  //  9 Casual
        {0xFF7C3AED, 0xFFA855F7},  // 10 Medium
        {0xFF64748B, 0xFF94A3B8},  // 11 Thin
        {0xFFD97706, 0xFFF59E0B},  // 12 Serif Italic
        {0xFF0F172A, 0xFF1E293B},  // 13 Condensed Bold
        {0xFF111827, 0xFF374151},  // 14 Black/Heavy
        {0xFFDB2777, 0xFFEC4899},  // 15 Cursive
        {0xFF0EA5E9, 0xFF38BDF8},  // 16 Sans Medium
        {0xFF1D4ED8, 0xFF3B82F6},  // 17 Mono Bold
        {0xFF9333EA, 0xFFC084FC},  // 18 Light Italic
        {0xFF15803D, 0xFF4ADE80},  // 19 Classic Bold
        {0xFF0369A1, 0xFF0EA5E9},  // 20 Samsung One
        {0xFFDB2777, 0xFFF0ABFC},  // 21 Script ✨
        {0xFF6B21A8, 0xFF7C3AED},  // 22 Serif Condensed
        {0xFF065F46, 0xFF0D9488},  // 23 Mono Italic
        {0xFF1E40AF, 0xFF6366F1},  // 24 Condensed Light
        {0xFFB91C1C, 0xFFF97316},  // 25 Sans Bold Condensed
    };

    private static final String[] STYLE_DESC = {
        "Regular weight, default look",
        "Strong & heavy text",
        "Slanted & expressive",
        "Bold meets italic — extra punch",
        "Fixed-width, code-like",
        "Classic newspaper style",
        "Serif with power",
        "Narrow & compact",
        "Thin & airy",
        "Relaxed handwritten vibe",
        "Middle weight, balanced",
        "Ultra-thin hairline",
        "Classic slant with serifs",
        "Narrow & strong",
        "Maximum weight, ultra thick",
        "Flowing script style",
        "Medium sans — clean & solid",
        "Bold monospace, code power",
        "Light with graceful slant",
        "Bold italic — styled classic",
        "Samsung system font",
        "Stylish script ✨",
        "Compact classic serif",
        "Code style with slant",
        "Narrow airy feel",
        "Bold condensed impact",
    };

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
        hp2.setMargins(dp(20), 0, dp(20), dp(4));
        header.setLayoutParams(hp2);
        header.setText("✍️  Typing Style");
        header.setTextSize(17f);
        header.setTextColor(0xFFFFFFFF);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(header);

        // Rainbow divider
        View divider = new View(ctx);
        LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        divP.setMargins(dp(20), dp(6), dp(20), dp(10));
        divider.setLayoutParams(divP);
        divider.setBackground(buildRainbowDivider());
        root.addView(divider);

        // Scrollable list
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

        int currentStyle = TypingStyleManager.get(ctx).getCurrentStyle();
        int total = TypingStyleManager.STYLE_NAMES.length;

        for (int i = 0; i < total; i++) {
            list.addView(buildRow(ctx, i, currentStyle));
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

            // 55% screen height
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

    private View buildRow(Context ctx, int index, int currentStyle) {
        boolean isSelected = (index == currentStyle);
        int[] clrs = STYLE_COLORS[index % STYLE_COLORS.length];

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, dp(4), 0, dp(4));
        card.setLayoutParams(cp);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackground(buildRowBackground(clrs[0], clrs[1], isSelected));
        card.setMinimumHeight(dp(62));

        View swatch = new View(ctx);
        LinearLayout.LayoutParams swP = new LinearLayout.LayoutParams(dp(6), dp(38));
        swP.setMarginEnd(dp(14));
        swatch.setLayoutParams(swP);
        GradientDrawable swBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{clrs[0], clrs[1]});
        swBg.setCornerRadius(dp(3));
        swatch.setBackground(swBg);

        LinearLayout textBlock = new LinearLayout(ctx);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameTv = new TextView(ctx);
        nameTv.setText(TypingStyleManager.STYLE_NAMES[index]);
        nameTv.setTextSize(14.5f);
        nameTv.setTextColor(isSelected ? clrs[0] : 0xFFFFFFFF);
        nameTv.setTypeface(Typeface.create("sans-serif-medium",
                isSelected ? Typeface.BOLD : Typeface.NORMAL));

        TextView descTv = new TextView(ctx);
        descTv.setText(STYLE_DESC[index < STYLE_DESC.length ? index : 0]);
        descTv.setTextSize(11.5f);
        descTv.setTextColor(isSelected ? clrs[1] : 0x99FFFFFF);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(2);
        descTv.setLayoutParams(descLp);

        textBlock.addView(nameTv);
        textBlock.addView(descTv);

        TextView check = new TextView(ctx);
        check.setText("✓");
        check.setTextSize(18f);
        check.setTextColor(clrs[0]);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams chkLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        chkLp.setMarginStart(dp(8));
        check.setLayoutParams(chkLp);

        card.addView(swatch);
        card.addView(textBlock);
        card.addView(check);

        card.setOnClickListener(v -> {
            if (index == TypingStyleManager.STYLE_SAMSUNG) {
                dismiss();
                if (listener != null) listener.onStyleSelected(-1);
                return;
            }
            TypingStyleManager.get(ctx).setStyle(index);
            if (listener != null) listener.onStyleSelected(index);
            dismiss();
            Toast.makeText(ctx,
                    TypingStyleManager.STYLE_NAMES[index] + " applied!",
                    Toast.LENGTH_SHORT).show();
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

    private GradientDrawable buildRowBackground(int clrStart, int clrEnd, boolean selected) {
        GradientDrawable gd;
        if (selected) {
            int s = (clrStart & 0x00FFFFFF) | 0x30000000;
            int e = (clrEnd   & 0x00FFFFFF) | 0x15000000;
            gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{s, e, 0x05FFFFFF});
            gd.setStroke(dp(1), (clrStart & 0x00FFFFFF) | 0x66000000);
        } else {
            gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x14FFFFFF, 0x08FFFFFF});
        }
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
