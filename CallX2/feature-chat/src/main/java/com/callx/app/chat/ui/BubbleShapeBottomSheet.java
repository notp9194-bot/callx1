package com.callx.app.chat.ui;

import android.content.Context;
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

import com.callx.app.utils.BubbleShapeManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * BubbleShapeBottomSheet
 *
 * 55% screen height, scrollable inside, drag-down to close.
 */
public class BubbleShapeBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "BubbleShapeBottomSheet";

    public interface OnShapeSelectedListener {
        void onShapeSelected(int shapeIndex);
    }

    private OnShapeSelectedListener listener;

    public static BubbleShapeBottomSheet newInstance() {
        return new BubbleShapeBottomSheet();
    }

    public void setOnShapeSelectedListener(OnShapeSelectedListener l) {
        this.listener = l;
    }

    private static final int[][] SHAPE_COLORS = {
        {0xFF6366F1, 0xFF8B5CF6},  //  0 Classic Rounded
        {0xFF10B981, 0xFF06B6D4},  //  1 Tail WhatsApp
        {0xFFEC4899, 0xFFF43F5E},  //  2 Pill
        {0xFF64748B, 0xFF94A3B8},  //  3 Square
        {0xFF8B5CF6, 0xFFD946EF},  //  4 Squircle
        {0xFFEF4444, 0xFFF97316},  //  5 Sharp Tail
        {0xFFF59E0B, 0xFFEAB308},  //  6 Double Tail
        {0xFF22C55E, 0xFF86EFAC},  //  7 Leaf
        {0xFF38BDF8, 0xFF818CF8},  //  8 Cloud
        {0xFF0EA5E9, 0xFF6366F1},  //  9 Diamond Cut
        {0xFF14B8A6, 0xFF06B6D4},  // 10 Teardrop
        {0xFFA78BFA, 0xFFC084FC},  // 11 Wave
        {0xFFFF6B6B, 0xFFFFE66D},  // 12 Notch
        {0xFF34D399, 0xFF6EE7B7},  // 13 Pebble
        {0xFF374151, 0xFF6B7280},  // 14 Sharp Edge
        {0xFFDB2777, 0xFF9333EA},  // 15 Ribbon
        {0xFFF97316, 0xFFFBBF24},  // 16 Shield
        {0xFF0284C7, 0xFF7C3AED},  // 17 Ticket
        {0xFF10B981, 0xFF0EA5E9},  // 18 Gem Cut
        {0xFF64748B, 0xFF38BDF8},  // 19 Soft Tail
        {0xFF7C3AED, 0xFFEC4899},  // 20 Bullet
        {0xFF0EA5E9, 0xFF14B8A6},  // 21 Raindrop
        {0xFFF59E0B, 0xFFEF4444},  // 22 Toast
        {0xFF6366F1, 0xFF06B6D4},  // 23 Arch
        {0xFFDB2777, 0xFFEAB308},  // 24 Bowtie
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
        header.setText("💬  Bubble Shape");
        header.setTextSize(17f);
        header.setTextColor(0xFFFFFFFF);
        header.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        root.addView(header);

        // Rainbow divider
        View dividerTop = new View(ctx);
        LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        divP.setMargins(dp(20), dp(6), dp(20), dp(10));
        dividerTop.setLayoutParams(divP);
        dividerTop.setBackground(buildRainbowDivider());
        root.addView(dividerTop);

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

        int currentShape = BubbleShapeManager.get(ctx).getCurrentShape();
        int total = BubbleShapeManager.SHAPE_NAMES.length;

        for (int i = 0; i < total; i++) {
            list.addView(buildRow(ctx, i, currentShape));
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

    private View buildRow(Context ctx, int index, int currentShape) {
        boolean isSelected = (index == currentShape);
        int[] clrs = SHAPE_COLORS[index % SHAPE_COLORS.length];

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
        nameTv.setText(BubbleShapeManager.SHAPE_NAMES[index]);
        nameTv.setTextSize(14.5f);
        nameTv.setTextColor(isSelected ? clrs[0] : 0xFFFFFFFF);
        nameTv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                isSelected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));

        TextView descTv = new TextView(ctx);
        descTv.setText(BubbleShapeManager.SHAPE_DESC[index]);
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
        check.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
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
            BubbleShapeManager.get(ctx).setShape(index);
            if (listener != null) listener.onShapeSelected(index);
            dismiss();
            Toast.makeText(ctx,
                    BubbleShapeManager.SHAPE_NAMES[index] + " applied!",
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
