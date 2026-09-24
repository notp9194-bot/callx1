package com.callx.app.chat.ui;

import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.RequiresApi;

/**
 * API 31+ GPU backdrop: records the live content behind the header into a
 * RenderNode and applies blur + saturation as a RenderEffect. No bitmap
 * copies, no CPU blur. Kept in its own class so API 23-30 devices never
 * touch RenderNode/RenderEffect.
 */
@RequiresApi(31)
final class GlassRenderNodeBackdrop {

    private final RenderNode node = new RenderNode("callx.glass.backdrop");

    GlassRenderNodeBackdrop(float blurPx, float saturation) {
        RenderEffect blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(saturation);
        node.setRenderEffect(
                RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm), blur));
    }

    /** Re-records {@code source} shifted so that the header's top-left maps to (0,0). */
    boolean record(View source, int w, int h, float offX, float offY, int baseColor) {
        node.setPosition(0, 0, w, h);
        Canvas c = node.beginRecording(w, h);
        try {
            c.drawColor(baseColor);
            c.translate(-offX, -offY);
            source.draw(c);
            return true;
        } catch (RuntimeException e) {
            return false;
        } finally {
            node.endRecording();
        }
    }

    void draw(Canvas canvas) {
        canvas.drawRenderNode(node);
    }
}
