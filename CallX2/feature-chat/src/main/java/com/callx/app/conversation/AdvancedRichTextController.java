package com.callx.app.conversation;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.text.Layout;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * v169 — AdvancedRichTextController
 *
 * Extends v168's basic formatting with a full creative-suite toolbar:
 *
 *   Row 1 (v168):   B · I · U · S · Code · Clear
 *   Row 2 (v169):   Color · Highlight · Size · Align · Font · Spacing · LineH
 *
 * ── All advanced tools ───────────────────────────────────────────────────────
 *
 *  TEXT COLOR       — 20-color palette + custom color picker (bottom sheet)
 *                     Applies ForegroundColorSpan to selection / pending.
 *
 *  HIGHLIGHT COLOR  — 12-color translucent palette.
 *                     Applies BackgroundColorSpan (semi-transparent) to selection.
 *
 *  TEXT SIZE        — Preset chips: XS(10) · S(12) · M(14) · L(18) · XL(24) · XXL(32) · XXXL(48)
 *                     Plus a SeekBar for arbitrary sp values 8–72.
 *                     Applies AbsoluteSizeSpan(sp, true) to selection.
 *
 *  ALIGNMENT        — Left · Center · Right · Justify (paragraph-level).
 *                     Applies AlignmentSpan.Standard to the paragraph.
 *
 *  FONT FAMILY      — 8 bundled families: Default · Serif · Monospace ·
 *                     Sans-Serif-Light · Sans-Serif-Medium · Cursive ·
 *                     Sans-Serif-Condensed · Sans-Serif-Thin.
 *                     Applies TypefaceSpan to selection.
 *
 *  LETTER SPACING   — SeekBar 0.0 – 0.5 em (in 0.02 steps).
 *                     Applies LetterSpacingSpan (API 21+) to selection.
 *
 *  LINE HEIGHT      — SeekBar 1.0 – 3.0× (in 0.1 steps).
 *                     Applies LineHeightSpan.Standard (API 29+); falls back to
 *                     a custom LineHeightSpan on older devices.
 *
 * ── Integration ──────────────────────────────────────────────────────────────
 *
 *   AdvancedRichTextController advCtrl =
 *       new AdvancedRichTextController(context, etMessageInput, fragmentManager);
 *
 *   // Bind the secondary (advanced) toolbar:
 *   advCtrl.bindAdvancedToolbar(
 *       btnTextColor, btnHighlight, btnTextSize,
 *       btnAlign, btnFont, btnLetterSpacing, btnLineHeight
 *   );
 *
 *   // Call this whenever the selection changes (override onSelectionChanged in EditText):
 *   advCtrl.onSelectionChanged(selStart, selEnd);
 */
public class AdvancedRichTextController {

    // ── Preset text sizes (sp) ────────────────────────────────────────────────
    public static final int[] TEXT_SIZE_PRESETS = { 10, 12, 14, 16, 18, 22, 28, 36, 48 };
    public static final String[] TEXT_SIZE_LABELS = { "XS", "S", "M", "M+", "L", "XL", "XXL", "3XL", "4XL" };

    // ── Preset text colors ────────────────────────────────────────────────────
    @ColorInt
    public static final int[] TEXT_COLORS = {
        Color.BLACK,        Color.WHITE,        0xFF212121,  // near-black
        0xFF1976D2,         // blue
        0xFF388E3C,         // green
        0xFFD32F2F,         // red
        0xFFF57C00,         // orange
        0xFF7B1FA2,         // purple
        0xFF00838F,         // teal
        0xFFAD1457,         // pink
        0xFF5D4037,         // brown
        0xFF546E7A,         // blue-grey
        0xFFFDD835,         // yellow
        0xFF00E5FF,         // cyan
        0xFF69F0AE,         // light green
        0xFFFF6D00,         // deep orange
        0xFF6200EA,         // deep purple
        0xFFE040FB,         // pink-purple
        0xFF64FFDA,         // mint
        0xFFFFFF00,         // bright yellow
    };

    // ── Preset highlight colors (semi-transparent) ────────────────────────────
    @ColorInt
    public static final int[] HIGHLIGHT_COLORS = {
        0x66FFFF00,  // yellow highlight
        0x6600FF00,  // green highlight
        0x660000FF,  // blue highlight
        0x66FF0000,  // red highlight
        0x66FF6600,  // orange highlight
        0x66FF00FF,  // pink highlight
        0x6600FFFF,  // cyan highlight
        0x66FFFFFF,  // white highlight
        0x66000000,  // black highlight
        0x6666FF00,  // lime highlight
        0x66FF66FF,  // lavender highlight
        0x6600,      // transparent (clear highlight)
    };

    // ── Font families ─────────────────────────────────────────────────────────
    public static final String[] FONT_FAMILIES = {
        "sans-serif",
        "serif",
        "monospace",
        "sans-serif-light",
        "sans-serif-medium",
        "sans-serif-condensed",
        "sans-serif-thin",
        "cursive",
    };
    public static final String[] FONT_LABELS = {
        "Default",
        "Serif",
        "Mono",
        "Light",
        "Medium",
        "Cond.",
        "Thin",
        "Italic",
    };

    // ── Alignment options ─────────────────────────────────────────────────────
    public enum TextAlign { LEFT, CENTER, RIGHT, JUSTIFY }
    private TextAlign currentAlign = TextAlign.LEFT;

    // ── State ─────────────────────────────────────────────────────────────────
    @ColorInt private int pendingTextColor  = Color.BLACK;
    @ColorInt private int pendingHighlight  = Color.TRANSPARENT;
    private int   pendingTextSizeSp         = 14;  // default message text size
    private String pendingFontFamily        = "sans-serif";
    private float  pendingLetterSpacing     = 0f;
    private float  pendingLineHeightMult    = 1.15f;

    // ── Views / context ───────────────────────────────────────────────────────
    private final Context context;
    private final EditText etInput;
    @Nullable private Object fragmentManager; // FragmentManager — kept as Object to avoid import collision

    // Advanced toolbar buttons
    private @Nullable View btnTextColor;
    private @Nullable View btnHighlight;
    private @Nullable View btnTextSize;
    private @Nullable View btnAlign;
    private @Nullable View btnFont;
    private @Nullable View btnLetterSpacing;
    private @Nullable View btnLineHeight;

    // ── Constructor ───────────────────────────────────────────────────────────
    public AdvancedRichTextController(@NonNull Context context,
                                      @NonNull EditText etInput,
                                      @Nullable Object fragmentManager) {
        this.context = context;
        this.etInput = etInput;
        this.fragmentManager = fragmentManager;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void bindAdvancedToolbar(@NonNull View btnTextColor,
                                    @NonNull View btnHighlight,
                                    @NonNull View btnTextSize,
                                    @NonNull View btnAlign,
                                    @NonNull View btnFont,
                                    @NonNull View btnLetterSpacing,
                                    @NonNull View btnLineHeight) {
        this.btnTextColor     = btnTextColor;
        this.btnHighlight     = btnHighlight;
        this.btnTextSize      = btnTextSize;
        this.btnAlign         = btnAlign;
        this.btnFont          = btnFont;
        this.btnLetterSpacing = btnLetterSpacing;
        this.btnLineHeight    = btnLineHeight;

        btnTextColor    .setOnClickListener(v -> showTextColorPicker(false));
        btnHighlight    .setOnClickListener(v -> showTextColorPicker(true));
        btnTextSize     .setOnClickListener(v -> showTextSizePanel());
        btnAlign        .setOnClickListener(v -> cycleAlignment());
        btnFont         .setOnClickListener(v -> showFontFamilyPanel());
        btnLetterSpacing.setOnClickListener(v -> showLetterSpacingPanel());
        btnLineHeight   .setOnClickListener(v -> showLineHeightPanel());

        // Long-press tooltips
        bindTooltip(btnTextColor,     "Text color");
        bindTooltip(btnHighlight,     "Highlight color");
        bindTooltip(btnTextSize,      "Text size");
        bindTooltip(btnAlign,         "Alignment");
        bindTooltip(btnFont,          "Font family");
        bindTooltip(btnLetterSpacing, "Letter spacing");
        bindTooltip(btnLineHeight,    "Line height");
    }

    /** Called by the host EditText.onSelectionChanged — refreshes button indicators. */
    public void onSelectionChanged(int selStart, int selEnd) {
        refreshIndicators(selStart, selEnd);
    }

    // ── Text Color ────────────────────────────────────────────────────────────

    private void showTextColorPicker(boolean isHighlight) {
        // Build an inline color-grid popup anchored to the toolbar button.
        View anchor = isHighlight ? btnHighlight : btnTextColor;
        if (anchor == null) return;

        // Inflate color-picker bottom sheet directly as a popup window.
        ColorPickerPopup popup = new ColorPickerPopup(
            context,
            isHighlight ? HIGHLIGHT_COLORS : TEXT_COLORS,
            isHighlight ? "Highlight Color" : "Text Color",
            colorInt -> {
                if (isHighlight) {
                    applyHighlightColor(colorInt);
                } else {
                    applyTextColor(colorInt);
                }
            }
        );
        popup.showAsDropDown(anchor, 0, 8);
    }

    public void applyTextColor(@ColorInt int color) {
        pendingTextColor = color;
        applySpanToSelectionOrPending(new ForegroundColorSpan(color), ForegroundColorSpan.class);
        updateColorIndicator(btnTextColor, color);
    }

    public void applyHighlightColor(@ColorInt int color) {
        pendingHighlight = color;
        if (color == Color.TRANSPARENT) {
            removeSpansInRange(BackgroundColorSpan.class);
        } else {
            applySpanToSelectionOrPending(new BackgroundColorSpan(color), BackgroundColorSpan.class);
        }
        updateColorIndicator(btnHighlight, color);
    }

    // ── Text Size ─────────────────────────────────────────────────────────────

    private void showTextSizePanel() {
        TextSizePopup popup = new TextSizePopup(context,
            pendingTextSizeSp,
            newSizeSp -> applyTextSize(newSizeSp)
        );
        if (btnTextSize != null) popup.showAsDropDown(btnTextSize, 0, 8);
    }

    public void applyTextSize(int sizeSp) {
        pendingTextSizeSp = sizeSp;
        applySpanToSelectionOrPending(
            new AbsoluteSizeSpan(sizeSp, true),
            AbsoluteSizeSpan.class
        );
        if (btnTextSize instanceof TextView) {
            ((TextView) btnTextSize).setText(sizeSp + "sp");
        }
    }

    // ── Alignment ─────────────────────────────────────────────────────────────

    private void cycleAlignment() {
        TextAlign[] vals = TextAlign.values();
        currentAlign = vals[(currentAlign.ordinal() + 1) % vals.length];
        applyAlignment(currentAlign);
        updateAlignButton();
    }

    public void applyAlignment(TextAlign align) {
        currentAlign = align;
        Editable text = etInput.getText();
        if (text == null) return;
        int s = safeSelStart(), e = safeSelEnd();
        // Alignment is paragraph-level — expand to paragraph boundaries.
        int paraStart = paragraphStart(text, s);
        int paraEnd   = paragraphEnd(text, e);
        // Remove old alignment spans in range.
        AlignmentSpan[] old = text.getSpans(paraStart, paraEnd, AlignmentSpan.class);
        for (AlignmentSpan sp : old) text.removeSpan(sp);

        Layout.Alignment layoutAlign;
        switch (align) {
            case CENTER:  layoutAlign = Layout.Alignment.ALIGN_CENTER;   break;
            case RIGHT:   layoutAlign = Layout.Alignment.ALIGN_OPPOSITE; break;
            case JUSTIFY: layoutAlign = Layout.Alignment.ALIGN_NORMAL;   break; // JUSTIFY = NORMAL on most devices
            default:      layoutAlign = Layout.Alignment.ALIGN_NORMAL;   break;
        }
        text.setSpan(new AlignmentSpan.Standard(layoutAlign),
                     paraStart, paraEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        updateAlignButton();
    }

    // ── Font Family ───────────────────────────────────────────────────────────

    private void showFontFamilyPanel() {
        FontFamilyPopup popup = new FontFamilyPopup(context,
            pendingFontFamily,
            family -> applyFontFamily(family)
        );
        if (btnFont != null) popup.showAsDropDown(btnFont, 0, 8);
    }

    public void applyFontFamily(String fontFamily) {
        pendingFontFamily = fontFamily;
        applySpanToSelectionOrPending(new TypefaceSpan(fontFamily), TypefaceSpan.class);
        if (btnFont instanceof TextView) {
            // Show short label on button.
            for (int i = 0; i < FONT_FAMILIES.length; i++) {
                if (FONT_FAMILIES[i].equals(fontFamily)) {
                    ((TextView) btnFont).setText(FONT_LABELS[i]);
                    break;
                }
            }
        }
    }

    // ── Letter Spacing ────────────────────────────────────────────────────────

    private void showLetterSpacingPanel() {
        SliderPopup popup = new SliderPopup(
            context,
            "Letter Spacing",
            0f, 0.5f, pendingLetterSpacing,
            val -> applyLetterSpacing(val)
        );
        if (btnLetterSpacing != null) popup.showAsDropDown(btnLetterSpacing, -80, 8);
    }

    public void applyLetterSpacing(float em) {
        pendingLetterSpacing = em;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            applySpanToSelectionOrPending(new LetterSpacingSpan(em), LetterSpacingSpan.class);
        }
    }

    // ── Line Height ───────────────────────────────────────────────────────────

    private void showLineHeightPanel() {
        SliderPopup popup = new SliderPopup(
            context,
            "Line Height",
            1.0f, 3.0f, pendingLineHeightMult,
            val -> applyLineHeight(val)
        );
        if (btnLineHeight != null) popup.showAsDropDown(btnLineHeight, -80, 8);
    }

    public void applyLineHeight(float multiplier) {
        pendingLineHeightMult = multiplier;
        Editable text = etInput.getText();
        if (text == null) return;
        int s = safeSelStart(), e = safeSelEnd();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // API 29+ — LineHeightSpan.Standard
            int lineHeightPx = (int) (etInput.getTextSize() * multiplier);
            LineHeightSpan.Standard[] old = text.getSpans(s, e, LineHeightSpan.Standard.class);
            for (LineHeightSpan.Standard sp : old) text.removeSpan(sp);
            text.setSpan(new LineHeightSpan.Standard(lineHeightPx), s, e,
                         Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            // Fallback: custom LineHeightSpan
            CustomLineHeightSpan[] old = text.getSpans(s, e, CustomLineHeightSpan.class);
            for (CustomLineHeightSpan sp : old) text.removeSpan(sp);
            text.setSpan(new CustomLineHeightSpan(multiplier), s, e,
                         Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── Generic span helpers ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> void applySpanToSelectionOrPending(T span, Class<T> cls) {
        Editable text = etInput.getText();
        if (text == null) return;
        int s = safeSelStart(), e = safeSelEnd();
        boolean hasSelection = e > s;

        // Remove existing spans of the same type in range first.
        T[] old = text.getSpans(hasSelection ? s : 0, hasSelection ? e : text.length(), cls);
        if (hasSelection) {
            for (T sp : old) text.removeSpan(sp);
            text.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            // Pending — apply at cursor position with INCLUSIVE flags.
            int cursor = s >= 0 ? s : text.length();
            text.setSpan(span, cursor, cursor, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void removeSpansInRange(Class<T> cls) {
        Editable text = etInput.getText();
        if (text == null) return;
        int s = safeSelStart(), e = safeSelEnd();
        boolean hasSelection = e > s;
        int from = hasSelection ? s : 0;
        int to   = hasSelection ? e : text.length();
        T[] spans = text.getSpans(from, to, cls);
        for (T sp : spans) text.removeSpan(sp);
    }

    // ── Paragraph helpers ─────────────────────────────────────────────────────

    private static int paragraphStart(CharSequence text, int pos) {
        for (int i = pos - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') return i + 1;
        }
        return 0;
    }

    private static int paragraphEnd(CharSequence text, int pos) {
        for (int i = pos; i < text.length(); i++) {
            if (text.charAt(i) == '\n') return i;
        }
        return text.length();
    }

    // ── Selection helpers ─────────────────────────────────────────────────────

    private int safeSelStart() {
        int s = etInput.getSelectionStart();
        return s >= 0 ? s : (etInput.getText() != null ? etInput.getText().length() : 0);
    }

    private int safeSelEnd() {
        int e = etInput.getSelectionEnd();
        return e >= 0 ? e : safeSelStart();
    }

    // ── Button indicator updates ──────────────────────────────────────────────

    private void updateColorIndicator(@Nullable View btn, @ColorInt int color) {
        if (btn == null) return;
        // The button has a small color-strip view child with id @id/vColorIndicator.
        View strip = btn.findViewWithTag("colorIndicator");
        if (strip != null) {
            strip.setBackgroundColor(color);
        }
    }

    private void updateAlignButton() {
        if (btnAlign == null) return;
        String label;
        switch (currentAlign) {
            case CENTER:  label = "≡C"; break;
            case RIGHT:   label = "≡R"; break;
            case JUSTIFY: label = "≡J"; break;
            default:      label = "≡L"; break;
        }
        if (btnAlign instanceof TextView) ((TextView) btnAlign).setText(label);
    }

    private void refreshIndicators(int selStart, int selEnd) {
        // Re-read spans at cursor/selection and update button states.
        Editable text = etInput.getText();
        if (text == null) return;
        int s = Math.max(0, selStart);
        int e = Math.min(text.length(), Math.max(selEnd, s));

        ForegroundColorSpan[] fg = text.getSpans(s, e, ForegroundColorSpan.class);
        if (fg.length > 0) updateColorIndicator(btnTextColor, fg[fg.length - 1].getForegroundColor());

        BackgroundColorSpan[] bg = text.getSpans(s, e, BackgroundColorSpan.class);
        if (bg.length > 0) updateColorIndicator(btnHighlight, bg[bg.length - 1].getBackgroundColor());
    }

    // ── Tooltip helper ────────────────────────────────────────────────────────

    private void bindTooltip(View v, String text) {
        if (v == null) return;
        v.setOnLongClickListener(view -> {
            Toast.makeText(view.getContext(), text, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    // ── Custom LetterSpacingSpan (android.text.style.LetterSpacingSpan is not public API) ──
    /**
     * Applies per-character letter spacing via TextPaint.setLetterSpacing().
     * Functionally equivalent to android.text.style.LetterSpacingSpan (API 21+) which is
     * not part of the public Android SDK — we replicate it here instead.
     *
     * @param em spacing in em units, e.g. 0.05f = 5% of font size between characters.
     */
    public static class LetterSpacingSpan extends android.text.style.MetricAffectingSpan
            implements android.text.ParcelableSpan {

        private final float mLetterSpacing;

        public LetterSpacingSpan(float letterSpacing) {
            mLetterSpacing = letterSpacing;
        }

        public float getLetterSpacing() { return mLetterSpacing; }

        @Override
        public void updateMeasureState(@androidx.annotation.NonNull android.text.TextPaint tp) {
            tp.setLetterSpacing(mLetterSpacing);
        }

        @Override
        public void updateDrawState(android.text.TextPaint tp) {
            tp.setLetterSpacing(mLetterSpacing);
        }

        @Override public int getSpanTypeId() { return 0; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeFloat(mLetterSpacing);
        }
    }

        // ── Custom LineHeightSpan (pre-API-29 fallback) ────────────────────────────

    public static class CustomLineHeightSpan implements LineHeightSpan {
        private final float multiplier;
        public CustomLineHeightSpan(float multiplier) { this.multiplier = multiplier; }

        @Override
        public void chooseHeight(CharSequence text, int start, int end,
                                 int spanstartv, int lineHeight,
                                 android.graphics.Paint.FontMetricsInt fm) {
            int originalHeight = fm.descent - fm.ascent;
            int newHeight      = (int) (originalHeight * multiplier);
            int delta          = newHeight - originalHeight;
            fm.descent  += delta / 2;
            fm.ascent   -= delta - delta / 2;
            fm.bottom    = fm.descent;
            fm.top       = fm.ascent;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Inner Popup helpers
    //  (lightweight popup windows — no BottomSheet dependency)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Color palette popup — 4-column grid of circular color swatches.
     */
    public static class ColorPickerPopup extends android.widget.PopupWindow {
        public interface OnColorSelected { void onSelected(@ColorInt int color); }

        public ColorPickerPopup(@NonNull Context ctx, @ColorInt int[] colors,
                                String title, OnColorSelected cb) {
            super(ctx);
            setFocusable(true);
            setOutsideTouchable(true);

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
            root.setBackgroundColor(Color.WHITE);
            // rounded shadow card
            root.setElevation(dp(ctx, 8));

            // Title
            TextView tv = new TextView(ctx);
            tv.setText(title);
            tv.setTextSize(12f);
            tv.setTextColor(0xFF666666);
            tv.setPadding(0, 0, 0, dp(ctx, 8));
            root.addView(tv);

            // Grid
            int cols = 5;
            int swatchSize = dp(ctx, 36);
            int rows = (int) Math.ceil(colors.length / (float) cols);
            for (int r = 0; r < rows; r++) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < cols; c++) {
                    int idx = r * cols + c;
                    if (idx >= colors.length) break;
                    final int colorVal = colors[idx];
                    View swatch = new View(ctx);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(swatchSize, swatchSize);
                    lp.setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
                    swatch.setLayoutParams(lp);
                    swatch.setBackgroundColor(colorVal);
                    // rounded circle via elevation clip
                    swatch.setClipToOutline(true);
                    swatch.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                    swatch.setOnClickListener(v -> { cb.onSelected(colorVal); dismiss(); });
                    row.addView(swatch);
                }
                root.addView(row);
            }

            setContentView(root);
            setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
            setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        private static int dp(Context ctx, int dp) {
            return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
        }
    }

    /**
     * Text size popup — preset chips + SeekBar.
     */
    public static class TextSizePopup extends android.widget.PopupWindow {
        public interface OnSizeSelected { void onSelected(int sizeSp); }

        public TextSizePopup(@NonNull Context ctx, int currentSp, OnSizeSelected cb) {
            super(ctx);
            setFocusable(true);
            setOutsideTouchable(true);

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
            root.setBackgroundColor(Color.WHITE);
            root.setElevation(dp(ctx, 8));

            // Preset chips row
            HorizontalScrollView hsv = new HorizontalScrollView(ctx);
            LinearLayout chips = new LinearLayout(ctx);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < TEXT_SIZE_PRESETS.length; i++) {
                final int sp = TEXT_SIZE_PRESETS[i];
                TextView chip = new TextView(ctx);
                chip.setText(TEXT_SIZE_LABELS[i]);
                chip.setTextSize(12f);
                chip.setTextColor(sp == currentSp ? Color.WHITE : 0xFF444444);
                chip.setBackgroundColor(sp == currentSp ? 0xFF1976D2 : 0xFFEEEEEE);
                int p = dp(ctx, 8);
                chip.setPadding(p, p / 2, p, p / 2);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0);
                chip.setLayoutParams(lp);
                chip.setOnClickListener(v -> { cb.onSelected(sp); dismiss(); });
                chips.addView(chip);
            }
            hsv.addView(chips);
            root.addView(hsv);

            // SeekBar — arbitrary size 8–72sp
            TextView seekLabel = new TextView(ctx);
            seekLabel.setText("Custom: " + currentSp + "sp");
            seekLabel.setTextSize(12f);
            seekLabel.setTextColor(0xFF666666);
            seekLabel.setPadding(0, dp(ctx, 10), 0, 0);
            root.addView(seekLabel);

            SeekBar seek = new SeekBar(ctx);
            seek.setMax(72 - 8);
            seek.setProgress(currentSp - 8);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    int sp2 = progress + 8;
                    seekLabel.setText("Custom: " + sp2 + "sp");
                    if (fromUser) cb.onSelected(sp2);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) { dismiss(); }
            });
            root.addView(seek);

            setContentView(root);
            setWidth(dp(ctx, 280));
            setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        private static int dp(Context ctx, int dp) {
            return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
        }
    }

    /**
     * Font family popup — vertical list of font options.
     */
    public static class FontFamilyPopup extends android.widget.PopupWindow {
        public interface OnFontSelected { void onSelected(String fontFamily); }

        public FontFamilyPopup(@NonNull Context ctx, String current, OnFontSelected cb) {
            super(ctx);
            setFocusable(true);
            setOutsideTouchable(true);

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
            root.setBackgroundColor(Color.WHITE);
            root.setElevation(dp(ctx, 8));

            for (int i = 0; i < FONT_FAMILIES.length; i++) {
                final String family = FONT_FAMILIES[i];
                TextView item = new TextView(ctx);
                item.setText(FONT_LABELS[i]);
                item.setTypeface(Typeface.create(family, Typeface.NORMAL));
                item.setTextSize(14f);
                item.setTextColor(family.equals(current) ? 0xFF1976D2 : 0xFF222222);
                int p = dp(ctx, 10);
                item.setPadding(p, p, p, p);
                item.setBackgroundColor(
                    family.equals(current) ? 0xFFE3F2FD : Color.TRANSPARENT);
                item.setOnClickListener(v -> { cb.onSelected(family); dismiss(); });
                root.addView(item);
            }

            setContentView(root);
            setWidth(dp(ctx, 180));
            setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        private static int dp(Context ctx, int dp) {
            return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
        }
    }

    /**
     * Generic slider popup (letter spacing / line height).
     */
    public static class SliderPopup extends android.widget.PopupWindow {
        public interface OnValueChanged { void onChange(float value); }

        public SliderPopup(@NonNull Context ctx, String title,
                           float min, float max, float current,
                           OnValueChanged cb) {
            super(ctx);
            setFocusable(true);
            setOutsideTouchable(true);

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
            root.setBackgroundColor(Color.WHITE);
            root.setElevation(dp(ctx, 8));

            TextView label = new TextView(ctx);
            label.setText(title + ": " + String.format("%.2f", current));
            label.setTextSize(12f);
            label.setTextColor(0xFF444444);
            root.addView(label);

            SeekBar seek = new SeekBar(ctx);
            int steps = 100;
            seek.setMax(steps);
            seek.setProgress((int) ((current - min) / (max - min) * steps));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    float val = min + (max - min) * (progress / (float) steps);
                    label.setText(title + ": " + String.format("%.2f", val));
                    if (fromUser) cb.onChange(val);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            root.addView(seek);

            setContentView(root);
            setWidth(dp(ctx, 260));
            setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        private static int dp(Context ctx, int dp) {
            return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
        }
    }
}
