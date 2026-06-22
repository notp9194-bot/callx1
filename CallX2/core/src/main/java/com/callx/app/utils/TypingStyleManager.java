package com.callx.app.utils;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.EditText;

/**
 * TypingStyleManager — Single Normal style. No switching.
 */
public class TypingStyleManager {

    public static final int STYLE_NORMAL          = 0;
    public static final int STYLE_BOLD            = 1;
    public static final int STYLE_ITALIC          = 2;
    public static final int STYLE_BOLD_ITALIC     = 3;
    public static final int STYLE_MONOSPACE       = 4;
    public static final int STYLE_SERIF           = 5;
    public static final int STYLE_SERIF_BOLD      = 6;
    public static final int STYLE_CONDENSED       = 7;
    public static final int STYLE_LIGHT           = 8;
    public static final int STYLE_HANDWRITING     = 9;
    public static final int STYLE_MEDIUM          = 10;
    public static final int STYLE_THIN            = 11;
    public static final int STYLE_SERIF_ITALIC    = 12;
    public static final int STYLE_CONDENSED_BOLD  = 13;
    public static final int STYLE_BLACK           = 14;
    public static final int STYLE_CURSIVE         = 15;
    public static final int STYLE_SANS_MEDIUM     = 16;
    public static final int STYLE_MONO_BOLD       = 17;
    public static final int STYLE_LIGHT_ITALIC    = 18;
    public static final int STYLE_CLASSIC_BOLD    = 19;
    public static final int STYLE_SAMSUNG         = 20;
    public static final int STYLE_SAMSUNG_SCRIPT  = 21;
    public static final int STYLE_SERIF_CONDENSED     = 22;
    public static final int STYLE_MONO_ITALIC         = 23;
    public static final int STYLE_CONDENSED_LIGHT     = 24;
    public static final int STYLE_SANS_BOLD_CONDENSED = 25;

    public static final String[] STYLE_NAMES = {
        "\u270F\uFE0F Normal"
    };

    private static TypingStyleManager instance;

    private TypingStyleManager(Context ctx) {
    }

    public static TypingStyleManager get(Context ctx) {
        if (instance == null) instance = new TypingStyleManager(ctx);
        return instance;
    }

    public int getCurrentStyle() { return STYLE_NORMAL; }

    public void setStyle(int styleId) {
    }

    public void applyToInput(EditText editText) {
        if (editText == null) return;
        editText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
    }
}
