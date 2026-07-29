package com.callx.app.utils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * RainbowStripColorPickerBottomSheet — the "common rainbow box" for simple
 * single-color picks on a strip/pill (no swatch grid, no ring-mode toggle —
 * that richer picker stays in {@code HighlightRingColorPickerBottomSheet}).
 *
 * Both pickers share the same underlying {@link RainbowColorPickerView}, so
 * "point anywhere, that color applies" behaves identically everywhere it's
 * used:
 *   - feature-status highlight ring colors (via HighlightRingColorPickerBottomSheet)
 *   - feature-reels UserReelsActivity strip colors (profile-song pill /
 *     "Add a song" stub) — long-press either strip to open this sheet.
 */
public class RainbowStripColorPickerBottomSheet {

    public interface OnPickListener {
        /** colorHex is null when the user chose "Use default". */
        void onPicked(@Nullable String colorHex);
    }

    /** Like {@link OnPickListener} but also reports whether "Apply to all" was checked. */
    public interface OnPickListenerScoped {
        /** colorHex is null when the user chose "Use default". */
        void onPicked(@Nullable String colorHex, boolean applyToAll);
    }

    public static void show(Context ctx, String title, @Nullable String currentColorHex,
                             boolean allowReset, OnPickListener listener) {
        show(ctx, title, currentColorHex, allowReset, false, null,
                (colorHex, applyToAll) -> { if (listener != null) listener.onPicked(colorHex); });
    }

    /**
     * @param showApplyAllOption when true, adds an "Apply to all {applyAllLabel}" checkbox
     *                            above the Apply button; its final state is passed back via
     *                            {@link OnPickListenerScoped#onPicked}.
     * @param applyAllLabel      plural noun shown in the checkbox text (e.g. "bio strips");
     *                            ignored when showApplyAllOption is false.
     */
    public static void show(Context ctx, String title, @Nullable String currentColorHex,
                             boolean allowReset, boolean showApplyAllOption,
                             @Nullable String applyAllLabel, OnPickListenerScoped listener) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 28));

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextSize(18);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(0, 0, 0, dp(ctx, 4));
        root.addView(titleView);

        TextView sub = new TextView(ctx);
        sub.setText("Tap anywhere in the box below for any color");
        sub.setTextSize(13);
        sub.setTextColor(Color.GRAY);
        sub.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(sub);

        int startColor = (currentColorHex != null && !currentColorHex.isEmpty())
                ? safeColor(currentColorHex) : Color.parseColor("#5B5BF6");
        int[] selectedColor = { startColor };

        RainbowColorPickerView rainbowPicker = new RainbowColorPickerView(ctx);
        GradientDrawable rainbowClip = new GradientDrawable();
        rainbowClip.setCornerRadius(dp(ctx, 10));
        rainbowClip.setColor(Color.BLACK);
        rainbowPicker.setBackground(rainbowClip);
        rainbowPicker.setClipToOutline(true);
        LinearLayout.LayoutParams rainbowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 150));
        rainbowLp.bottomMargin = dp(ctx, 20);
        root.addView(rainbowPicker, rainbowLp);
        rainbowPicker.post(() -> rainbowPicker.setInitialColor(selectedColor[0]));
        rainbowPicker.setOnColorPickListener(color -> selectedColor[0] = color);

        Button apply = new Button(ctx);
        apply.setText("Apply");
        apply.setAllCaps(false);

        android.widget.CheckBox applyAllCheck = null;
        if (showApplyAllOption) {
            applyAllCheck = new android.widget.CheckBox(ctx);
            applyAllCheck.setText("Apply to all " + (applyAllLabel != null ? applyAllLabel : "strips"));
            applyAllCheck.setTextSize(13);
            LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            checkLp.bottomMargin = dp(ctx, 8);
            root.addView(applyAllCheck, checkLp);
        }
        final android.widget.CheckBox fApplyAllCheck = applyAllCheck;

        apply.setOnClickListener(v -> {
            String hex = String.format("#%06X", (0xFFFFFF & selectedColor[0]));
            boolean applyToAll = fApplyAllCheck != null && fApplyAllCheck.isChecked();
            if (listener != null) listener.onPicked(hex, applyToAll);
            sheet.dismiss();
        });
        root.addView(apply);

        if (allowReset) {
            TextView reset = new TextView(ctx);
            reset.setText("Use default");
            reset.setTextSize(13);
            reset.setGravity(Gravity.CENTER);
            reset.setTextColor(Color.parseColor("#6200EE"));
            reset.setPadding(0, dp(ctx, 14), 0, 0);
            reset.setOnClickListener(v -> {
                boolean applyToAll = fApplyAllCheck != null && fApplyAllCheck.isChecked();
                if (listener != null) listener.onPicked(null, applyToAll);
                sheet.dismiss();
            });
            root.addView(reset);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    private static int safeColor(String hex) {
        try { return Color.parseColor(hex); } catch (Exception e) { return Color.parseColor("#5B5BF6"); }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
