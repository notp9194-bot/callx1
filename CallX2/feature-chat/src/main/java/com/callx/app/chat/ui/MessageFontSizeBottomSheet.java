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

import com.callx.app.utils.MessageFontSizeManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * MessageFontSizeBottomSheet — simplified.
 * Font size is fixed at 15sp. Shows a single info card only.
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
        float density = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 28));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1A2E, 0xFF0F0F1A});
        bg.setCornerRadii(new float[]{dp(ctx,24),dp(ctx,24),dp(ctx,24),dp(ctx,24),0,0,0,0});
        root.setBackground(bg);

        // Drag handle
        FrameLayout handleBar = new FrameLayout(ctx);
        handleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 28)));
        View handle = new View(ctx);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(ctx, 40), dp(ctx, 4));
        hp.gravity = Gravity.CENTER;
        handle.setLayoutParams(hp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x55FFFFFF);
        handleBg.setCornerRadius(dp(ctx, 2));
        handle.setBackground(handleBg);
        handleBar.addView(handle);
        root.addView(handleBar);

        // Header
        TextView header = new TextView(ctx);
        LinearLayout.LayoutParams hp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hp2.bottomMargin = dp(ctx, 16);
        header.setLayoutParams(hp2);
        header.setText("\uD83D\uDD24  Message Font Size");
        header.setTextSize(17f);
        header.setTextColor(0xFFFFFFFF);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(header);

        // Info card — single fixed size
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cp);
        card.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16));
        GradientDrawable cardBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x300EA5E9, 0x156366F1});
        cardBg.setCornerRadius(dp(ctx, 14));
        cardBg.setStroke(dp(ctx, 1), 0x660EA5E9);
        card.setBackground(cardBg);

        // "Aa" preview
        FrameLayout previewFrame = new FrameLayout(ctx);
        LinearLayout.LayoutParams pfLp = new LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56));
        pfLp.setMarginEnd(dp(ctx, 14));
        previewFrame.setLayoutParams(pfLp);
        GradientDrawable pfBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF0EA5E9, 0xFF6366F1});
        pfBg.setCornerRadius(dp(ctx, 14));
        previewFrame.setBackground(pfBg);
        TextView previewTv = new TextView(ctx);
        FrameLayout.LayoutParams ptLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        ptLp.gravity = Gravity.CENTER;
        previewTv.setLayoutParams(ptLp);
        previewTv.setText("Aa");
        previewTv.setTextSize(16f);
        previewTv.setTextColor(0xFFFFFFFF);
        previewTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        previewFrame.addView(previewTv);

        // Text block
        LinearLayout textBlock = new LinearLayout(ctx);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameTv = new TextView(ctx);
        nameTv.setText("Medium  •  15sp");
        nameTv.setTextSize(14.5f);
        nameTv.setTextColor(0xFF0EA5E9);
        nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        TextView descTv = new TextView(ctx);
        descTv.setText("Default — balanced readability");
        descTv.setTextSize(11.5f);
        descTv.setTextColor(0x996366F1);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(ctx, 3);
        descTv.setLayoutParams(descLp);

        textBlock.addView(nameTv);
        textBlock.addView(descTv);

        // Check icon
        TextView check = new TextView(ctx);
        check.setText("✓");
        check.setTextSize(18f);
        check.setTextColor(0xFF0EA5E9);
        check.setTypeface(Typeface.DEFAULT_BOLD);

        card.addView(previewFrame);
        card.addView(textBlock);
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
            int sheetHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.35f);
            behavior.setPeekHeight(sheetHeight, false);
            behavior.setMaxHeight(sheetHeight);
            behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            behavior.setHideable(true);
            behavior.setDraggable(true);
            behavior.setFitToContents(true);
        }
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
