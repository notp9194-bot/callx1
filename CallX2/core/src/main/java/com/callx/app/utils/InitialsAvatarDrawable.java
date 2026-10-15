package com.callx.app.utils;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Circle with the user's initial on a colour derived from their uid — used instead of a grey person icon when there is no photo. */
public final class InitialsAvatarDrawable extends Drawable {
    private static final int[] PALETTE = {
        0xFF5C6BC0, 0xFF26A69A, 0xFFEF6C00, 0xFF8E24AA, 0xFF3949AB, 0xFF00897B, 0xFFD81B60, 0xFF6D4C41
    };
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String initial;

    public InitialsAvatarDrawable(@Nullable String name, @Nullable String uid) {
        String n = name == null ? "" : name.trim();
        initial = n.isEmpty() ? "?" : new String(Character.toChars(n.codePointAt(0))).toUpperCase();
        String key = (uid != null && !uid.isEmpty()) ? uid : n;
        bg.setColor(PALETTE[(key.hashCode() & 0x7fffffff) % PALETTE.length]);
        fg.setColor(0xFFFFFFFF);
        fg.setTextAlign(Paint.Align.CENTER);
        fg.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    @Override public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        float r = Math.min(b.width(), b.height()) / 2f;
        c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, bg);
        fg.setTextSize(r * 0.95f);
        float y = b.exactCenterY() - (fg.descent() + fg.ascent()) / 2f;
        c.drawText(initial, b.exactCenterX(), y, fg);
    }
    @Override public void setAlpha(int a) { bg.setAlpha(a); fg.setAlpha(a); }
    @Override public void setColorFilter(@Nullable ColorFilter f) { bg.setColorFilter(f); fg.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
