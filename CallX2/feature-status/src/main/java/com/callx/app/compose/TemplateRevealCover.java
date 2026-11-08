package com.callx.app.compose;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * v750: the "closed" look of one template box (dark fill, the box's own colour, dotted outline, "+") that the status viewer
 * lays over the photo and lifts box by box — see StatusViewerActivity#playTemplateReveal. Touch-transparent by design.
 */
public final class TemplateRevealCover extends View {

    private final int shape, color;
    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Path path = new Path();
    private float cx, cy, arm;

    public TemplateRevealCover(Context c, int shape, int color) {
        super(c);
        this.shape = shape;
        this.color = color;
        d = c.getResources().getDisplayMetrics().density;
        fill.setColor(0xFF191124);
        tint.setColor((color & 0x00FFFFFF) | 0x38000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(color);
        stroke.setStrokeWidth(3f * d);
        stroke.setPathEffect(new DashPathEffect(new float[]{9f * d, 6f * d}, 0f));
        plus.setStyle(Paint.Style.STROKE);
        plus.setStrokeCap(Paint.Cap.ROUND);
        plus.setColor(color);
        plus.setStrokeWidth(3f * d);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        final float in = 1.5f * d;
        path = TemplateBoxStyle.path(shape, new RectF(in, in, w - in, h - in));
        cx = w / 2f;
        cy = h / 2f;
        arm = Math.min(w, h) * 0.12f;
    }

    @Override protected void onDraw(Canvas c) {
        c.drawPath(path, fill);
        c.drawPath(path, tint);
        c.drawPath(path, stroke);
        c.drawLine(cx - arm, cy, cx + arm, cy, plus);
        c.drawLine(cx, cy - arm, cx, cy + arm, plus);
    }
}
