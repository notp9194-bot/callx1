package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.widget.EditText;

/**
 * TypingStyleManager — Chat input box ke liye 26 alag typing styles.
 *
 * Usage:
 *   TypingStyleManager.get(ctx).applyToInput(binding.etMessage);
 *   TypingStyleManager.get(ctx).setStyle(TypingStyleManager.STYLE_BOLD);
 */
public class TypingStyleManager {

    // ── Original Style IDs (0–9) ──────────────────────────────────────────
    public static final int STYLE_NORMAL      = 0;
    public static final int STYLE_BOLD        = 1;
    public static final int STYLE_ITALIC      = 2;
    public static final int STYLE_BOLD_ITALIC = 3;
    public static final int STYLE_MONOSPACE   = 4;
    public static final int STYLE_SERIF       = 5;
    public static final int STYLE_SERIF_BOLD  = 6;
    public static final int STYLE_CONDENSED   = 7;
    public static final int STYLE_LIGHT       = 8;
    public static final int STYLE_HANDWRITING = 9;

    // ── New Popular Style IDs (10–19) ─────────────────────────────────────
    public static final int STYLE_MEDIUM         = 10;
    public static final int STYLE_THIN           = 11;
    public static final int STYLE_SERIF_ITALIC   = 12;
    public static final int STYLE_CONDENSED_BOLD = 13;
    public static final int STYLE_BLACK          = 14;
    public static final int STYLE_CURSIVE        = 15;
    public static final int STYLE_SANS_MEDIUM    = 16;
    public static final int STYLE_MONO_BOLD      = 17;
    public static final int STYLE_LIGHT_ITALIC   = 18;
    public static final int STYLE_CLASSIC_BOLD   = 19;

    // ── Samsung Style IDs (20–21) ────────────────────────────────────────
    public static final int STYLE_SAMSUNG        = 20;
    public static final int STYLE_SAMSUNG_SCRIPT = 21;

    // ── 4 New Style IDs (22–25) ──────────────────────────────────────────
    public static final int STYLE_SERIF_CONDENSED      = 22;  // Serif Condensed — compact classic
    public static final int STYLE_MONO_ITALIC          = 23;  // Monospace Italic — code slant
    public static final int STYLE_CONDENSED_LIGHT      = 24;  // Condensed Light — minimal narrow
    public static final int STYLE_SANS_BOLD_CONDENSED  = 25;  // Sans-Serif Bold Condensed — strong narrow

    public static final String[] STYLE_NAMES = {
        // Original 10
        "\u270F\uFE0F Normal (Default)",             // 0
        "\uD835\uDC01 Bold",                          // 1
        "\uD835\uDC18 Italic",                        // 2
        "\uD835\uDCD1 Bold Italic",                   // 3
        "\u2328\uFE0F Monospace",                     // 4
        "\uD835\uDC12 Serif",                         // 5
        "\uD835\uDC01 Serif Bold",                    // 6
        "\u258C Condensed",                           // 7
        "\u2097 Light",                               // 8
        "\u270C\uFE0F Casual",                        // 9
        // New 10
        "\u2726 Medium",                              // 10
        "\u1D00 Thin",                                // 11
        "\uD835\uDC34 Serif Italic",                  // 12
        "\u2759 Condensed Bold",                      // 13
        "\u2B1B Black / Heavy",                       // 14
        "\uD835\uDC9E Cursive",                       // 15
        "\u25C6 Sans Medium",                         // 16
        "\u2328 Mono Bold",                           // 17
        "~ Light Italic",                             // 18
        "\ua7B5 Classic Bold Italic",                 // 19
        // Samsung
        "\uD83C\uDDF8 Samsung One",                   // 20
        "\uD835\uDC9E\uD835\uDCB6\uD835\uDCBB\uD835\uDCBC\uD835\uDCCA\uD835\uDCB7\uD835\uDCB8 Script \u2728", // 21
        // 4 New
        "\uD83D\uDCDA Serif Condensed",               // 22
        "\uD83D\uDCBB Mono Italic",                   // 23
        "\u2018 Condensed Light",                     // 24
        "\u25AE Sans Bold Condensed"                  // 25
    };

    // ── SharedPreferences ─────────────────────────────────────────────────
    private static final String PREF_NAME  = "typing_style_prefs";
    private static final String KEY_STYLE  = "input_typing_style";

    // ── Singleton ─────────────────────────────────────────────────────────
    private static TypingStyleManager instance;
    private final SharedPreferences prefs;
    private int currentStyle;

    private TypingStyleManager(Context ctx) {
        prefs        = ctx.getApplicationContext()
                          .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        currentStyle = prefs.getInt(KEY_STYLE, STYLE_NORMAL);
    }

    public static TypingStyleManager get(Context ctx) {
        if (instance == null) instance = new TypingStyleManager(ctx);
        return instance;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public int getCurrentStyle() { return currentStyle; }

    public void setStyle(int styleId) {
        currentStyle = styleId;
        prefs.edit().putInt(KEY_STYLE, styleId).apply();
    }

    /**
     * Apply current typing style to the given EditText (message input box).
     */
    public void applyToInput(EditText editText) {
        if (editText == null) return;

        switch (currentStyle) {
            case STYLE_BOLD:
                editText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                break;
            case STYLE_ITALIC:
                editText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC));
                break;
            case STYLE_BOLD_ITALIC:
                editText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC));
                break;
            case STYLE_MONOSPACE:
                editText.setTypeface(Typeface.MONOSPACE);
                break;
            case STYLE_SERIF:
                editText.setTypeface(Typeface.SERIF);
                break;
            case STYLE_SERIF_BOLD:
                editText.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                break;
            case STYLE_CONDENSED:
                editText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                break;
            case STYLE_LIGHT:
                editText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                break;
            case STYLE_HANDWRITING:
                editText.setTypeface(Typeface.create("casual", Typeface.NORMAL));
                break;
            case STYLE_MEDIUM:
                editText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                break;
            case STYLE_THIN:
                editText.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
                break;
            case STYLE_SERIF_ITALIC:
                editText.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
                break;
            case STYLE_CONDENSED_BOLD:
                editText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                break;
            case STYLE_BLACK:
                editText.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                break;
            case STYLE_CURSIVE:
                editText.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
                break;
            case STYLE_SANS_MEDIUM:
                editText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                break;
            case STYLE_MONO_BOLD:
                editText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                break;
            case STYLE_LIGHT_ITALIC:
                editText.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
                break;
            case STYLE_CLASSIC_BOLD:
                editText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC));
                break;
            case STYLE_SAMSUNG:
                try {
                    Typeface samsungTf = Typeface.create("SamsungOne", Typeface.NORMAL);
                    if (samsungTf != null && !samsungTf.equals(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL))) {
                        editText.setTypeface(samsungTf);
                    } else {
                        Typeface alt = Typeface.create("samsung-sans", Typeface.NORMAL);
                        if (alt != null && !alt.equals(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL))) {
                            editText.setTypeface(alt);
                        } else {
                            editText.setTypeface(Typeface.SERIF);
                        }
                    }
                } catch (Exception e) {
                    editText.setTypeface(Typeface.SERIF);
                }
                break;
            case STYLE_SAMSUNG_SCRIPT:
                editText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                break;

            // ── 4 New Styles ──────────────────────────────────────────────

            case STYLE_SERIF_CONDENSED:
                // Serif Condensed — compact serif with Italic for classic narrow feel
                editText.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
                break;

            case STYLE_MONO_ITALIC:
                // Monospace Italic — code style with slant
                editText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC));
                break;

            case STYLE_CONDENSED_LIGHT:
                // Condensed Light — very narrow and hairline feel
                editText.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
                break;

            case STYLE_SANS_BOLD_CONDENSED:
                // Sans-Serif Bold Condensed — strong and narrow, great for impact
                editText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
                break;

            case STYLE_NORMAL:
            default:
                editText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                break;
        }
    }
}
