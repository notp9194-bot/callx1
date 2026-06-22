package com.callx.app.utils;

import android.content.Context;

/**
 * MessageFontSizeManager — fixed at 15sp.
 * Public constants kept for compilation compatibility.
 */
public class MessageFontSizeManager {

    public static final int SIZE_SMALL   = 0;
    public static final int SIZE_MEDIUM  = 1;
    public static final int SIZE_LARGE   = 2;
    public static final int SIZE_XLARGE  = 3;

    public static final String[] SIZE_NAMES = {"Small", "Medium", "Large", "Extra Large"};
    public static final String[] SIZE_DESC  = {
        "Compact — more messages visible",
        "Default — balanced readability",
        "Comfortable — easy on the eyes",
        "Bold & big — maximum readability"
    };

    private static MessageFontSizeManager instance;

    private MessageFontSizeManager(Context ctx) {}

    public static MessageFontSizeManager get(Context ctx) {
        if (instance == null) instance = new MessageFontSizeManager(ctx);
        return instance;
    }

    public int getCurrentSize() { return SIZE_MEDIUM; }

    public float getFontSizeSp() { return 15f; }

    public void setSize(int sizeIndex) { /* fixed — no-op */ }

    public static float spForIndex(int idx) { return 15f; }
}
