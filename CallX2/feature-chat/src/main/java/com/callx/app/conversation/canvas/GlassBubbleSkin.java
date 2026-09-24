package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Frosted-glass LOOK for a message bubble — fill tint + top gloss + bright rim — reusing the
 * same shader recipe as the chat header/input-bar glass (GlassCapsuleSkin), but with NO live
 * backdrop blur and no {@code GlassBackdrop}.
 *
 * Why static instead of live blur, like the header/input glass uses:
 *  - The bubble lives INSIDE the RecyclerView that IS the blur source for the header/input
 *    glass (fl_chat_backdrop) — a bubble can't record a live blur of the list it is itself a
 *    row of.
 *  - There can be a dozen+ bubbles on screen scrolling continuously (not idle like the header),
 *    so a per-bubble live backdrop would mean that many RenderNode re-records every scroll
 *    frame — real cost, not the one-traversal-per-dirty-cycle the header/input glass now shares
 *    (see GlassBackdrop.Shared#ensureBaseCapture).
 *  - Every bubble is already drawn once into a whole-bubble Picture/RenderNode cache and only
 *    re-recorded when its content changes (see MessageBubbleCanvasView#onDraw). A STATIC
 *    gradient bakes into that cache for free; live blur would force the cache to be bypassed
 *    and re-recorded every frame instead, defeating it entirely.
 *
 * One instance per {@link MessageBubbleCanvasView} (recycled with the view, same lifetime as
 * its other per-instance Paints). Shaders are cached and rebuilt only when this bubble's
 * height or the day/night mode change, not on every draw.
 */
final class GlassBubbleSkin {

    private final float rimWidth;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF inner = new RectF();

    private LinearGradient fillShader, rimShader;
    private float shaderH = -1f;
    private boolean shaderDark;

    GlassBubbleSkin(float density) {
        rimWidth = 1f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimWidth);
    }

    /**
     * Draws the overlay on top of whatever is already painted in {@code rect} (the real
     * bubbleDrawable fill/shape). {@code cornerRadii} is the same 8-value format as
     * {@code GradientDrawable#setCornerRadii} (TL, TR, BR, BL pairs).
     */
    void draw(Canvas canvas, RectF rect, float[] cornerRadii, boolean dark) {
        if (rect.width() <= 0f || rect.height() <= 0f) return;
        ensureShaders(rect.height(), dark);

        path.rewind();
        path.addRoundRect(rect, cornerRadii, Path.Direction.CW);

        int save = canvas.save();
        canvas.clipPath(path);
        fill.setShader(fillShader);
        canvas.drawPath(path, fill);
        canvas.restoreToCount(save);

        float rad = avgRadius(cornerRadii);
        inner.set(rect);
        inner.inset(rimWidth / 2f, rimWidth / 2f);
        rim.setShader(rimShader);
        canvas.drawRoundRect(inner, rad - rimWidth / 2f, rad - rimWidth / 2f, rim);
    }

    /** A single rounded rim stroke doesn't need per-corner precision like the fill path does —
     *  the average tracks the fill's rounding closely enough for a 1dp hairline. */
    private static float avgRadius(float[] r) {
        float sum = 0f;
        for (float v : r) sum += v;
        return sum / r.length;
    }

    private void ensureShaders(float h, boolean dark) {
        if (fillShader != null && h == shaderH && dark == shaderDark) return;
        shaderH = h;
        shaderDark = dark;
        // Same top-lighter-to-bottom-darker gloss recipe as GlassCapsuleSkin's fill gradient,
        // just without a live-blurred backdrop underneath it — the tint alone gives the
        // "frosted" read against the bubble's own solid color.
        fillShader = new LinearGradient(0, 0, 0, h,
                new int[]{
                        dark ? 0x26FFFFFF : 0x70FFFFFF,
                        0x0AFFFFFF,
                        0x00FFFFFF,
                        0x16000000 },
                new float[]{ 0f, 0.35f, 0.6f, 1f }, Shader.TileMode.CLAMP);
        rimShader = new LinearGradient(0, 0, 0, h,
                new int[]{ 0xC2FFFFFF, 0x28FFFFFF, 0x28FFFFFF, 0x70FFFFFF },
                new float[]{ 0f, 0.45f, 0.55f, 1f }, Shader.TileMode.CLAMP);
    }
}
