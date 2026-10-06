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
 * Lightweight thread connector for a reply row: a 2dp vertical trunk on the
 * left that branches into a small curved arrow (the "↳") pointing at the reply
 * avatar.
 *
 * - Last reply:      trunk runs from the top of the row to the curve and stops.
 * - Non-last reply:  trunk runs the FULL row height (top to bottom) and the
 *                    curve branches off it, so consecutive rows join with no gap.
 *
 * The row must NOT have vertical padding around this view (padding clips the
 * trunk) - spacing lives in the avatar/body margins instead, see
 * item_reel_reply.xml.
 *
 * Everything (trunk, branch, arrow head) goes into ONE Path drawn with ONE
 * Paint. The colour is translucent (thread_line has alpha), and a single
 * stroked path is filled as a union, so overlaps (trunk/branch junction, arm/
 * arrow tip) do not double-blend into darker dots.
 */
public class ReplyConnectorView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  path  = new Path();
    private final RectF arc   = new RectF();

    private final float stroke;   // 2dp line
    private final float radius;   // curve radius (8dp)
    private final float curveY;   // y of the horizontal arm = reply avatar centre
    private final float arm;      // horizontal arm length incl. arrow head (20dp)
    private final float head;     // arrow head size (4dp)

    private boolean last = true;

    public ReplyConnectorView(Context c) { this(c, null); }

    public ReplyConnectorView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        stroke = 2f * d;
        radius = 8f * d;
        curveY = 23f * d;   // avatar marginTop 10 (row spacing 8 + 2) + half of 26dp avatar
        arm    = 20f * d;
        head   = 4f * d;

        // thread_line carries its own alpha (60% = 0x99) and has a separate
        // light / dark value, so no manual alpha masking here.
        paint.setColor(ContextCompat.getColor(c, R.color.thread_line));
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
        final float x = stroke / 2f;
        final float h = getHeight();
        final float endX = Math.min(x + arm, getWidth() - stroke);

        path.reset();
        if (last) {
            // trunk stops where the curve begins
            path.moveTo(x, 0);
            path.lineTo(x, curveY - radius);
        } else {
            // trunk runs the full height; the curve branches off it
            path.moveTo(x, 0);
            path.lineTo(x, h);
            path.moveTo(x, curveY - radius);
        }
        // rounded corner into the horizontal arm
        arc.set(x, curveY - 2 * radius, x + 2 * radius, curveY);
        path.arcTo(arc, 180f, -90f, false);
        path.lineTo(endX, curveY);

        // arrow head (↳) as its own sub-path of the same Path
        path.moveTo(endX - head, curveY - head);
        path.lineTo(endX, curveY);
        path.lineTo(endX - head, curveY + head);

        canvas.drawPath(path, paint);
    }
}
