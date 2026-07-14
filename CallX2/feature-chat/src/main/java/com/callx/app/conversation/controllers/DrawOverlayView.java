package com.callx.app.conversation.controllers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Freehand drawing surface for {@link MediaEditActivity}'s pencil tool.
 *
 * Features:
 *  ✅ Smooth anti-aliased strokes with round caps/joins
 *  ✅ Eraser mode — uses PorterDuff.CLEAR on an offscreen layer so strokes
 *     are truly erased (not painted over), leaving the photo visible again
 *  ✅ Undo (removes last stroke)
 *  ✅ Clear all strokes
 *  ✅ Normalized (0..1) point storage so the same stroke data scales correctly
 *     both for the live preview and for the full-res bake in MediaEditActivity
 *  ✅ bindStrokes() — binds to the per-item backing list so edits survive
 *     switching items in the thumbnail strip without any sync step
 */
public class DrawOverlayView extends View {

    // ── Stroke model ──────────────────────────────────────────────────────

    /** One freehand stroke — ordered normalized points + style. */
    public static final class Stroke {
        public final List<PointF> points = new ArrayList<>();
        public final int   color;
        public final float widthDp;
        public final boolean eraser;   // true → CLEAR xfer mode (erase pixels)

        public Stroke(int color, float widthDp, boolean eraser) {
            this.color   = color;
            this.widthDp = widthDp;
            this.eraser  = eraser;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────
    private List<Stroke> strokes         = new ArrayList<>();
    private Stroke       currentStroke;
    private int          activeColor     = Color.WHITE;
    private float        activeWidthDp   = 8f;
    private boolean      drawingEnabled  = false;
    private boolean      eraserMode      = false;

    // ── Offscreen layer (required for PorterDuff.CLEAR to work) ──────────
    private Bitmap offscreen;
    private Canvas offscreenCanvas;
    private boolean needsFullRedraw = true;

    // ── Paints ────────────────────────────────────────────────────────────
    private static final PorterDuffXfermode ERASE_MODE =
            new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

    // ═════════════════════════════════════════════════════════════════════
    public DrawOverlayView(Context ctx) { super(ctx); init(); }
    public DrawOverlayView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public DrawOverlayView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        // Software layer required — hardware-accelerated canvas doesn't
        // support PorterDuff.CLEAR without a saved layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════

    public void setDrawingEnabled(boolean enabled) {
        this.drawingEnabled = enabled;
        setClickable(enabled);
    }

    public void setActiveColor(int color) {
        this.activeColor = color;
    }

    public void setActiveWidthDp(float widthDp) {
        this.activeWidthDp = widthDp;
    }

    public void setEraserMode(boolean eraser) {
        this.eraserMode = eraser;
    }

    public boolean isEraserMode() {
        return eraserMode;
    }

    public List<Stroke> getStrokes() {
        return strokes;
    }

    public void undoLastStroke() {
        if (!strokes.isEmpty()) {
            strokes.remove(strokes.size() - 1);
            needsFullRedraw = true;
            invalidate();
        }
    }

    public void clearStrokes() {
        strokes.clear();
        needsFullRedraw = true;
        invalidate();
    }

    /**
     * Binds this view to a caller-owned backing list so strokes survive
     * item switching without any external sync step.
     */
    public void bindStrokes(List<Stroke> backing) {
        this.strokes = (backing != null) ? backing : new ArrayList<>();
        needsFullRedraw = true;
        invalidate();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Touch handling
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (!drawingEnabled) return false;
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return false;

        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                currentStroke = new Stroke(activeColor, activeWidthDp, eraserMode);
                currentStroke.points.add(new PointF(event.getX() / w, event.getY() / h));
                strokes.add(currentStroke);
                // Draw this first point live
                drawSingleStrokeOnOffscreen(currentStroke);
                invalidate();
                return true;

            case android.view.MotionEvent.ACTION_MOVE:
                if (currentStroke != null) {
                    // Historical points for smooth curves at high speed
                    int histCount = event.getHistorySize();
                    for (int hi = 0; hi < histCount; hi++) {
                        currentStroke.points.add(
                                new PointF(event.getHistoricalX(hi) / w,
                                           event.getHistoricalY(hi) / h));
                    }
                    currentStroke.points.add(new PointF(event.getX() / w, event.getY() / h));
                    // Redraw just the current stroke on top of committed offscreen
                    drawSingleStrokeOnOffscreen(currentStroke);
                    invalidate();
                }
                return true;

            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                currentStroke = null;
                return true;
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Drawing
    // ═════════════════════════════════════════════════════════════════════

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w > 0 && h > 0) {
            // Recreate offscreen bitmap at new size
            if (offscreen != null) offscreen.recycle();
            offscreen = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            offscreenCanvas = new Canvas(offscreen);
            needsFullRedraw = true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (offscreen == null) return;

        // If we need a full redraw (after undo/clear/bind), repaint offscreen
        if (needsFullRedraw) {
            offscreenCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            for (Stroke s : strokes) {
                if (s != currentStroke) drawSingleStrokeOnOffscreen(s);
            }
            if (currentStroke != null) drawSingleStrokeOnOffscreen(currentStroke);
            needsFullRedraw = false;
        }

        canvas.drawBitmap(offscreen, 0, 0, null);
    }

    /**
     * Draws one stroke onto the offscreen bitmap.
     * Eraser strokes use CLEAR xfer mode so they genuinely remove pixels.
     */
    private void drawSingleStrokeOnOffscreen(Stroke s) {
        if (offscreenCanvas == null || s == null || s.points.size() < 1) return;
        float density = getResources().getDisplayMetrics().density;
        float strokePx = s.widthDp * density;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(strokePx);

        if (s.eraser) {
            p.setXfermode(ERASE_MODE);
            p.setColor(Color.TRANSPARENT);
        } else {
            p.setColor(s.color);
        }

        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (s.points.size() == 1) {
            // Single-point tap → draw a filled circle
            PointF pt = s.points.get(0);
            Paint dot = new Paint(p);
            dot.setStyle(Paint.Style.FILL);
            offscreenCanvas.drawCircle(pt.x * w, pt.y * h, strokePx / 2f, dot);
            return;
        }

        Path path = new Path();
        PointF first = s.points.get(0);
        path.moveTo(first.x * w, first.y * h);
        for (int i = 1; i < s.points.size() - 1; i++) {
            PointF p1 = s.points.get(i);
            PointF p2 = s.points.get(i + 1);
            // Quadratic bezier for smooth curves
            float midX = (p1.x + p2.x) / 2f * w;
            float midY = (p1.y + p2.y) / 2f * h;
            path.quadTo(p1.x * w, p1.y * h, midX, midY);
        }
        PointF last = s.points.get(s.points.size() - 1);
        path.lineTo(last.x * w, last.y * h);
        offscreenCanvas.drawPath(path, p);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Static bake helper (called by MediaEditActivity for final send export)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Bakes all strokes onto a Canvas at target resolution.
     * Used by MediaEditActivity when compositing the final send-out bitmap.
     * See the fitCenter-mapping note above the parameter list below.
     *
     * @param canvas      destination canvas (full-res photo bitmap)
     * @param strokes     stroke list from EditState
     * @param targetW     canvas width in pixels
     * @param targetH     canvas height in pixels
     * @param strokeScale density scale factor (fullResPx / viewPx)
     */
    public static void drawStrokes(Canvas canvas, List<Stroke> strokes,
                                   float targetW, float targetH,
                                   float viewW, float viewH,
                                   float offX, float offY, float fitScale,
                                   float density) {
        if (strokes == null || strokes.isEmpty()) return;
        if (fitScale <= 0f) fitScale = 1f;

        // Save layer for CLEAR mode to work correctly
        int sc = canvas.saveLayer(0, 0, targetW, targetH, null);

        for (Stroke s : strokes) {
            if (s.points.isEmpty()) continue;
            float strokePx = (s.widthDp * density) / fitScale;

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(strokePx);

            if (s.eraser) {
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                p.setColor(Color.TRANSPARENT);
            } else {
                p.setColor(s.color);
            }

            if (s.points.size() == 1) {
                PointF pt = s.points.get(0);
                float px = ((pt.x * viewW) - offX) / fitScale;
                float py = ((pt.y * viewH) - offY) / fitScale;
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                canvas.drawCircle(px, py, strokePx / 2f, dot);
                continue;
            }

            Path path = new Path();
            PointF first = s.points.get(0);
            float fx = ((first.x * viewW) - offX) / fitScale;
            float fy = ((first.y * viewH) - offY) / fitScale;
            path.moveTo(fx, fy);
            for (int i = 1; i < s.points.size() - 1; i++) {
                PointF p1 = s.points.get(i);
                PointF p2 = s.points.get(i + 1);
                float x1 = ((p1.x * viewW) - offX) / fitScale;
                float y1 = ((p1.y * viewH) - offY) / fitScale;
                float x2 = ((p2.x * viewW) - offX) / fitScale;
                float y2 = ((p2.y * viewH) - offY) / fitScale;
                float midX = (x1 + x2) / 2f;
                float midY = (y1 + y2) / 2f;
                path.quadTo(x1, y1, midX, midY);
            }
            PointF last = s.points.get(s.points.size() - 1);
            float lx = ((last.x * viewW) - offX) / fitScale;
            float ly = ((last.y * viewH) - offY) / fitScale;
            path.lineTo(lx, ly);
            canvas.drawPath(path, p);
        }

        canvas.restoreToCount(sc);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (offscreen != null) {
            offscreen.recycle();
            offscreen = null;
        }
    }
}
