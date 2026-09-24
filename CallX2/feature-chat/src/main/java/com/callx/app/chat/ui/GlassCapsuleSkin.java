package com.callx.app.chat.ui;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Paints ONE large glass plate (the input-bar pill): live backdrop blur from
 * {@link GlassBackdrop}, tint, top gloss and a bright rim. Shaders are cached
 * per size/theme so drawing allocates nothing.
 */
final class GlassCapsuleSkin {

    private final float rimWidth;
    private final float ringWidth;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF plate = new RectF();
    private final RectF tmp = new RectF();
    private final Path path = new Path();

    private Shader fillShader, rimShader;
    private float shaderW = -1f, shaderH = -1f;
    private boolean shaderDark;

    GlassCapsuleSkin(float density) {
        rimWidth = 1.1f * density;
        ringWidth = 0.8f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimWidth);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ringWidth);
    }

    void draw(Canvas canvas, GlassBackdrop backdrop, int w, int h, float radius) {
        if (w <= 0 || h <= 0) return;
        final boolean dark = backdrop.isDark();
        plate.set(0, 0, w, h);
        plate.inset(rimWidth, rimWidth);   // leave room for the outer ring / rim
        final float r = Math.min(radius, Math.min(plate.width(), plate.height()) / 2f);

        if (!dark) {
            ring.setColor(0x1A0F172A);
            tmp.set(plate);
            tmp.inset(-0.4f, -0.4f);
            canvas.drawRoundRect(tmp, r + 0.4f, r + 0.4f, ring);
        }

        int save = canvas.save();
        path.rewind();
        path.addRoundRect(plate, r, r, Path.Direction.CW);
        canvas.clipPath(path);
        if (!backdrop.draw(canvas)) {
            fallback.setColor((backdrop.baseColor() & 0x00FFFFFF) | 0xB0000000);
            canvas.drawPaint(fallback);
        }
        canvas.restoreToCount(save);

        ensureShaders(plate.width(), plate.height(), dark);
        // shaders are built in local (0,0)-based coords; plate starts at rimWidth
        canvas.save();
        canvas.translate(plate.left, plate.top);
        tmp.set(0, 0, plate.width(), plate.height());
        fill.setShader(fillShader);
        canvas.drawRoundRect(tmp, r, r, fill);   // tint + gloss in one call
        rim.setShader(rimShader);
        tmp.inset(rimWidth / 2f, rimWidth / 2f);
        canvas.drawRoundRect(tmp, r - rimWidth / 2f, r - rimWidth / 2f, rim);
        canvas.restore();
    }

    private void ensureShaders(float w, float h, boolean dark) {
        if (fillShader != null && w == shaderW && h == shaderH && dark == shaderDark) return;
        shaderW = w; shaderH = h; shaderDark = dark;
        final int t = dark ? 0x2EFFFFFF : 0x99FFFFFF;
        fillShader = new LinearGradient(0, 0, 0, h,
                new int[]{
                        GlassImageButton.over(dark ? 0x30FFFFFF : 0x80FFFFFF, t),
                        GlassImageButton.over(0x08FFFFFF, t),
                        GlassImageButton.over(0x00FFFFFF, t),
                        GlassImageButton.over(0x12000000, t) },
                new float[]{ 0f, 0.35f, 0.6f, 1f }, Shader.TileMode.CLAMP);
        rimShader = new LinearGradient(0, 0, w, h,
                new int[]{ 0xF2FFFFFF, 0x30FFFFFF, 0x30FFFFFF, 0x8CFFFFFF },
                new float[]{ 0f, 0.45f, 0.55f, 1f }, Shader.TileMode.CLAMP);
    }
}
