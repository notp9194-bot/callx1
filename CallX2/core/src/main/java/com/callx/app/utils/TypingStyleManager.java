package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.widget.EditText;

/**
 * TypingStyleManager — Chat input box ke liye 21 alag typing styles.
 *
 * Usage:
 *   TypingStyleManager.get(ctx).applyToInput(binding.etMessage);
 *   TypingStyleManager.get(ctx).setStyle(TypingStyleManager.STYLE_BOLD);
 */
public class TypingStyleManager {

    // ── Original Style IDs (0–9) ──────────────────────────────────────────
    public static final int STYLE_NORMAL      = 0;   // Normal (Default)
    public static final int STYLE_BOLD        = 1;   // Bold
    public static final int STYLE_ITALIC      = 2;   // Italic
    public static final int STYLE_BOLD_ITALIC = 3;   // Bold Italic
    public static final int STYLE_MONOSPACE   = 4;   // Monospace (code feel)
    public static final int STYLE_SERIF       = 5;   // Serif (classic)
    public static final int STYLE_SERIF_BOLD  = 6;   // Serif Bold
    public static final int STYLE_CONDENSED   = 7;   // Sans-Serif Condensed
    public static final int STYLE_LIGHT       = 8;   // Sans-Serif Light
    public static final int STYLE_HANDWRITING = 9;   // Casual/Handwriting feel

    // ── New Popular Style IDs (10–19) ─────────────────────────────────────
    public static final int STYLE_MEDIUM         = 10;  // Medium weight
    public static final int STYLE_THIN           = 11;  // Thin / Hairline
    public static final int STYLE_SERIF_ITALIC   = 12;  // Serif Italic
    public static final int STYLE_CONDENSED_BOLD = 13;  // Condensed Bold
    public static final int STYLE_BLACK          = 14;  // Black/Extra Bold
    public static final int STYLE_CURSIVE        = 15;  // Cursive
    public static final int STYLE_SANS_MEDIUM    = 16;  // Sans-Serif Medium
    public static final int STYLE_MONO_BOLD      = 17;  // Monospace Bold
    public static final int STYLE_LIGHT_ITALIC   = 18;  // Light Italic
    public static final int STYLE_CLASSIC_BOLD   = 19;  // Classic Sans Bold Italic

    // ── Samsung Style ID (20) ─────────────────────────────────────────────
    public static final int STYLE_SAMSUNG        = 20;  // Samsung One font (native on Samsung, Serif fallback on others)

    public static final String[] STYLE_NAMES = {
        // Original 10
        "✏️ Normal (Default)",      // 0
        "𝗕 Bold",                   // 1
        "𝘐 Italic",                 // 2
        "𝙱 Bold Italic",            // 3
        "🅢 Samsung One",           // 4  ← position 4 (1-based 5th, but user bola "4 number pe")
        "⌨️ Monospace",             // 5
        "𝐒 Serif",                  // 6
        "𝐁 Serif Bold",             // 7
        "▌Condensed",               // 8
        "ₗ Light",                  // 9
        // New 10
        "✒️ Casual",                // 10
        "✦ Medium",                 // 11
        "᳀ Thin",                   // 12
        "𝓘 Serif Italic",           // 13
        "❚ Condensed Bold",         // 14
        "⬛ Black / Heavy",         // 15
        "𝒞 Cursive",               // 16
        "◆ Sans Medium",            // 17
        "⌨ Mono Bold",             // 18
        "~ Light Italic",           // 19
        "Ꞵ Classic Bold Italic"     // 20
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
                // Samsung One — Samsung devices pe natively available.
                // Non-Samsung pe "samsung-sans" try karo, warna Serif fallback.
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
            case STYLE_NORMAL:
            default:
                editText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                break;
        }
    }
}
