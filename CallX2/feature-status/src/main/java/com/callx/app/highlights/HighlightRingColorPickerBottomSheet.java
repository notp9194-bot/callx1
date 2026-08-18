package com.callx.app.highlights;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.callx.app.utils.HighlightRingDrawable;
// RainbowColorPickerView now lives in :core as a shared "common rainbow box" —
// feature-reels' UserReelsActivity strip color pickers reuse the exact same
// view instead of each module keeping its own copy.
import com.callx.app.utils.RainbowColorPickerView;

/**
 * HighlightRingColorPickerBottomSheet — lets the user pick a custom ring
 * color for a highlight album, in one of two modes:
 *
 *   "Solid Color"       (HighlightRingDrawable.MODE_SOLID)    — the ring is
 *                        exactly the picked color, flat, no blending.
 *   "Dominant Gradient" (HighlightRingDrawable.MODE_DOMINANT) — a gradient
 *                        ring that mixes rainbow hue accents in, while the
 *                        picked color still covers most of the ring.
 *
 * Color can be picked two ways: from the quick preset swatch grid, or from
 * the RainbowColorPickerView box below it — the whole box is a rainbow
 * (hue left→right, white→hue→black top→bottom) and tapping any point
 * applies that exact color, deselecting the swatch grid.
 *
 * Used from:
 *   - StatusAddToHighlightBottomSheet (choosing a color while creating a new
 *     album)
 *   - StatusHighlightSettingsBottomSheet (changing the color of an existing
 *     album, alongside Rename / Change Cover / Delete)
 */
public class HighlightRingColorPickerBottomSheet {

    public interface OnPickListener {
        /** colorHex/mode are both null when the user chose "Use default ring". */
        void onPicked(@Nullable String colorHex, @Nullable String mode);
    }

    private static final int[] PALETTE = {
        0xFFFF3B5C, 0xFFFF6B6B, 0xFFFF9500, 0xFFFFD700,
        0xFF4CD964, 0xFF00D4AA, 0xFF34C3EB, 0xFF4A90E2,
        0xFF5856D6, 0xFF9B59B6, 0xFFE056A0, 0xFF8E8E93
    };

    public static void show(Context ctx, @Nullable String currentColorHex,
                            @Nullable String currentMode, boolean allowReset,
                            OnPickListener listener) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 28));

        TextView title = new TextView(ctx);
        title.setText("Ring Color");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(ctx, 4));
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("Choose a color for this highlight's ring");
        sub.setTextSize(13);
        sub.setTextColor(Color.GRAY);
        sub.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(sub);

        // ── Live preview ring ───────────────────────────────────────────
        int[] selectedColor = { currentColorHex != null && !currentColorHex.isEmpty()
                ? safeColor(currentColorHex) : PALETTE[0] };
        boolean[] selectedDominant = { HighlightRingDrawable.MODE_DOMINANT.equals(currentMode) };

        FrameLayout previewFrame = new FrameLayout(ctx);
        FrameLayout.LayoutParams pfLp = new FrameLayout.LayoutParams(dp(ctx, 70), dp(ctx, 70));
        pfLp.gravity = Gravity.CENTER_HORIZONTAL;
        pfLp.bottomMargin = dp(ctx, 16);
        previewFrame.setLayoutParams(pfLp);
        View previewRing = new View(ctx);
        previewRing.setLayoutParams(new FrameLayout.LayoutParams(dp(ctx, 70), dp(ctx, 70)));
        previewFrame.addView(previewRing);
        View previewGap = new View(ctx);
        FrameLayout.LayoutParams gapLp = new FrameLayout.LayoutParams(dp(ctx, 66), dp(ctx, 66));
        gapLp.gravity = Gravity.CENTER;
        GradientDrawable gapBg = new GradientDrawable();
        gapBg.setShape(GradientDrawable.OVAL);
        gapBg.setColor(Color.WHITE);
        previewGap.setBackground(gapBg);
        previewGap.setLayoutParams(gapLp);
        previewFrame.addView(previewGap);
        View previewCenter = new View(ctx);
        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(dp(ctx, 58), dp(ctx, 58));
        centerLp.gravity = Gravity.CENTER;
        GradientDrawable centerBg = new GradientDrawable();
        centerBg.setShape(GradientDrawable.OVAL);
        centerBg.setColor(Color.parseColor("#EEEEEE"));
        previewCenter.setBackground(centerBg);
        previewCenter.setLayoutParams(centerLp);
        previewFrame.addView(previewCenter);
        root.addView(previewFrame);

        Runnable[] refreshPreview = new Runnable[1];
        refreshPreview[0] = () -> previewRing.setBackground(
                HighlightRingDrawable.withStrokeDp(selectedColor[0],
                        selectedDominant[0] ? HighlightRingDrawable.MODE_DOMINANT : HighlightRingDrawable.MODE_SOLID,
                        4f, ctx.getResources().getDisplayMetrics().density));
        refreshPreview[0].run();

        // ── Mode toggle ─────────────────────────────────────────────────
        LinearLayout modeRow = new LinearLayout(ctx);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, 0, 0, dp(ctx, 16));
        Button btnSolid = modeButton(ctx, "Solid Color");
        Button btnDominant = modeButton(ctx, "Dominant Gradient");
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        modeLp.setMarginEnd(dp(ctx, 8));
        btnSolid.setLayoutParams(modeLp);
        btnDominant.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        modeRow.addView(btnSolid);
        modeRow.addView(btnDominant);
        root.addView(modeRow);

        Runnable[] refreshModeButtons = new Runnable[1];
        refreshModeButtons[0] = () -> {
            styleModeButton(btnSolid, !selectedDominant[0]);
            styleModeButton(btnDominant, selectedDominant[0]);
        };
        refreshModeButtons[0].run();

        // ── Color swatch grid ───────────────────────────────────────────
        TextView swatchLabel = new TextView(ctx);
        swatchLabel.setText(selectedDominant[0] ? "Dominant Color" : "Color");
        swatchLabel.setTextSize(13);
        swatchLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        swatchLabel.setPadding(0, 0, 0, dp(ctx, 8));
        root.addView(swatchLabel);

        btnSolid.setOnClickListener(v -> {
            selectedDominant[0] = false;
            swatchLabel.setText("Color");
            refreshModeButtons[0].run();
            refreshPreview[0].run();
        });
        btnDominant.setOnClickListener(v -> {
            selectedDominant[0] = true;
            swatchLabel.setText("Dominant Color");
            refreshModeButtons[0].run();
            refreshPreview[0].run();
        });

        RecyclerView rvColors = new RecyclerView(ctx);
        rvColors.setLayoutManager(new GridLayoutManager(ctx, 6));
        int[] swatchSelectedPos = { indexOf(PALETTE, selectedColor[0]) };
        SwatchAdapter swatchAdapter = new SwatchAdapter(PALETTE, swatchSelectedPos[0], (color, pos) -> {
            selectedColor[0] = color;
            refreshPreview[0].run();
        });
        rvColors.setAdapter(swatchAdapter);
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rvLp.bottomMargin = dp(ctx, 12);
        root.addView(rvColors, rvLp);

        // ── Rainbow "point anywhere" box — pick ANY color, not just presets ──
        TextView rainbowLabel = new TextView(ctx);
        rainbowLabel.setText("Or tap anywhere for any color");
        rainbowLabel.setTextSize(12);
        rainbowLabel.setTextColor(Color.GRAY);
        rainbowLabel.setPadding(0, 0, 0, dp(ctx, 8));
        root.addView(rainbowLabel);

        RainbowColorPickerView rainbowPicker = new RainbowColorPickerView(ctx);
        GradientDrawable rainbowClip = new GradientDrawable();
        rainbowClip.setCornerRadius(dp(ctx, 10));
        rainbowClip.setColor(Color.BLACK);
        rainbowPicker.setBackground(rainbowClip);
        rainbowPicker.setClipToOutline(true);
        LinearLayout.LayoutParams rainbowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 130));
        rainbowLp.bottomMargin = dp(ctx, 20);
        root.addView(rainbowPicker, rainbowLp);
        rainbowPicker.post(() -> rainbowPicker.setInitialColor(selectedColor[0]));
        rainbowPicker.setOnColorPickListener(color -> {
            selectedColor[0] = color;
            swatchAdapter.setSelectedColor(-1); // deselect presets — an exact custom color is now chosen
            refreshPreview[0].run();
        });

        // ── Precise sliders — RGB + HEX + opacity, same advanced widget used
        // by RainbowStripColorPickerBottomSheet in feature-reels/feature-chat ──
        com.callx.app.utils.RainbowColorSlidersView slidersView =
                new com.callx.app.utils.RainbowColorSlidersView(ctx);
        slidersView.setVisibility(View.GONE);
        LinearLayout.LayoutParams slidersLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slidersLp.bottomMargin = dp(ctx, 16);
        root.addView(slidersView, slidersLp);
        slidersView.setOnColorChangeListener(color -> {
            selectedColor[0] = color;
            swatchAdapter.setSelectedColor(-1);
            refreshPreview[0].run();
        });

        TextView toggleSliders = new TextView(ctx);
        toggleSliders.setText("Use precise sliders");
        toggleSliders.setTextSize(13);
        toggleSliders.setTextColor(Color.parseColor("#6200EE"));
        toggleSliders.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(toggleSliders);
        boolean[] slidersMode = { false };
        toggleSliders.setOnClickListener(v -> {
            slidersMode[0] = !slidersMode[0];
            rvColors.setVisibility(slidersMode[0] ? View.GONE : View.VISIBLE);
            rainbowLabel.setVisibility(slidersMode[0] ? View.GONE : View.VISIBLE);
            rainbowPicker.setVisibility(slidersMode[0] ? View.GONE : View.VISIBLE);
            slidersView.setVisibility(slidersMode[0] ? View.VISIBLE : View.GONE);
            toggleSliders.setText(slidersMode[0] ? "Back to presets & spectrum" : "Use precise sliders");
            if (slidersMode[0]) slidersView.setColor(selectedColor[0]);
        });

        // ── Actions ─────────────────────────────────────────────────────
        Button apply = new Button(ctx);
        apply.setText("Apply");
        apply.setAllCaps(false);
        apply.setOnClickListener(v -> {
            int a = Color.alpha(selectedColor[0]);
            String hex = a >= 255
                    ? String.format("#%06X", (0xFFFFFF & selectedColor[0]))
                    : String.format("#%08X", selectedColor[0]);
            String mode = selectedDominant[0] ? HighlightRingDrawable.MODE_DOMINANT : HighlightRingDrawable.MODE_SOLID;
            if (listener != null) listener.onPicked(hex, mode);
            sheet.dismiss();
        });
        root.addView(apply);

        if (allowReset) {
            TextView reset = new TextView(ctx);
            reset.setText("Use default ring");
            reset.setTextSize(13);
            reset.setGravity(Gravity.CENTER);
            reset.setTextColor(Color.parseColor("#6200EE"));
            reset.setPadding(0, dp(ctx, 14), 0, 0);
            reset.setOnClickListener(v -> {
                if (listener != null) listener.onPicked(null, null);
                sheet.dismiss();
            });
            root.addView(reset);
        }

        sheet.setContentView(root);
        sheet.show();
    }

    // ── Swatch adapter ──────────────────────────────────────────────────
    private interface OnSwatchPick { void onPick(int color, int position); }

    private static class SwatchAdapter extends RecyclerView.Adapter<SwatchAdapter.VH> {
        private final int[] colors;
        private int selectedPos;
        private final OnSwatchPick pick;

        SwatchAdapter(int[] colors, int selectedPos, OnSwatchPick pick) {
            this.colors = colors;
            this.selectedPos = selectedPos;
            this.pick = pick;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            FrameLayout cell = new FrameLayout(c);
            int size = dp(c, 40);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
            lp.setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
            cell.setLayoutParams(lp);
            View swatch = new View(c);
            FrameLayout.LayoutParams swLp = new FrameLayout.LayoutParams(size, size);
            swatch.setLayoutParams(swLp);
            cell.addView(swatch);
            View check = new View(c);
            check.setVisibility(View.GONE);
            return new VH(cell, swatch, check);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(colors[pos]);
            gd.setStroke(pos == selectedPos ? dp(h.itemView.getContext(), 3) : dp(h.itemView.getContext(), 1),
                    pos == selectedPos ? Color.BLACK : 0x22000000);
            h.swatch.setBackground(gd);
            h.itemView.setOnClickListener(v -> {
                int prev = selectedPos;
                selectedPos = pos;
                notifyItemChanged(prev);
                notifyItemChanged(pos);
                if (pick != null) pick.onPick(colors[pos], pos);
            });
        }

        @Override public int getItemCount() { return colors.length; }

        /** Sets the highlighted swatch, or clears it entirely when {@code pos}
         *  is -1 (used when the user picks an exact color from the rainbow
         *  box instead of a preset). */
        void setSelectedColor(int pos) {
            int prev = selectedPos;
            selectedPos = pos;
            if (prev >= 0) notifyItemChanged(prev);
            if (pos >= 0) notifyItemChanged(pos);
        }

        static class VH extends RecyclerView.ViewHolder {
            final View swatch;
            VH(View item, View swatch, View check) { super(item); this.swatch = swatch; }
        }
    }

    private static int indexOf(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == value) return i;
        return 0;
    }

    private static int safeColor(String hex) {
        try { return Color.parseColor(hex); } catch (Exception e) { return PALETTE[0]; }
    }

    private static Button modeButton(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        return b;
    }

    private static void styleModeButton(Button b, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(b.getContext(), 8));
        bg.setColor(selected ? Color.parseColor("#6200EE") : Color.parseColor("#F0F0F0"));
        b.setBackground(bg);
        b.setTextColor(selected ? Color.WHITE : Color.parseColor("#333333"));
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
