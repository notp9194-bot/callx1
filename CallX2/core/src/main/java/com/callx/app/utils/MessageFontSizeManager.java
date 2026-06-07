package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * MessageFontSizeManager
 *
 * Chat message text ka font size globally store karta hai.
 * 4 levels: SMALL=12sp, MEDIUM=15sp (default), LARGE=18sp, XLARGE=22sp
 *
 * Usage:
 *   float sp = MessageFontSizeManager.get(ctx).getFontSizeSp();
 *   MessageFontSizeManager.get(ctx).setSize(MessageFontSizeManager.SIZE_LARGE);
 */
public class MessageFontSizeManager {

    public static final int SIZE_SMALL   = 0;  // 12sp
    public static final int SIZE_MEDIUM  = 1;  // 15sp  (default)
    public static final int SIZE_LARGE   = 2;  // 18sp
    public static final int SIZE_XLARGE  = 3;  // 22sp

    public static final String[] SIZE_NAMES = {"Small", "Medium", "Large", "Extra Large"};
    public static final String[] SIZE_DESC  = {
        "Compact — more messages visible",
        "Default — balanced readability",
        "Comfortable — easy on the eyes",
        "Bold & big — maximum readability"
    };

    private static final float[] SIZE_SP = {12f, 15f, 18f, 22f};
    private static final String PREF_NAME = "msg_font_size_prefs";
    private static final String KEY_SIZE  = "font_size_index";

    private static MessageFontSizeManager instance;
    private final SharedPreferences prefs;

    private MessageFontSizeManager(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static MessageFontSizeManager get(Context ctx) {
        if (instance == null) instance = new MessageFontSizeManager(ctx);
        return instance;
    }

    public int getCurrentSize() {
        return prefs.getInt(KEY_SIZE, SIZE_MEDIUM);
    }

    public float getFontSizeSp() {
        int idx = getCurrentSize();
        if (idx < 0 || idx >= SIZE_SP.length) idx = SIZE_MEDIUM;
        return SIZE_SP[idx];
    }

    public void setSize(int sizeIndex) {
        if (sizeIndex < 0 || sizeIndex >= SIZE_SP.length) return;
        prefs.edit().putInt(KEY_SIZE, sizeIndex).apply();
    }

    /** Convenience: given a size index, return the sp value */
    public static float spForIndex(int idx) {
        if (idx < 0 || idx >= SIZE_SP.length) return SIZE_SP[SIZE_MEDIUM];
        return SIZE_SP[idx];
    }
}
