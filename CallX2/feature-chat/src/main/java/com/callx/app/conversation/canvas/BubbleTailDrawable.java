package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Chat bubble background with an optional WhatsApp-style tail: a small triangle that
 * pokes OUT of the top corner on the sender's side (top-left = received, top-right = sent).
 *
 * Perf design (this is drawn for every visible bubble):
 *  - Stateless w.r.t. bounds, so ONE instance per (sent, tail) is shared process-wide
 *    (see MessageBubbleCanvasView.sharedBubbleDrawable) — nothing is rebuilt per row.
 *  - The body is a plain drawRoundRect() (GPU fast path, no path rasterisation).
 *  - The tail corner is squared with one drawRect(), and the tail itself is a tiny Path
 *    in local coordinates, built ONCE per density with its rounded tip baked in (no
 *    path effect at draw time). Because every row draws the same Path object, HWUI
 *    caches a single texture for all tails.
 *  - Tail pieces overlap the body by 1dp (opaque colours), so there are no AA seams.
 *
 * The tail is drawn OUTSIDE getBounds(); the caller must leave tailWidth of room on that
 * side (MessageBubbleCanvasView reserves it in its layout).
 */
final class BubbleTailDrawable extends Drawable {

    private final boolean sent;
    private final boolean tail;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tailPath = new Path();   // local coords, origin = body's top corner on the tail side
    // Square corner (r x r) + tail fused into ONE path (Path.op UNION, built once): the tail
    // bubble costs a single drawPath instead of drawRect + drawPath. Same local origin as tailPath.
    private final Path patchPath = new Path();
    private boolean patchOk;
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean shadow;          // only for LIGHT bubble colours (they vanish on a light wallpaper)
    private float shadowDy;
    private float radius;
    private float density = -1f;

    BubbleTailDrawable(boolean sent, boolean tail) {
        this.sent = sent;
        this.tail = tail;
        paint.setStyle(Paint.Style.FILL);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(0x1A000000);          // ~10% black
    }

    /** Cheap no-op when nothing changed. */
    void configure(int color, float cornerRadiusPx, float tailWPx, float tailHPx, float densityKey) {
        if (paint.getColor() != color) paint.setColor(color);
        // Perceived luminance > ~0.6 => light bubble => add a 1dp soft drop edge below it.
        final float lum = (0.299f * ((color >> 16) & 0xFF) + 0.587f * ((color >> 8) & 0xFF)
                + 0.114f * (color & 0xFF)) / 255f;
        shadow = lum > 0.6f;
        shadowDy = 1f * densityKey;
        radius = cornerRadiusPx;
        if (tail && densityKey != density) {
            density = densityKey;
            buildTail(tailWPx, tailHPx, 1f * densityKey);
        }
    }

    private void buildTail(float tw, float th, float overlap) {
        // Built in received orientation (tail LEFT of the body, origin = body top-left);
        // for sent every x is mirrored by k = -1 directly (no Matrix object needed).
        final float k = sent ? -1f : 1f;
        final Path raw = new Path();
        raw.moveTo(-tw * k, 0f);                   // tip
        raw.lineTo(overlap * k, 0f);               // along the top edge, into the body
        raw.lineTo(overlap * k, th);
        raw.lineTo(0f, th);                        // down the body's side edge
        raw.cubicTo(0f, th * 0.55f, -tw * 0.30f * k, th * 0.14f, -tw * k, 0f);   // concave swoosh back to the tip
        raw.close();
        // Bake a slightly rounded tip once (a path effect at draw time would be CPU work per frame).
        final Paint bake = new Paint(Paint.ANTI_ALIAS_FLAG);
        bake.setStyle(Paint.Style.FILL);
        bake.setPathEffect(new CornerPathEffect(tw * 0.22f));
        tailPath.reset();
        bake.getFillPath(raw, tailPath);

        // Fuse the squared corner into the tail path once (r is fixed per density).
        final Path square = new Path();
        if (sent) square.addRect(-radius, 0f, 0f, radius, Path.Direction.CW);
        else      square.addRect(0f, 0f, radius, radius, Path.Direction.CW);
        patchPath.reset();
        patchOk = radius > 0f && patchPath.op(square, tailPath, Path.Op.UNION);
        if (!patchOk) patchPath.reset();           // fall back to drawRect + drawPath at draw time
    }

    @Override
    public void draw(@NonNull Canvas c) {
        final Rect b = getBounds();
        final float r = Math.min(radius, Math.min(b.width(), b.height()) / 2f);
        // The fused patch is only valid while the corner radius isn't clamped by a tiny bubble.
        final boolean fused = tail && patchOk && r == radius;
        if (shadow) {                                  // soft edge under bubble + tail, drawn first
            drawShape(c, b.left, b.top + shadowDy, b.right, b.bottom + shadowDy, r, fused, shadowPaint);
        }
        drawShape(c, b.left, b.top, b.right, b.bottom, r, fused, paint);
    }

    private void drawShape(Canvas c, float l, float t, float rt, float bt, float r,
                           boolean fused, Paint p) {
        c.drawRoundRect(l, t, rt, bt, r, r, p);
        if (!tail) return;
        final int save = c.save();
        c.translate(sent ? rt : l, t);
        if (fused) {
            c.drawPath(patchPath, p);                  // squared corner + tail, one shared texture
        } else {
            // Square off the corner the tail grows from (translated: sent = left of origin).
            if (sent) c.drawRect(-r, 0f, 0f, r, p);
            else      c.drawRect(0f, 0f, r, r, p);
            c.drawPath(tailPath, p);
        }
        c.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
