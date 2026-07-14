package com.callx.app.conversation.controllers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Freehand pencil-drawing surface for {@link MediaEditActivity}'s "pencil"
 * tool. Transparent by default so it can sit on top of the photo + sticker
 * layer; only intercepts touches while {@link #setDrawingEnabled} is true
 * (so stickers underneath stay draggable the rest of the time).
 *
 * Strokes are stored as normalized (0..1) points relative to this view's
 * own size, via {@link Stroke}, so {@link MediaEditActivity} can re-draw
 * them at full photo resolution when baking the final send-out bitmap —
 * the exact same shape the user drew, just scaled up.
 */
public class DrawOverlayView extends View {

    /** One freehand stroke: ordered normalized points + the color/width it was drawn with. */
    public static final class Stroke {
        public final List<PointF> points = new ArrayList<>();
        public final int color;
        public final float widthDp;

        public Stroke(int color, float widthDp) {
            this.color = color;
            this.widthDp = widthDp;
        }
    }

    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke current;
    private final Paint paint = new Paint();
    private int activeColor = Color.RED;
    private float activeWidthDp = 6f;
    private boolean drawingEnabled = false;

    public DrawOverlayView(Context context) { super(context); init(); }
    public DrawOverlayView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setDrawingEnabled(boolean enabled) {
        this.drawingEnabled = enabled;
        // Only claim touches while actively drawing — otherwise let them
        // fall through to the sticker layer / swipe-up gesture underneath.
        setClickable(enabled);
    }

    public void setActiveColor(int color) { this.activeColor = color; }

    public void setActiveWidthDp(float widthDp) { this.activeWidthDp = widthDp; }

    public List<Stroke> getStrokes() { return strokes; }

    public void undoLastStroke() {
        if (!strokes.isEmpty()) {
            strokes.remove(strokes.size() - 1);
            invalidate();
        }
    }

    public void clearStrokes() {
        strokes.clear();
        invalidate();
    }

    /** Restores previously-recorded strokes (e.g. switching back to an item already drawn on). */
    public void setStrokes(List<Stroke> restored) {
        strokes.clear();
        if (restored != null) strokes.addAll(restored);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!drawingEnabled) return false;
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                current = new Stroke(activeColor, activeWidthDp);
                current.points.add(new PointF(event.getX() / w, event.getY() / h));
                strokes.add(current);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (current != null) {
                    current.points.add(new PointF(event.getX() / w, event.getY() / h));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                current = null;
                return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawStrokes(canvas, strokes, getWidth(), getHeight(), getResources().getDisplayMetrics().density);
    }

    /**
     * Shared by the live overlay (onDraw above) and MediaEditActivity's final
     * bake step, so the drawing looks identical at preview size and at full
     * photo resolution — callers just pass the target canvas's own
     * width/height and a widthScale (1f for screen-density dp, or
     * fullResPx/viewPx for baking).
     */
    public static void drawStrokes(Canvas canvas, List<Stroke> strokes, float targetW, float targetH, float strokeWidthScale) {
        if (strokes.isEmpty()) return;
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        for (Stroke s : strokes) {
            if (s.points.size() < 2) continue;
            Path path = new Path();
            PointF first = s.points.get(0);
            path.moveTo(first.x * targetW, first.y * targetH);
            for (int i = 1; i < s.points.size(); i++) {
                PointF pt = s.points.get(i);
                path.lineTo(pt.x * targetW, pt.y * targetH);
            }
            p.setColor(s.color);
            p.setStrokeWidth(s.widthDp * strokeWidthScale);
            canvas.drawPath(path, p);
        }
    }
}
