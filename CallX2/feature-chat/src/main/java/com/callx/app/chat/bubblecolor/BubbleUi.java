package com.callx.app.chat.bubblecolor;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;

import com.callx.app.utils.ChatThemeManager;

/** Tiny shared UI toolkit for the two bubble-colour screens: premium iOS-style palette + helpers. */
final class BubbleUi {
    static final int ACCENT = 0xFF6C2BD9;

    final float d;
    final boolean dark;
    final int bg, card, text, sub, stroke;

    BubbleUi(Context c) {
        d = c.getResources().getDisplayMetrics().density;
        dark = ChatThemeManager.isDarkMode(c);
        bg     = dark ? 0xFF0F0F14 : 0xFFFFFFFF;
        card   = dark ? 0xFF1D1B2A : 0xFFF3F0FF;
        text   = dark ? 0xFFF2F2F7 : 0xFF17172B;
        sub    = dark ? 0xFFA09DB5 : 0xFF6B6880;
        stroke = dark ? 0xFF2C2A3B : 0xFFE7E2F7;
    }

    int dp(float v) { return Math.round(v * d); }

    GradientDrawable round(int fill, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp * d);
        if (strokeColor != 0) g.setStroke(Math.max(1, Math.round(d)), strokeColor);
        return g;
    }

    TextView label(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-medium", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    TextView pillButton(Context c, String s, boolean filled) {
        TextView t = label(c, s, 16f, filled ? 0xFFFFFFFF : sub, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(filled ? ACCENT : card, 28f, filled ? 0 : stroke));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }
}
