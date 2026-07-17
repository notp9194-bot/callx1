package com.callx.app.editor;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;

/**
 * ReelTextOverlayActivity — Full-screen, production-quality text overlay editor.
 *
 * TikTok / Instagram-style:
 *  ✅ Full-screen dark overlay (camera shows behind via windowIsTranslucent)
 *  ✅ Keyboard pops immediately; text typed in center
 *  ✅ Live preview with real font / color / background / alignment applied
 *  ✅ 16-color swatch palette
 *  ✅ 5 font styles: Default, Bold, Italic, Script, Mono
 *  ✅ 3 background styles: None, Semi, Solid
 *  ✅ Left / Center / Right alignment
 *  ✅ Font size SeekBar (12–72sp)
 *  ✅ Done returns all data; Cancel just finishes
 *  ✅ Supports pre-fill for tap-to-edit flow from ReelCameraActivity
 */
public class ReelTextOverlayActivity extends AppCompatActivity {

    // ── Intent extras (input) ─────────────────────────────────────────────
    public static final String EXTRA_INITIAL_TEXT      = "initial_text";
    public static final String EXTRA_INITIAL_COLOR     = "initial_color";
    public static final String EXTRA_INITIAL_FONT      = "initial_font_index";
    public static final String EXTRA_INITIAL_SIZE      = "initial_size_sp";
    public static final String EXTRA_INITIAL_BG        = "initial_bg_style";
    public static final String EXTRA_INITIAL_ALIGN     = "initial_align";

    // ── Result extras (output) ────────────────────────────────────────────
    public static final String RESULT_TEXT             = "result_text";
    public static final String RESULT_COLOR            = "result_color";
    public static final String RESULT_FONT_INDEX       = "result_font_index";
    public static final String RESULT_SIZE_SP          = "result_size_sp";
    public static final String RESULT_BG_STYLE         = "result_bg_style";
    public static final String RESULT_ALIGNMENT        = "result_alignment";

    // ── Palette / option arrays ───────────────────────────────────────────
    private static final int[] PALETTE = {
        0xFFFFFFFF, 0xFF000000, 0xFFFF3B5C, 0xFFFF9500,
        0xFFFFCC00, 0xFF34C759, 0xFF00C7BE, 0xFF007AFF,
        0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55, 0xFFFF6B35,
        0xFFFFD60A, 0xFF30D158, 0xFF40CBE0, 0xFFBF5AF2
    };
    private static final String[] FONT_LABELS = { "Aa", "Bold", "Italic", "Script", "Mono" };
    private static final String[] BG_LABELS   = { "None", "Blur", "Solid" };
    private static final String[] ALIGN_ICONS = { "⬅", "↔", "➡" };

    // ── State ─────────────────────────────────────────────────────────────
    private int  selColor = 0xFFFFFFFF;
    private int  selFont  = 0;
    private int  selBg    = 0;
    private int  selAlign = 1;   // default: center
    private int  textSp   = 32;

    // ── Views ─────────────────────────────────────────────────────────────
    private EditText    etText;
    private FrameLayout previewContainer;
    private TextView    tvPreview;
    private SeekBar     seekSize;
    private TextView    tvSizeLabel;
    private LinearLayout layoutColors;

    // Active-selection highlight views
    private final View[] fontHighlights  = new View[FONT_LABELS.length];
    private final View[] bgHighlights    = new View[BG_LABELS.length];
    private final View[] alignHighlights = new View[ALIGN_ICONS.length];

    // ─────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep soft keyboard always visible so user types immediately
        getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // Full-screen, no title bar
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        setContentView(R.layout.activity_reel_text_overlay);
        bindViews();
        readExtras();
        updatePreview();

        // Open keyboard immediately
        etText.postDelayed(() -> {
            etText.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etText, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    // ─────────────────────────────────────────────────────────────────────
    private void bindViews() {
        // Top bar
        ImageButton btnBack = findViewById(R.id.btn_overlay_back);
        Button      btnDone = findViewById(R.id.btn_overlay_done);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnDone != null) btnDone.setOnClickListener(v -> returnResult());

        // Preview area
        previewContainer = findViewById(R.id.overlay_preview_container);
        tvPreview        = findViewById(R.id.tv_overlay_preview);

        // Text input
        etText = findViewById(R.id.et_overlay_text);
        etText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { updatePreview(); }
        });

        // Size slider
        seekSize    = findViewById(R.id.seek_overlay_size);
        tvSizeLabel = findViewById(R.id.tv_overlay_size_label);
        if (seekSize != null) {
            seekSize.setMax(60);         // 12–72sp
            seekSize.setProgress(20);   // default 32sp
            seekSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    textSp = 12 + p;
                    if (tvSizeLabel != null) tvSizeLabel.setText(textSp + "sp");
                    updatePreview();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        // Color palette
        layoutColors = findViewById(R.id.layout_overlay_colors);
        buildColorPalette();

        // Font chips
        LinearLayout layoutFonts = findViewById(R.id.layout_overlay_fonts);
        buildChips(layoutFonts, FONT_LABELS, fontHighlights, idx -> {
            selFont = idx;
            highlightSelection(fontHighlights, idx, 0xFF5B5BF6);
            updatePreview();
        });

        // Background style chips
        LinearLayout layoutBg = findViewById(R.id.layout_overlay_bg);
        buildChips(layoutBg, BG_LABELS, bgHighlights, idx -> {
            selBg = idx;
            highlightSelection(bgHighlights, idx, 0xFFAA44CC);
            updatePreview();
        });

        // Alignment chips
        LinearLayout layoutAlign = findViewById(R.id.layout_overlay_align);
        buildChips(layoutAlign, ALIGN_ICONS, alignHighlights, idx -> {
            selAlign = idx;
            highlightSelection(alignHighlights, idx, 0xFF007AFF);
            updatePreview();
        });

        // Set default selections
        highlightSelection(fontHighlights,  selFont,  0xFF5B5BF6);
        highlightSelection(bgHighlights,    selBg,    0xFFAA44CC);
        highlightSelection(alignHighlights, selAlign, 0xFF007AFF);
    }

    // ─────────────────────────────────────────────────────────────────────
    private void readExtras() {
        Intent i = getIntent();
        if (i == null) return;

        String initialText  = i.getStringExtra(EXTRA_INITIAL_TEXT);
        String initialColor = i.getStringExtra(EXTRA_INITIAL_COLOR);
        int    initialFont  = i.getIntExtra(EXTRA_INITIAL_FONT,  0);
        int    initialSize  = i.getIntExtra(EXTRA_INITIAL_SIZE,  32);
        int    initialBg    = i.getIntExtra(EXTRA_INITIAL_BG,    0);
        int    initialAlign = i.getIntExtra(EXTRA_INITIAL_ALIGN, 1);

        if (initialText != null && !initialText.isEmpty()) {
            etText.setText(initialText);
            etText.setSelection(initialText.length());
        }
        if (initialColor != null && !initialColor.isEmpty()) {
            try { selColor = Color.parseColor(initialColor); } catch (Exception ignored) {}
        }
        selFont  = Math.max(0, Math.min(FONT_LABELS.length - 1, initialFont));
        textSp   = Math.max(12, Math.min(72, initialSize));
        selBg    = Math.max(0, Math.min(BG_LABELS.length - 1, initialBg));
        selAlign = Math.max(0, Math.min(2, initialAlign));

        // Apply loaded size to seekBar
        if (seekSize != null)    seekSize.setProgress(textSp - 12);
        if (tvSizeLabel != null) tvSizeLabel.setText(textSp + "sp");

        // Refresh highlight states after loading values
        highlightSelection(fontHighlights,  selFont,  0xFF5B5BF6);
        highlightSelection(bgHighlights,    selBg,    0xFFAA44CC);
        highlightSelection(alignHighlights, selAlign, 0xFF007AFF);

        // Refresh color palette ring
        refreshColorRing();
    }

    // ─────────────────────────────────────────────────────────────────────
    /** Apply all current state to the live preview TextView. */
    private void updatePreview() {
        if (tvPreview == null) return;

        String raw = etText != null && etText.getText() != null
                     ? etText.getText().toString() : "";
        tvPreview.setText(raw.isEmpty() ? "Enter your text…" : raw);
        tvPreview.setTextSize(textSp);
        tvPreview.setTextColor(selColor);

        // Alignment
        int grav = (selAlign == 1) ? Gravity.CENTER
                 : (selAlign == 2) ? Gravity.END | Gravity.CENTER_VERTICAL
                 : Gravity.START | Gravity.CENTER_VERTICAL;
        tvPreview.setGravity(grav);

        // Background
        switch (selBg) {
            case 1: tvPreview.setBackgroundColor(0x99000000); break;
            case 2: tvPreview.setBackgroundColor(0xFF000000); break;
            default: tvPreview.setBackgroundColor(Color.TRANSPARENT); break;
        }

        // Typeface
        Typeface tf;
        int style = Typeface.NORMAL;
        switch (selFont) {
            case 1: tf = Typeface.DEFAULT_BOLD; break;
            case 2: tf = Typeface.DEFAULT; style = Typeface.ITALIC; break;
            case 3: tf = Typeface.SERIF; break;
            case 4: tf = Typeface.MONOSPACE; break;
            default: tf = Typeface.DEFAULT; break;
        }
        tvPreview.setTypeface(tf, style);
    }

    // ─────────────────────────────────────────────────────────────────────
    /** Return text + all styling to the caller. */
    private void returnResult() {
        String text = etText != null && etText.getText() != null
                      ? etText.getText().toString().trim() : "";
        if (text.isEmpty()) {
            Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent result = new Intent();
        result.putExtra(RESULT_TEXT,       text);
        result.putExtra(RESULT_COLOR,      String.format("#%06X", 0xFFFFFF & selColor));
        result.putExtra(RESULT_FONT_INDEX, selFont);
        result.putExtra(RESULT_SIZE_SP,    textSp);
        result.putExtra(RESULT_BG_STYLE,   selBg);
        result.putExtra(RESULT_ALIGNMENT,  selAlign);
        setResult(RESULT_OK, result);
        finish();
    }

    // ── Palette builder ───────────────────────────────────────────────────
    private void buildColorPalette() {
        if (layoutColors == null) return;
        layoutColors.removeAllViews();
        for (int color : PALETTE) {
            View swatch = new View(this);
            int  sz     = dp(34);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            swatch.setLayoutParams(lp);
            swatch.setBackgroundColor(color);
            // Round swatch
            swatch.setBackground(buildCircleDrawable(color));
            final int c = color;
            swatch.setOnClickListener(v -> {
                selColor = c;
                refreshColorRing();
                updatePreview();
            });
            layoutColors.addView(swatch);
        }
        refreshColorRing();
    }

    /** Draw a ring around the currently selected colour swatch. */
    private void refreshColorRing() {
        if (layoutColors == null) return;
        for (int i = 0; i < layoutColors.getChildCount() && i < PALETTE.length; i++) {
            View sw = layoutColors.getChildAt(i);
            if (PALETTE[i] == selColor) {
                sw.setBackground(buildRingedDrawable(PALETTE[i]));
            } else {
                sw.setBackground(buildCircleDrawable(PALETTE[i]));
            }
        }
    }

    private android.graphics.drawable.GradientDrawable buildCircleDrawable(int color) {
        android.graphics.drawable.GradientDrawable gd =
            new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        return gd;
    }

    private android.graphics.drawable.LayerDrawable buildRingedDrawable(int color) {
        android.graphics.drawable.GradientDrawable outer =
            new android.graphics.drawable.GradientDrawable();
        outer.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        outer.setStroke(dp(3), 0xFFFFFFFF);
        outer.setColor(color);
        return new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{ outer });
    }

    // ── Generic chip-row builder ──────────────────────────────────────────
    interface OnChipSelected { void onSelected(int index); }

    private void buildChips(LinearLayout container, String[] labels,
                            View[] highlights, OnChipSelected cb) {
        if (container == null) return;
        container.removeAllViews();
        highlights[0] = null; // reset
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(13);
            chip.setTextColor(0xFFFFFFFF);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            chip.setBackgroundColor(0xFF2A2A2A);

            LinearLayout.LayoutParams lp;
            if (container.getId() == R.id.layout_overlay_align) {
                lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            } else {
                lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            lp.setMargins(dp(3), 0, dp(3), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> cb.onSelected(idx));
            highlights[i] = chip;
            container.addView(chip);
        }
    }

    private void highlightSelection(View[] views, int selectedIdx, int highlightColor) {
        for (int i = 0; i < views.length; i++) {
            if (views[i] == null) continue;
            views[i].setBackgroundColor(i == selectedIdx ? highlightColor : 0xFF2A2A2A);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
