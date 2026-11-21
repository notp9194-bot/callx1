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
        // Received orientation (tail to the LEFT of the body, origin at body top-left).
        final Path raw = new Path();
        raw.moveTo(-tw, 0f);                       // tip
        raw.lineTo(overlap, 0f);                   // along the top edge, into the body
        raw.lineTo(overlap, th);
        raw.lineTo(0f, th);                        // down the body's left edge
        raw.cubicTo(0f, th * 0.55f, -tw * 0.30f, th * 0.14f, -tw, 0f);   // concave swoosh back to the tip
        raw.close();
        if (sent) {                                // mirror for the right side
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(-1f, 1f);
            raw.transform(m);
        }
        // Bake a slightly rounded tip once (a path effect at draw time would be CPU work per frame).
        final Paint bake = new Paint(Paint.ANTI_ALIAS_FLAG);
        bake.setStyle(Paint.Style.FILL);
        bake.setPathEffect(new CornerPathEffect(tw * 0.22f));
        tailPath.reset();
        bake.getFillPath(raw, tailPath);
    }

    @Override
    public void draw(@NonNull Canvas c) {
        final Rect b = getBounds();
        final float r = Math.min(radius, Math.min(b.width(), b.height()) / 2f);
        if (shadow) {                                  // soft edge under bubble + tail, drawn first
            final int s = c.save();
            c.translate(0f, shadowDy);
            drawShape(c, b, r, shadowPaint);
            c.restoreToCount(s);
        }
        drawShape(c, b, r, paint);
    }

    private void drawShape(Canvas c, Rect b, float r, Paint p) {
        c.drawRoundRect(b.left, b.top, b.right, b.bottom, r, r, p);
        if (!tail) return;
        // Square off the corner the tail grows from.
        if (sent) c.drawRect(b.right - r, b.top, b.right, b.top + r, p);
        else      c.drawRect(b.left, b.top, b.left + r, b.top + r, p);
        final int save = c.save();
        c.translate(sent ? b.right : b.left, b.top);
        c.drawPath(tailPath, p);
        c.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
