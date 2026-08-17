package com.callx.app.utils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * RainbowStripColorPickerBottomSheet — the "common rainbow box" for simple
 * single-color picks on a strip/pill (no ring-mode toggle — that richer
 * picker stays in {@code HighlightRingColorPickerBottomSheet}).
 *
 * Advanced picker with 3 modes, switchable via a tab row (matches the
 * familiar GRID / SPECTRUM / SLIDERS pattern):
 *   - GRID     — quick preset swatches + last-used colors, one tap
 *   - SPECTRUM — the original "point anywhere in the box" rainbow square
 *   - SLIDERS  — precise RED/GREEN/BLUE sliders with numeric boxes, a live
 *                HEX field, and an opacity slider (checkerboard track) —
 *                {@link RainbowColorSlidersView}
 *
 * All three modes share one underlying color, so switching tabs never loses
 * what was picked. Both {@code RainbowColorSlidersView} and
 * {@code RainbowColorPickerView} are shared :core widgets, so "point
 * anywhere, that color applies" (and now, "drag a slider, or type a hex")
 * behaves identically everywhere this sheet is used:
 *   - feature-reels UserReelsActivity strip colors (profile-song pill /
 *     "Add a song" stub) — long-press either strip to open this sheet.
 *   - feature-chat MediaEditActivity (drawing/text colors), RainbowColorDotView
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

    private static final int[] QUICK_PALETTE = {
        0xFFFF3B5C, 0xFFFF6B6B, 0xFFFF9500, 0xFFFFD700,
        0xFF4CD964, 0xFF00D4AA, 0xFF34C3EB, 0xFF4A90E2,
        0xFF5856D6, 0xFF9B59B6, 0xFFE056A0, 0xFF8E8E93,
        0xFFFFFFFF, 0xFF1C1C1E, 0xFF5B5BF6, 0xFFFF2D55
    };

    private static final int MAX_RECENTS = 8;
    private static final java.util.LinkedList<Integer> recentColors = new java.util.LinkedList<>();

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
        sub.setText("Pick a preset, tap the box, or fine-tune with sliders");
        sub.setTextSize(13);
        sub.setTextColor(Color.GRAY);
        sub.setPadding(0, 0, 0, dp(ctx, 14));
        root.addView(sub);

        int startColor = (currentColorHex != null && !currentColorHex.isEmpty())
                ? safeColor(currentColorHex) : Color.parseColor("#5B5BF6");
        int[] selectedColor = { startColor };

        // ── Tab row: GRID | SPECTRUM | SLIDERS ──────────────────────────
        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, 0, 0, dp(ctx, 12));
        TextView tabGrid = tabButton(ctx, "Grid");
        TextView tabSpectrum = tabButton(ctx, "Spectrum");
        TextView tabSliders = tabButton(ctx, "Sliders");
        for (TextView t : new TextView[]{ tabGrid, tabSpectrum, tabSliders }) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(dp(ctx, 6));
            t.setLayoutParams(lp);
            tabRow.addView(t);
        }
        root.addView(tabRow);

        // ── Content container (holds all 3 modes, only one visible) ─────
        FrameLayout content = new FrameLayout(ctx);

        // GRID mode
        LinearLayout gridPanel = new LinearLayout(ctx);
        gridPanel.setOrientation(LinearLayout.VERTICAL);
        GridLayout swatchGrid = buildSwatchGrid(ctx, QUICK_PALETTE, "Presets", color -> {
            selectedColor[0] = color;
        });
        gridPanel.addView(swatchGrid);
        GridLayout recentGrid = null;
        if (!recentColors.isEmpty()) {
            int[] recentsArr = new int[recentColors.size()];
            for (int i = 0; i < recentsArr.length; i++) recentsArr[i] = recentColors.get(i);
            TextView recentsLabel = new TextView(ctx);
            recentsLabel.setText("Recent");
            recentsLabel.setTextSize(12);
            recentsLabel.setTextColor(Color.GRAY);
            recentsLabel.setPadding(0, dp(ctx, 10), 0, dp(ctx, 6));
            gridPanel.addView(recentsLabel);
            recentGrid = buildSwatchGrid(ctx, recentsArr, "Recent", color -> selectedColor[0] = color);
            gridPanel.addView(recentGrid);
        }
        content.addView(gridPanel);

        // SPECTRUM mode
        RainbowColorPickerView rainbowPicker = new RainbowColorPickerView(ctx);
        GradientDrawable rainbowClip = new GradientDrawable();
        rainbowClip.setCornerRadius(dp(ctx, 10));
        rainbowClip.setColor(Color.BLACK);
        rainbowPicker.setBackground(rainbowClip);
        rainbowPicker.setClipToOutline(true);
        FrameLayout.LayoutParams rainbowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(ctx, 150));
        rainbowPicker.setVisibility(View.GONE);
        content.addView(rainbowPicker, rainbowLp);
        rainbowPicker.post(() -> rainbowPicker.setInitialColor(selectedColor[0]));
        rainbowPicker.setOnColorPickListener(color -> selectedColor[0] = color);

        // SLIDERS mode
        RainbowColorSlidersView slidersView = new RainbowColorSlidersView(ctx);
        slidersView.setVisibility(View.GONE);
        content.addView(slidersView);
        slidersView.setOnColorChangeListener(color -> selectedColor[0] = color);

        root.addView(content);

        // ── Tab switching (syncs the shared color into whichever view becomes visible) ──
        Runnable[] showTab = new Runnable[1];
        int[] activeTab = { 1 }; // 0=grid, 1=spectrum, 2=sliders — spectrum stays the default, familiar entry point
        showTab[0] = () -> {
            gridPanel.setVisibility(activeTab[0] == 0 ? View.VISIBLE : View.GONE);
            rainbowPicker.setVisibility(activeTab[0] == 1 ? View.VISIBLE : View.GONE);
            slidersView.setVisibility(activeTab[0] == 2 ? View.VISIBLE : View.GONE);
            styleTab(tabGrid, activeTab[0] == 0);
            styleTab(tabSpectrum, activeTab[0] == 1);
            styleTab(tabSliders, activeTab[0] == 2);
            if (activeTab[0] == 1) rainbowPicker.setInitialColor(selectedColor[0]);
            if (activeTab[0] == 2) slidersView.setColor(selectedColor[0]);
        };
        tabGrid.setOnClickListener(v -> { activeTab[0] = 0; showTab[0].run(); });
        tabSpectrum.setOnClickListener(v -> { activeTab[0] = 1; showTab[0].run(); });
        tabSliders.setOnClickListener(v -> { activeTab[0] = 2; showTab[0].run(); });
        showTab[0].run();

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
            checkLp.topMargin = dp(ctx, 12);
            checkLp.bottomMargin = dp(ctx, 8);
            root.addView(applyAllCheck, checkLp);
        }
        final android.widget.CheckBox fApplyAllCheck = applyAllCheck;
        if (!showApplyAllOption) {
            LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            applyLp.topMargin = dp(ctx, 14);
            apply.setLayoutParams(applyLp);
        }

        apply.setOnClickListener(v -> {
            String hex = toHex(selectedColor[0]);
            boolean applyToAll = fApplyAllCheck != null && fApplyAllCheck.isChecked();
            rememberRecent(selectedColor[0]);
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

    // ── helpers ──────────────────────────────────────────────────────────

    private interface OnSwatchTap { void onTap(int color); }

    private static GridLayout buildSwatchGrid(Context ctx, int[] colors, String contentDesc, OnSwatchTap onTap) {
        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(8);
        for (int color : colors) {
            FrameLayout cell = new FrameLayout(ctx);
            int size = dp(ctx, 34);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size; lp.height = size;
            lp.setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
            cell.setLayoutParams(lp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(color);
            gd.setStroke(dp(ctx, 1), 0x22000000);
            cell.setBackground(gd);
            cell.setOnClickListener(v -> onTap.onTap(color));
            grid.addView(cell);
        }
        return grid;
    }

    private static void rememberRecent(int color) {
        recentColors.remove(Integer.valueOf(color));
        recentColors.addFirst(color);
        while (recentColors.size() > MAX_RECENTS) recentColors.removeLast();
    }

    private static TextView tabButton(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        return t;
    }

    private static void styleTab(TextView t, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(t.getContext(), 8));
        bg.setColor(selected ? Color.parseColor("#6200EE") : Color.parseColor("#F0F0F0"));
        t.setBackground(bg);
        t.setTextColor(selected ? Color.WHITE : Color.parseColor("#333333"));
        t.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    /** #RRGGBB when fully opaque (keeps every existing caller working exactly
     *  as before), #AARRGGBB when the opacity slider was used — both formats
     *  are accepted by {@code Color.parseColor}. */
    private static String toHex(int argb) {
        int a = Color.alpha(argb);
        return a >= 255
                ? String.format("#%06X", (0xFFFFFF & argb))
                : String.format("#%08X", argb);
    }

    private static int safeColor(String hex) {
        try { return Color.parseColor(hex); } catch (Exception e) { return Color.parseColor("#5B5BF6"); }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
