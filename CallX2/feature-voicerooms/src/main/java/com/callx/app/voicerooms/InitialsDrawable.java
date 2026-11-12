package com.callx.app.voicerooms;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
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
    // (start, end) gradient pairs — har naam ko hash se ek pair milta hai
    private static final int[][] PALETTE = {
        {0xFFA78BFA, 0xFF6D3BF6}, {0xFFF472B6, 0xFFDB2777}, {0xFFFBBF24, 0xFFEA580C},
        {0xFF34D399, 0xFF059669}, {0xFF60A5FA, 0xFF2563EB}, {0xFFF87171, 0xFFDC2626},
        {0xFF2DD4BF, 0xFF0D9488}, {0xFFC084FC, 0xFF9333EA}
    };

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String letter;
    private final int[] pair;

    public InitialsDrawable(String name) {
        String n = name == null ? "" : name.trim();
        letter = n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase(Locale.ROOT);
        pair = PALETTE[Math.abs(n.hashCode()) % PALETTE.length];
        bg.setColor(pair[1]);
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

    @Override protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        bg.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                pair[0], pair[1], Shader.TileMode.CLAMP));
    }

    @Override public int getIntrinsicWidth() { return SIZE; }
    @Override public int getIntrinsicHeight() { return SIZE; }

    @Override public void setAlpha(int alpha) { bg.setAlpha(alpha); tx.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { bg.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
