package com.callx.app.comments;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.callx.app.reels.R;

/**
 * Lightweight thread connector for a reply row: a 2dp vertical line on the
 * left that bends into a small curved arrow (the "↳") pointing at the reply
 * avatar. If the row is not the last reply, the line continues to the bottom
 * so the next reply connects to the same trunk.
 *
 * No glass/blur/bitmaps — one Path + one Paint, drawn only when state changes,
 * so it costs nothing during fling.
 */
public class ReplyConnectorView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  path  = new Path();
    private final RectF arc   = new RectF();

    private final float stroke;   // 2dp line
    private final float radius;   // curve radius
    private final float curveY;   // y of the horizontal arm = avatar centre
    private final float arm;      // horizontal arm length incl. arrow head
    private final float head;     // arrow head size

    private boolean last = true;

    public ReplyConnectorView(Context c) { this(c, null); }

    public ReplyConnectorView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        stroke = 2f * d;
        radius = 10f * d;
        curveY = 23f * d;   // paddingTop 8 + avatar marginTop 2 + half of 26dp avatar
        arm    = 22f * d;
        head   = 3f * d;

        int base = ContextCompat.getColor(c, R.color.text_muted);
        paint.setColor((base & 0x00FFFFFF) | 0x66000000); // subtle: ~40% alpha
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** true = line stops at the curve (last reply); false = line continues down. */
    public void setLast(boolean isLast) {
        if (last != isLast) { last = isLast; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float x = stroke / 2f;
        path.reset();
        // vertical trunk
        path.moveTo(x, 0);
        path.lineTo(x, curveY - radius);
        // rounded corner into the horizontal arm
        arc.set(x, curveY - 2 * radius, x + 2 * radius, curveY);
        path.arcTo(arc, 180f, -90f, false);
        float endX = Math.min(x + arm, getWidth() - stroke);
        path.lineTo(endX, curveY);
        canvas.drawPath(path, paint);

        // small arrow head (↳)
        path.reset();
        path.moveTo(endX - head, curveY - head);
        path.lineTo(endX, curveY);
        path.lineTo(endX - head, curveY + head);
        canvas.drawPath(path, paint);

        // trunk continues for non-last replies
        if (!last) {
            canvas.drawLine(x, curveY, x, getHeight(), paint);
        }
    }
}
