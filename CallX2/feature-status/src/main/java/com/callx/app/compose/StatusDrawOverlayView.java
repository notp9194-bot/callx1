package com.callx.app.compose;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;

/**
 * Transparent finger-drawing layer used by the status story editor.
 * v587: per-stroke colour + width, eraser, undo/redo, single-pointer tracking
 * (a stray 2nd finger no longer makes the path jump) and ancestor scroll lock
 * while drawing (NestedScrollView can no longer steal vertical strokes).
 */
final class StatusDrawOverlayView extends View {

    interface Listener {
        void onModeChanged(boolean enabled);
        void onHistoryChanged(boolean canUndo, boolean canRedo);
    }

    private static final class Stroke {
        final Path path = new Path();
        final Paint paint;
        Stroke(Paint paint) { this.paint = paint; }
    }

    private static final float ERASER_WIDTH_FACTOR = 2.5f;

    private final List<Stroke> strokes = new ArrayList<>();
    private final List<Stroke> redoStack = new ArrayList<>();
    private Stroke activeStroke;
    private int activePointerId = -1;
    private boolean drawEnabled;
    private float lastX, lastY;
    private int color = Color.WHITE;
    private float widthPx;
    private boolean eraser;
    private Listener listener;

    StatusDrawOverlayView(Context context) {
        super(context);
        init(context);
    }

    StatusDrawOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        // Software layer: the eraser (PorterDuff.CLEAR) needs a transparent
        // backing bitmap to punch holes in; also keeps export snapshots exact.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        widthPx = 6f * context.getResources().getDisplayMetrics().density;
    }

    void setListener(Listener l) { listener = l; }

    void setBrushColor(int c) { color = c; eraser = false; }
    void setBrushWidthPx(float px) { widthPx = Math.max(1f, px); }
    void setEraser(boolean on) { eraser = on; }
    boolean isEraser() { return eraser; }
    boolean isDrawEnabled() { return drawEnabled; }
    boolean hasDrawing() { return !strokes.isEmpty(); }
    boolean canUndo() { return !strokes.isEmpty(); }
    boolean canRedo() { return !redoStack.isEmpty(); }

    void setDrawEnabled(boolean enabled) {
        boolean changed = drawEnabled != enabled;
        drawEnabled = enabled;
        setClickable(enabled);
        if (!enabled) { finishStroke(); lockParents(false); }
        if (changed && listener != null) listener.onModeChanged(enabled);
    }

    void clearDrawing() {
        strokes.clear();
        redoStack.clear();
        activeStroke = null;
        activePointerId = -1;
        invalidate();
        notifyHistory();
    }

    void undo() {
        if (strokes.isEmpty()) return;
        redoStack.add(strokes.remove(strokes.size() - 1));
        invalidate();
        notifyHistory();
    }

    void redo() {
        if (redoStack.isEmpty()) return;
        strokes.add(redoStack.remove(redoStack.size() - 1));
        invalidate();
        notifyHistory();
    }

    private void notifyHistory() {
        if (listener != null) listener.onHistoryChanged(canUndo(), canRedo());
    }

    private Paint newPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        if (eraser) {
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            p.setStrokeWidth(widthPx * ERASER_WIDTH_FACTOR);
        } else {
            p.setColor(color);
            p.setStrokeWidth(widthPx);
        }
        return p;
    }

    private void lockParents(boolean lock) {
        ViewParent p = getParent();
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(lock);
            p = p.getParent();
        }
    }

    private void finishStroke() {
        if (activeStroke != null) {
            activeStroke = null;
            notifyHistory();
        }
        activePointerId = -1;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Stroke s : strokes) canvas.drawPath(s.path, s.paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!drawEnabled) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lockParents(true);
                activePointerId = event.getPointerId(0);
                lastX = event.getX();
                lastY = event.getY();
                activeStroke = new Stroke(newPaint());
                activeStroke.path.moveTo(lastX, lastY);
                strokes.add(activeStroke);
                redoStack.clear(); // new stroke invalidates redo history
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (activeStroke == null) return true;
                int idx = event.findPointerIndex(activePointerId);
                if (idx < 0) return true; // ignore any other finger
                float x = event.getX(idx), y = event.getY(idx);
                activeStroke.path.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f);
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                // The drawing finger lifted while another stayed down → end stroke.
                if (event.getPointerId(event.getActionIndex()) == activePointerId) {
                    if (activeStroke != null) activeStroke.path.lineTo(lastX, lastY);
                    finishStroke();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP: {
                if (activeStroke != null) {
                    int idx = event.findPointerIndex(activePointerId);
                    float x = idx >= 0 ? event.getX(idx) : lastX;
                    float y = idx >= 0 ? event.getY(idx) : lastY;
                    activeStroke.path.lineTo(x, y); // single tap → round dot
                }
                finishStroke();
                lockParents(false);
                invalidate();
                performClick();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                finishStroke();
                lockParents(false);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
