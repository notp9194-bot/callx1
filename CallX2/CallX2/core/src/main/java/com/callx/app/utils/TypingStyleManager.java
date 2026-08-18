package com.callx.app.utils;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.EditText;

/** Stub — typing style system removed. Always uses default sans-serif. */
public class TypingStyleManager {
    public static final int STYLE_NORMAL        = 0;
    public static final int STYLE_SAMSUNG_SCRIPT = 21;

    private static TypingStyleManager instance;
    private TypingStyleManager(Context ctx) {}

    public static TypingStyleManager get(Context ctx) {
        if (instance == null) instance = new TypingStyleManager(ctx);
        return instance;
    }

    public int getCurrentStyle() { return STYLE_NORMAL; }
    public void setStyle(int id) {}

    public void applyToInput(EditText editText) {
        if (editText != null)
            editText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
    }
}
