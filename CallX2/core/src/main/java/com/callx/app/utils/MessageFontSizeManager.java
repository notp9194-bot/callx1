package com.callx.app.utils;

import android.content.Context;

/** Stub — font size system removed. Always uses 15sp. */
public class MessageFontSizeManager {
    public static final int SIZE_MEDIUM = 1;

    private static MessageFontSizeManager instance;
    private MessageFontSizeManager(Context ctx) {}

    public static MessageFontSizeManager get(Context ctx) {
        if (instance == null) instance = new MessageFontSizeManager(ctx);
        return instance;
    }

    public int getCurrentSize() { return SIZE_MEDIUM; }
    public float getFontSizeSp() { return 15f; }
    public void setSize(int idx) {}
    public static float spForIndex(int idx) { return 15f; }
}
