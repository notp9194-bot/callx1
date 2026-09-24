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
 * API 31+ GPU backdrop: records the live content behind ONE glass host (only its strip) into a
 * RenderNode and applies blur + saturation as a RenderEffect. No bitmap
 * copies, no CPU blur. Kept in its own class so API 23-30 devices never
 * touch RenderNode/RenderEffect.
 *
 * LOW-RES PIPELINE (PERF): the backdrop is rendered at a fraction of its real
 * size (1/4 => 16x fewer pixels at the top quality tier, 1/8 at the medium tier),
 * then upscaled when drawn. A blurred image has
 * no fine detail, so the result looks the same but the GPU rasterises, blurs and
 * stores 16x less data.
 *
 *   layer  (RenderNode, hardware compositing layer, size = w*scale x h*scale)
 *     └─ content (RenderNode, blur + saturation RenderEffect, source drawn scaled down)
 *
 * The effect sits on {@code content} INSIDE the low-res layer, so it runs at low
 * resolution. (If it sat on the outer node, the up-scale matrix would make it run
 * at full resolution again.)
 */
@RequiresApi(31)
final class GlassRenderNodeBackdrop {

    private final RenderNode content = new RenderNode("callx.glass.content");
    private final RenderNode layer = new RenderNode("callx.glass.layer");

    private final float saturation;
    private float currentBlurPx = -1f;
    private float scale = 0.25f;   // fraction of real size the backdrop is rendered at
    private float sx = 0.25f, sy = 0.25f;

    GlassRenderNodeBackdrop(float saturation) {
        this.saturation = saturation;
        layer.setUseCompositingLayer(true, null);   // render into a w*scale x h*scale texture
    }

    /**
     * Sets the blur radius (real pixels) and the render scale. Cheap: just a new RenderEffect on the
     * node. No-op if unchanged. The caller re-records afterwards (size changes with the scale).
     */
    void setQuality(float blurPx, float scale) {
        if (blurPx == currentBlurPx && scale == this.scale) return;
        currentBlurPx = blurPx;
        this.scale = scale;
        final float r = Math.max(0.5f, blurPx * scale);   // radius in low-res pixels
        RenderEffect blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(saturation);
        content.setRenderEffect(
                RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm), blur));
    }

    /**
     * PERF: crops the w x h region (offX, offY) out of an already-recorded, shared low-res
     * capture of the WHOLE source ({@code base}, see {@link GlassBackdrop.Shared#ensureBaseCapture})
     * instead of re-walking the source's view tree. {@code base} is recorded once per source per
     * dirty cycle and reused by every glass host (header + input bar); this just composites a
     * GPU texture region, so N hosts no longer cost N full tree draws.
     * {@code baseSx}/{@code baseSy} are the scale factors {@code base} itself was recorded at.
     */
    boolean recordFromBase(RenderNode base, float baseSx, float baseSy,
                            int w, int h, float offX, float offY, int baseColor) {
        final int lw = Math.max(1, Math.round(w * scale));
        final int lh = Math.max(1, Math.round(h * scale));
        sx = lw / (float) w;   // exact factors so the up-scale in draw() lines up pixel-for-pixel
        sy = lh / (float) h;

        boolean ok;
        content.setPosition(0, 0, lw, lh);
        Canvas c = content.beginRecording(lw, lh);
        try {
            c.drawColor(baseColor);
            c.save();
            c.scale(sx / baseSx, sy / baseSy);
            c.translate(-offX * baseSx, -offY * baseSy);
            c.drawRenderNode(base);
            c.restore();
            ok = true;
        } catch (RuntimeException e) {
            ok = false;
        } finally {
            content.endRecording();
        }

        // Re-record the (tiny) layer wrapper too: guarantees the layer texture is re-rendered.
        layer.setPosition(0, 0, lw, lh);
        Canvas lc = layer.beginRecording(lw, lh);
        try {
            lc.drawRenderNode(content);
        } finally {
            layer.endRecording();
        }
        return ok;
    }

    /**
     * Fallback: re-records straight from {@code source} (full tree walk). Only used if a shared
     * base capture couldn't be produced this frame (e.g. mid-transition RuntimeException).
     */
    boolean recordDirect(View source, int w, int h, float offX, float offY, int baseColor) {
        final int lw = Math.max(1, Math.round(w * scale));
        final int lh = Math.max(1, Math.round(h * scale));
        sx = lw / (float) w;
        sy = lh / (float) h;

        boolean ok;
        content.setPosition(0, 0, lw, lh);
        Canvas c = content.beginRecording(lw, lh);
        try {
            c.scale(sx, sy);
            c.drawColor(baseColor);
            c.translate(-offX, -offY);
            source.draw(c);
            ok = true;
        } catch (RuntimeException e) {
            ok = false;
        } finally {
            content.endRecording();
        }

        layer.setPosition(0, 0, lw, lh);
        Canvas lc = layer.beginRecording(lw, lh);
        try {
            lc.drawRenderNode(content);
        } finally {
            layer.endRecording();
        }
        return ok;
    }

    /**
     * Draws the blurred strip up-scaled back to full size (bilinear), with its top-left at
     * (dx, dy) in the caller's coordinates.
     */
    void draw(Canvas canvas, float dx, float dy) {
        final int save = canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(1f / sx, 1f / sy);
        canvas.drawRenderNode(layer);
        canvas.restoreToCount(save);
    }
}
