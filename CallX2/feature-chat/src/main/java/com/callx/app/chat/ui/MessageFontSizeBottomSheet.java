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

import com.callx.app.utils.MessageFontSizeManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * MessageFontSizeBottomSheet
 *
 * 4 size options (Small / Medium / Large / Extra Large).
 * Same colorful dark style as ChatTheme / BubbleShape / TypingStyle sheets.
 * 55% screen height, scroll inside, drag-down to close.
 */
public class MessageFontSizeBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "MessageFontSizeBottomSheet";

    public interface OnSizeSelectedListener {
        void onSizeSelected(int sizeIndex);
    }

    private OnSizeSelectedListener listener;

    public static MessageFontSizeBottomSheet newInstance() {
        return new MessageFontSizeBottomSheet();
    }

    public void setOnSizeSelectedListener(OnSizeSelectedListener l) {
        this.listener = l;
    }

    // Per-size gradient colours
    private static final int[][] SIZE_COLORS = {
        {0xFF64748B, 0xFF94A3B8},  // Small       — slate → light slate
        {0xFF0EA5E9, 0xFF6366F1},  // Medium      — sky → indigo
        {0xFF16A34A, 0xFF0D9488},  // Large       — green → teal
        {0xFFEC4899, 0xFFF97316},  // Extra Large — pink → orange
    };

    // Preview text size shown inside each card
    private static final float[] PREVIEW_SP = {13f, 16f, 20f, 26f};
    // Preview label shown with growing text
    private static final String[] PREVIEW_TEXT = {"Aa", "Aa", "Aa", "Aa"};

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
        hp2.setMargins(dp(20), 0, dp(20), dp(4));
        header.setLayoutParams(hp2);
        header.setText("🔤  Message Font Size");
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

        int current = MessageFontSizeManager.get(ctx).getCurrentSize();

        for (int i = 0; i < MessageFontSizeManager.SIZE_NAMES.length; i++) {
            list.addView(buildRow(ctx, i, current));
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

    private View buildRow(Context ctx, int index, int current) {
        boolean isSelected = (index == current);
        int[] clrs = SIZE_COLORS[index];

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
        card.setBackground(buildRowBackground(clrs[0], clrs[1], isSelected));
        card.setMinimumHeight(dp(72));

        // "Aa" preview bubble — shows actual size
        FrameLayout previewFrame = new FrameLayout(ctx);
        LinearLayout.LayoutParams pfLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        pfLp.setMarginEnd(dp(14));
        previewFrame.setLayoutParams(pfLp);
        GradientDrawable pfBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, clrs);
        pfBg.setCornerRadius(dp(14));
        previewFrame.setBackground(pfBg);

        TextView previewTv = new TextView(ctx);
        FrameLayout.LayoutParams ptLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        ptLp.gravity = Gravity.CENTER;
        previewTv.setLayoutParams(ptLp);
        previewTv.setText("Aa");
        previewTv.setTextSize(PREVIEW_SP[index]);
        previewTv.setTextColor(0xFFFFFFFF);
        previewTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        previewFrame.addView(previewTv);

        // Text block
        LinearLayout textBlock = new LinearLayout(ctx);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameTv = new TextView(ctx);
        nameTv.setText(MessageFontSizeManager.SIZE_NAMES[index]);
        nameTv.setTextSize(14.5f);
        nameTv.setTextColor(isSelected ? clrs[0] : 0xFFFFFFFF);
        nameTv.setTypeface(Typeface.create("sans-serif-medium",
                isSelected ? Typeface.BOLD : Typeface.NORMAL));

        TextView descTv = new TextView(ctx);
        descTv.setText(MessageFontSizeManager.SIZE_DESC[index]);
        descTv.setTextSize(11.5f);
        descTv.setTextColor(isSelected ? clrs[1] : 0x99FFFFFF);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(3);
        descTv.setLayoutParams(descLp);

        // Size label (e.g., "15sp")
        TextView sizeLbl = new TextView(ctx);
        sizeLbl.setText(((int) MessageFontSizeManager.spForIndex(index)) + "sp");
        sizeLbl.setTextSize(10.5f);
        sizeLbl.setTextColor(0x66FFFFFF);
        LinearLayout.LayoutParams slLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slLp.topMargin = dp(2);
        sizeLbl.setLayoutParams(slLp);

        textBlock.addView(nameTv);
        textBlock.addView(descTv);
        textBlock.addView(sizeLbl);

        // Check indicator
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

        card.addView(previewFrame);
        card.addView(textBlock);
        card.addView(check);

        card.setOnClickListener(v -> {
            MessageFontSizeManager.get(ctx).setSize(index);
            if (listener != null) listener.onSizeSelected(index);
            dismiss();
            Toast.makeText(ctx,
                    MessageFontSizeManager.SIZE_NAMES[index] + " applied!",
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
