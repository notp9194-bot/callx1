package com.callx.app.voicerooms;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * InitialsDrawable — photo na ho to naam ka pehla letter circle mein.
 *
 * NOTE: CircleImageView non-bitmap drawable ko intrinsic size se bitmap
 * banata hai; intrinsic size -1 hui to crash/blank aata hai. Isliye 256px
 * ka fixed intrinsic size return karte hain.
 */
public class InitialsDrawable extends Drawable {

    private static final int SIZE = 256;
    private static final int[] PALETTE = {
        0xFF8B5CF6, 0xFFEC4899, 0xFFF59E0B, 0xFF10B981,
        0xFF3B82F6, 0xFFEF4444, 0xFF14B8A6, 0xFFA855F7
    };

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String letter;

    public InitialsDrawable(String name) {
        String n = name == null ? "" : name.trim();
        letter = n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase(Locale.ROOT);
        bg.setColor(PALETTE[Math.abs(n.hashCode()) % PALETTE.length]);
        tx.setColor(Color.WHITE);
        tx.setTextAlign(Paint.Align.CENTER);
        tx.setFakeBoldText(true);
    }

    @Override public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        float r = Math.min(b.width(), b.height()) / 2f;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        canvas.drawCircle(cx, cy, r, bg);
        tx.setTextSize(r * 0.95f);
        float y = cy - (tx.descent() + tx.ascent()) / 2f;
        canvas.drawText(letter, cx, y, tx);
    }

    @Override public int getIntrinsicWidth() { return SIZE; }
    @Override public int getIntrinsicHeight() { return SIZE; }

    @Override public void setAlpha(int alpha) { bg.setAlpha(alpha); tx.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { bg.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
