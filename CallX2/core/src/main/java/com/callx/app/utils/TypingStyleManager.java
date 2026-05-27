package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.widget.EditText;

/**
 * TypingStyleManager — Chat input box ke liye 10 alag typing styles.
 *
 * Usage:
 *   TypingStyleManager.get(ctx).applyToInput(binding.etMessage);
 *   TypingStyleManager.get(ctx).setStyle(TypingStyleManager.STYLE_BOLD);
 */
public class TypingStyleManager {

    // ── Style IDs ─────────────────────────────────────────────────────────
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

    public static final String[] STYLE_NAMES = {
        "✏️ Normal (Default)",
        "𝗕 Bold",
        "𝘐 Italic",
        "𝙱 Bold Italic",
        "⌨️ Monospace",
        "𝐒 Serif",
        "𝐁 Serif Bold",
        "▌Condensed",
        "ₗ Light",
        "✒️ Casual"
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
            case STYLE_NORMAL:
            default:
                editText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                break;
        }
    }
}
