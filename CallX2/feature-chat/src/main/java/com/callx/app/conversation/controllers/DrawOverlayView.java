package com.callx.app.conversation.controllers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Freehand drawing surface for {@link MediaEditActivity}'s pencil tool.
 *
 * Features:
 *  ✅ Smooth anti-aliased strokes with round caps/joins
 *  ✅ Multiple brush types — Pen, Highlighter, Marker, Ink, Crayon, Neon —
 *     selected via {@link #setActiveBrushType(int)}, matching the advanced
 *     brush row in the draw tools panel
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

    // ── Brush types ──────────────────────────────────────────────────────
    public static final int BRUSH_PEN         = 0; // solid round stroke (default / fast path)
    public static final int BRUSH_HIGHLIGHTER = 1; // flat cap, wide, translucent
    public static final int BRUSH_INK         = 2; // round cap, soft wet-ink bleed halo
    public static final int BRUSH_CRAYON      = 3; // round cap, grainy stippled texture
    public static final int BRUSH_NEON        = 4; // blurred glow halo + bright core
    public static final int BRUSH_MARKER      = 5; // flat cap, bold, semi-opaque
    public static final int BRUSH_BLUR        = 6; // pixelate/blur reveal — for hiding faces, plates, etc.

    // ── Shape tools ──────────────────────────────────────────────────────
    public static final int SHAPE_FREEHAND = 0; // ordinary multi-point freehand path (default)
    public static final int SHAPE_LINE     = 1; // straight line, start → end
    public static final int SHAPE_ARROW    = 2; // straight line with an arrowhead at the end
    public static final int SHAPE_RECT     = 3; // axis-aligned rectangle from the drag bounding box
    public static final int SHAPE_OVAL     = 4; // oval/circle inscribed in the drag bounding box

    // ── Stroke model ──────────────────────────────────────────────────────

    /**
     * One stroke — either a freehand path (many points) or a two-point
     * shape (start/end only; see {@link #shapeType}). Ordered normalized
     * (0..1) points + style.
     */
    public static final class Stroke {
        public final List<PointF> points = new ArrayList<>();
        public final int   color;
        public final float widthDp;
        public final boolean eraser;   // true → CLEAR xfer mode (erase pixels)
        public final int   brushType;  // one of the BRUSH_* constants above
        public final int   shapeType;  // one of the SHAPE_* constants above

        public Stroke(int color, float widthDp, boolean eraser) {
            this(color, widthDp, eraser, BRUSH_PEN, SHAPE_FREEHAND);
        }

        public Stroke(int color, float widthDp, boolean eraser, int brushType) {
            this(color, widthDp, eraser, brushType, SHAPE_FREEHAND);
        }

        public Stroke(int color, float widthDp, boolean eraser, int brushType, int shapeType) {
            this.color     = color;
            this.widthDp   = widthDp;
            this.eraser    = eraser;
            this.brushType = brushType;
            this.shapeType = shapeType;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────
    private List<Stroke> strokes         = new ArrayList<>();
    /** Strokes popped by undo, replayable via {@link #redoLastStroke()} until a new stroke is drawn. */
    private List<Stroke> redoStack       = new ArrayList<>();
    private Stroke       currentStroke;
    private int          activeColor     = Color.WHITE;
    private float        activeWidthDp   = 8f;
    private boolean      drawingEnabled  = false;
    private boolean      eraserMode      = false;
    private int          activeBrushType = BRUSH_PEN;
    private int          activeShapeType = SHAPE_FREEHAND;

    // ── Blur/pixelate brush source ──────────────────────────────────────
    // A pre-pixelated snapshot of what's currently showing underneath this
    // overlay (the photo, post rotation/flip/filter), plus the matrix that
    // maps ITS pixel space into THIS view's local pixel space. Painting a
    // BRUSH_BLUR stroke with a BitmapShader built from this bitmap+matrix
    // makes the stroke reveal blurred/pixelated content exactly where the
    // finger drags — the classic "redact a face/plate" brush. Refreshed by
    // MediaEditActivity any time the underlying image changes (item switch,
    // rotate, flip, filter, or adjustment change).
    private Bitmap blurSourceBitmap;
    private Matrix blurSourceMatrix;

    /** Notified after a stroke is committed, undone, redone, or cleared — lets the host refresh Undo/Redo button state. */
    public interface OnStrokeChangeListener { void onStrokesChanged(); }
    private OnStrokeChangeListener strokeChangeListener;
    public void setOnStrokeChangeListener(OnStrokeChangeListener l) { this.strokeChangeListener = l; }
    private void notifyStrokesChanged() {
        if (strokeChangeListener != null) strokeChangeListener.onStrokesChanged();
    }

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
        // support PorterDuff.CLEAR (or BlurMaskFilter, used by the Ink/Neon
        // brushes) without a saved layer.
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

    /** Sets the brush used for the NEXT stroke — one of the BRUSH_* constants. */
    public void setActiveBrushType(int brushType) {
        this.activeBrushType = brushType;
    }

    public int getActiveBrushType() {
        return activeBrushType;
    }

    /** Sets the shape used for the NEXT stroke — one of the SHAPE_* constants. SHAPE_FREEHAND is ordinary drawing. */
    public void setActiveShapeType(int shapeType) {
        this.activeShapeType = shapeType;
    }

    public int getActiveShapeType() {
        return activeShapeType;
    }

    public List<Stroke> getStrokes() {
        return strokes;
    }

    /**
     * Supplies the pixelated snapshot used by the Blur/Pixelate brush, and
     * the matrix mapping that bitmap's pixel space into this view's local
     * pixel space (typically {@code ivPreview.getImageMatrix()}, since this
     * overlay sits exactly on top of the preview ImageView). Pass
     * {@code null} for either to disable the blur brush (it will silently
     * fall back to a solid redaction color instead of crashing).
     */
    public void setBlurSource(Bitmap pixelatedBitmap, Matrix viewMatrix) {
        this.blurSourceBitmap = pixelatedBitmap;
        this.blurSourceMatrix = viewMatrix;
        needsFullRedraw = true;
        invalidate();
    }

    /**
     * Produces a blocky pixelated copy of {@code src} at its own resolution —
     * downscale to roughly {@code src.width / blockDivisor} then scale back
     * up with nearest-neighbour filtering off, so blocks stay crisp instead
     * of blurring into a smooth gaussian look. Used both for the live-preview
     * blur source and for the final full-res bake, so the sent photo always
     * matches what the brush showed on screen.
     */
    public static Bitmap pixelate(Bitmap src, int blockDivisor) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return src;
        int smallW = Math.max(1, src.getWidth()  / Math.max(4, blockDivisor));
        int smallH = Math.max(1, src.getHeight() / Math.max(4, blockDivisor));
        Bitmap small = Bitmap.createScaledBitmap(src, smallW, smallH, true);
        Bitmap result = Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), false);
        if (small != result) small.recycle();
        return result;
    }

    public void undoLastStroke() {
        if (!strokes.isEmpty()) {
            redoStack.add(strokes.remove(strokes.size() - 1));
            needsFullRedraw = true;
            invalidate();
            notifyStrokesChanged();
        }
    }

    /** Re-applies the most recently undone stroke, if any. Cleared once a new stroke is drawn. */
    public void redoLastStroke() {
        if (!redoStack.isEmpty()) {
            strokes.add(redoStack.remove(redoStack.size() - 1));
            needsFullRedraw = true;
            invalidate();
            notifyStrokesChanged();
        }
    }

    public boolean canUndo() {
        return !strokes.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void clearStrokes() {
        strokes.clear();
        redoStack.clear();
        needsFullRedraw = true;
        invalidate();
        notifyStrokesChanged();
    }

    /**
     * Binds this view to a caller-owned backing list so strokes survive
     * item switching without any external sync step.
     */
    public void bindStrokes(List<Stroke> backing) {
        this.strokes = (backing != null) ? backing : new ArrayList<>();
        this.redoStack.clear();
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
            case android.view.MotionEvent.ACTION_DOWN: {
                // Starting a new stroke invalidates whatever was undone before it.
                redoStack.clear();

                float sx = event.getX() / w, sy = event.getY() / h;
                boolean isShape = activeShapeType != SHAPE_FREEHAND;
                // Shapes are always drawn (not erased) with a plain solid stroke.
                currentStroke = new Stroke(activeColor, activeWidthDp,
                        !isShape && eraserMode, activeBrushType, activeShapeType);
                currentStroke.points.add(new PointF(sx, sy));
                if (isShape) {
                    // Second point is the live end-point, updated on every move.
                    currentStroke.points.add(new PointF(sx, sy));
                }
                strokes.add(currentStroke);

                if (isShape) {
                    // Shape strokes can shrink/move on every move event, so they
                    // can't be drawn additively — force a full repaint.
                    needsFullRedraw = true;
                } else {
                    drawSingleStrokeOnOffscreen(currentStroke);
                }
                invalidate();
                return true;
            }

            case android.view.MotionEvent.ACTION_MOVE:
                if (currentStroke != null) {
                    if (currentStroke.shapeType == SHAPE_FREEHAND) {
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
                    } else {
                        // Shape: only the end-point (index 1) moves.
                        float ex = event.getX() / w, ey = event.getY() / h;
                        if (currentStroke.points.size() >= 2) {
                            currentStroke.points.set(1, new PointF(ex, ey));
                        } else {
                            currentStroke.points.add(new PointF(ex, ey));
                        }
                        needsFullRedraw = true;
                    }
                    invalidate();
                }
                return true;

            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                boolean hadStroke = currentStroke != null;
                currentStroke = null;
                if (hadStroke) notifyStrokesChanged();
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

    // PERF (ultra): drawSingleStrokeOnOffscreen() runs on EVERY ACTION_MOVE
    // while the user is actively drawing — 60-100+ times/sec during a fast
    // drag once historical batched points are counted. For the default Pen
    // brush (by far the most common case) we keep the original zero-
    // allocation hot path below: a reused Paint/Path, no per-call GC.
    // The advanced brushes (Highlighter/Ink/Crayon/Neon/Marker) go through
    // renderStroke() instead, which allocates a couple of small Paint
    // objects per redraw — acceptable since only the single in-progress
    // stroke is re-rendered per move, not the whole canvas.
    private final Paint reusableStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reusableDotPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  reusablePath        = new Path();

    /**
     * Draws one stroke onto the offscreen bitmap.
     * Eraser strokes use CLEAR xfer mode so they genuinely remove pixels.
     */
    private void drawSingleStrokeOnOffscreen(Stroke s) {
        if (offscreenCanvas == null || s == null || s.points.size() < 1) return;
        float density = getResources().getDisplayMetrics().density;
        float strokePx = s.widthDp * density;
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (s.shapeType != SHAPE_FREEHAND) {
            if (s.points.size() < 2) return;
            PointF a = s.points.get(0), b = s.points.get(1);
            renderShapePrimitive(offscreenCanvas, s.shapeType, s.color, strokePx,
                    a.x * w, a.y * h, b.x * w, b.y * h);
            return;
        }

        if (s.brushType == BRUSH_PEN) {
            Paint p = reusableStrokePaint;
            p.reset();
            p.setAntiAlias(true);
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

            if (s.points.size() == 1) {
                // Single-point tap → draw a filled circle
                PointF pt = s.points.get(0);
                Paint dot = reusableDotPaint;
                dot.reset();
                dot.setAntiAlias(true);
                dot.setStyle(Paint.Style.FILL);
                dot.setColor(p.getColor());
                dot.setXfermode(p.getXfermode());
                offscreenCanvas.drawCircle(pt.x * w, pt.y * h, strokePx / 2f, dot);
                return;
            }

            Path path = reusablePath;
            path.reset();
            buildQuadPath(path, s.points, w, h);
            offscreenCanvas.drawPath(path, p);
            return;
        }

        // ── Advanced brushes (Highlighter / Marker / Ink / Crayon / Neon) ──
        if (s.points.size() == 1) {
            PointF pt = s.points.get(0);
            renderStroke(offscreenCanvas, s, null, new PointF(pt.x * w, pt.y * h), strokePx,
                    blurSourceBitmap, blurSourceMatrix);
            return;
        }
        Path path = new Path();
        buildQuadPath(path, s.points, w, h);
        renderStroke(offscreenCanvas, s, path, null, strokePx, blurSourceBitmap, blurSourceMatrix);
    }

    private static void buildQuadPath(Path path, List<PointF> pts, float w, float h) {
        PointF first = pts.get(0);
        path.moveTo(first.x * w, first.y * h);
        for (int i = 1; i < pts.size() - 1; i++) {
            PointF p1 = pts.get(i);
            PointF p2 = pts.get(i + 1);
            // Quadratic bezier for smooth curves
            float midX = (p1.x + p2.x) / 2f * w;
            float midY = (p1.y + p2.y) / 2f * h;
            path.quadTo(p1.x * w, p1.y * h, midX, midY);
        }
        PointF last = pts.get(pts.size() - 1);
        path.lineTo(last.x * w, last.y * h);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Advanced brush rendering — shared by the live view (offscreen bitmap,
    // view-space coordinates) AND the static full-res bake below (baked
    // photo canvas, transformed coordinates). Exactly one code path renders
    // each brush's look, so the exported image always matches the preview.
    // ═════════════════════════════════════════════════════════════════════

    private static void renderStroke(Canvas canvas, Stroke s, Path path, PointF singlePoint, float strokePx,
                                      Bitmap blurBmp, Matrix blurMatrix) {
        boolean erase = s.eraser;
        switch (s.brushType) {
            case BRUSH_BLUR: {
                // Reveals a pixelated copy of the underlying photo along the
                // stroke — this IS the redaction, so eraser mode is a no-op
                // for this brush (nothing sensible to "erase" back to).
                if (blurBmp != null && !blurBmp.isRecycled() && blurMatrix != null) {
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    p.setStrokeWidth(Math.max(strokePx, strokePx * 1.6f)); // blur brush reads best a bit wider
                    BitmapShader shader = new BitmapShader(blurBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader.setLocalMatrix(blurMatrix);
                    p.setShader(shader);
                    drawShape(canvas, p, path, singlePoint, strokePx * 1.6f);
                } else {
                    // No pixelated source available yet (e.g. mid-load, or
                    // video where there's no static frame to sample from) —
                    // fall back to a solid redaction block so the tool still
                    // visibly hides something instead of doing nothing.
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    p.setStrokeWidth(strokePx * 1.6f);
                    p.setColor(0xFF2B2B2B);
                    drawShape(canvas, p, path, singlePoint, strokePx * 1.6f);
                }
                break;
            }
            case BRUSH_NEON: {
                Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
                halo.setStyle(Paint.Style.STROKE);
                halo.setStrokeCap(Paint.Cap.ROUND);
                halo.setStrokeJoin(Paint.Join.ROUND);
                halo.setStrokeWidth(strokePx * 2.2f);
                halo.setColor(erase ? Color.TRANSPARENT : withAlpha(s.color, 90));
                halo.setMaskFilter(new BlurMaskFilter(Math.max(4f, strokePx * 0.6f), BlurMaskFilter.Blur.NORMAL));
                if (erase) halo.setXfermode(ERASE_MODE);
                drawShape(canvas, halo, path, singlePoint, strokePx * 2.2f);

                Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
                core.setStyle(Paint.Style.STROKE);
                core.setStrokeCap(Paint.Cap.ROUND);
                core.setStrokeJoin(Paint.Join.ROUND);
                core.setStrokeWidth(Math.max(2f, strokePx * 0.42f));
                core.setColor(erase ? Color.TRANSPARENT : lighten(s.color));
                if (erase) core.setXfermode(ERASE_MODE);
                drawShape(canvas, core, path, singlePoint, strokePx * 0.42f);
                break;
            }
            case BRUSH_INK: {
                Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
                halo.setStyle(Paint.Style.STROKE);
                halo.setStrokeCap(Paint.Cap.ROUND);
                halo.setStrokeJoin(Paint.Join.ROUND);
                halo.setStrokeWidth(strokePx * 1.35f);
                halo.setColor(erase ? Color.TRANSPARENT : withAlpha(s.color, 70));
                halo.setMaskFilter(new BlurMaskFilter(Math.max(2f, strokePx * 0.3f), BlurMaskFilter.Blur.NORMAL));
                if (erase) halo.setXfermode(ERASE_MODE);
                drawShape(canvas, halo, path, singlePoint, strokePx * 1.35f);

                Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
                core.setStyle(Paint.Style.STROKE);
                core.setStrokeCap(Paint.Cap.ROUND);
                core.setStrokeJoin(Paint.Join.ROUND);
                core.setStrokeWidth(strokePx);
                core.setColor(erase ? Color.TRANSPARENT : s.color);
                if (erase) core.setXfermode(ERASE_MODE);
                drawShape(canvas, core, path, singlePoint, strokePx);
                break;
            }
            case BRUSH_CRAYON: {
                Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
                base.setStyle(Paint.Style.STROKE);
                base.setStrokeCap(Paint.Cap.ROUND);
                base.setStrokeJoin(Paint.Join.ROUND);
                base.setStrokeWidth(strokePx);
                base.setColor(erase ? Color.TRANSPARENT : withAlpha(s.color, 210));
                if (erase) base.setXfermode(ERASE_MODE);
                drawShape(canvas, base, path, singlePoint, strokePx);

                if (!erase) {
                    Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG);
                    grain.setStyle(Paint.Style.FILL);
                    grain.setColor(withAlpha(s.color, 130));
                    sprinkleGrain(canvas, path, singlePoint, strokePx, grain);
                }
                break;
            }
            case BRUSH_HIGHLIGHTER:
            case BRUSH_MARKER: {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.SQUARE);
                p.setStrokeJoin(Paint.Join.MITER);
                boolean isHighlighter = s.brushType == BRUSH_HIGHLIGHTER;
                float mult  = isHighlighter ? 1.9f : 1.35f;
                int   alpha = isHighlighter ? 105  : 205;
                p.setStrokeWidth(strokePx * mult);
                p.setColor(erase ? Color.TRANSPARENT : withAlpha(s.color, alpha));
                if (erase) p.setXfermode(ERASE_MODE);
                drawShape(canvas, p, path, singlePoint, strokePx * mult);
                break;
            }
            case BRUSH_PEN:
            default: {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setStrokeWidth(strokePx);
                p.setColor(erase ? Color.TRANSPARENT : s.color);
                if (erase) p.setXfermode(ERASE_MODE);
                drawShape(canvas, p, path, singlePoint, strokePx);
                break;
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Shape tool rendering (Line / Arrow / Rectangle / Circle) — shared by
    // the live view (view-space pixel coords) and the static full-res bake
    // below, exactly like renderStroke() above for freehand brushes.
    // ═════════════════════════════════════════════════════════════════════

    private static void renderShapePrimitive(Canvas canvas, int shapeType, int color, float strokePx,
                                               float x1, float y1, float x2, float y2) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(strokePx);
        p.setColor(color);

        switch (shapeType) {
            case SHAPE_LINE:
                canvas.drawLine(x1, y1, x2, y2, p);
                break;
            case SHAPE_ARROW: {
                canvas.drawLine(x1, y1, x2, y2, p);
                float angle = (float) Math.atan2(y2 - y1, x2 - x1);
                float headLen = Math.max(18f, strokePx * 3.2f);
                float headAngle = (float) Math.toRadians(28);
                float hx1 = x2 - headLen * (float) Math.cos(angle - headAngle);
                float hy1 = y2 - headLen * (float) Math.sin(angle - headAngle);
                float hx2 = x2 - headLen * (float) Math.cos(angle + headAngle);
                float hy2 = y2 - headLen * (float) Math.sin(angle + headAngle);
                canvas.drawLine(x2, y2, hx1, hy1, p);
                canvas.drawLine(x2, y2, hx2, hy2, p);
                break;
            }
            case SHAPE_RECT: {
                float left = Math.min(x1, x2), right = Math.max(x1, x2);
                float top = Math.min(y1, y2), bottom = Math.max(y1, y2);
                canvas.drawRect(left, top, right, bottom, p);
                break;
            }
            case SHAPE_OVAL: {
                float left = Math.min(x1, x2), right = Math.max(x1, x2);
                float top = Math.min(y1, y2), bottom = Math.max(y1, y2);
                canvas.drawOval(left, top, right, bottom, p);
                break;
            }
            default:
                break;
        }
    }

    private static void drawShape(Canvas canvas, Paint paint, Path path, PointF singlePoint, float strokePx) {
        if (singlePoint != null) {
            Paint dot = new Paint(paint);
            dot.setStyle(Paint.Style.FILL);
            canvas.drawCircle(singlePoint.x, singlePoint.y, Math.max(1f, strokePx / 2f), dot);
        } else if (path != null) {
            canvas.drawPath(path, paint);
        }
    }

    /** Stipples small translucent dots along the stroke for a grainy crayon texture. */
    private static void sprinkleGrain(Canvas canvas, Path path, PointF singlePoint, float strokePx, Paint grainPaint) {
        if (singlePoint != null) {
            Random rnd = new Random(1);
            for (int i = 0; i < 5; i++) {
                float ang = (float) (rnd.nextDouble() * Math.PI * 2);
                float rad = (strokePx / 2f) * (0.25f + rnd.nextFloat() * 0.55f);
                float fx = singlePoint.x + (float) Math.cos(ang) * rad;
                float fy = singlePoint.y + (float) Math.sin(ang) * rad;
                canvas.drawCircle(fx, fy, Math.max(1f, strokePx * 0.09f), grainPaint);
            }
            return;
        }
        if (path == null) return;
        PathMeasure pm = new PathMeasure(path, false);
        float len = pm.getLength();
        if (len <= 0f) return;
        float step = Math.max(4f, strokePx * 0.35f);
        float[] pos = new float[2];
        Random rnd = new Random(Float.floatToIntBits(len)); // stable seed → no flicker across redraws
        for (float d = 0; d <= len; d += step) {
            if (pm.getPosTan(d, pos, null)) {
                for (int i = 0; i < 2; i++) {
                    float ang = (float) (rnd.nextDouble() * Math.PI * 2);
                    float rad = (strokePx / 2f) * rnd.nextFloat();
                    float fx = pos[0] + (float) Math.cos(ang) * rad;
                    float fy = pos[1] + (float) Math.sin(ang) * rad;
                    canvas.drawCircle(fx, fy, Math.max(1f, strokePx * 0.08f), grainPaint);
                }
            }
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int lighten(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0f, hsv[1] * 0.35f);
        hsv[2] = 1f;
        return Color.HSVToColor(255, hsv);
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
        drawStrokes(canvas, strokes, targetW, targetH, viewW, viewH, offX, offY, fitScale, density, null);
    }

    /**
     * Same as {@link #drawStrokes} above, plus a full-resolution pixelated
     * copy of the baked photo (sized exactly {@code targetW x targetH}, i.e.
     * already in this canvas's own pixel space) so BRUSH_BLUR strokes reveal
     * blurred content in the final send-out image, matching what the Blur
     * brush showed live in the editor.
     */
    public static void drawStrokes(Canvas canvas, List<Stroke> strokes,
                                   float targetW, float targetH,
                                   float viewW, float viewH,
                                   float offX, float offY, float fitScale,
                                   float density, Bitmap blurBakeBitmap) {
        if (strokes == null || strokes.isEmpty()) return;
        if (fitScale <= 0f) fitScale = 1f;
        // blurBakeBitmap is already sized targetW x targetH (same pixel
        // space as `canvas`), so an identity matrix aligns it directly —
        // unlike the live-preview path, no fitCenter remap is needed here.
        Matrix blurBakeMatrix = new Matrix();

        // Save layer for CLEAR mode (and for BlurMaskFilter on Ink/Neon) to work correctly
        int sc = canvas.saveLayer(0, 0, targetW, targetH, null);

        for (Stroke s : strokes) {
            if (s.points.isEmpty()) continue;
            float strokePx = (s.widthDp * density) / fitScale;

            if (s.shapeType != SHAPE_FREEHAND) {
                if (s.points.size() < 2) continue;
                PointF a = s.points.get(0), b = s.points.get(1);
                float ax = ((a.x * viewW) - offX) / fitScale;
                float ay = ((a.y * viewH) - offY) / fitScale;
                float bx = ((b.x * viewW) - offX) / fitScale;
                float by = ((b.y * viewH) - offY) / fitScale;
                renderShapePrimitive(canvas, s.shapeType, s.color, strokePx, ax, ay, bx, by);
                continue;
            }

            Path path = null;
            PointF singlePoint = null;

            if (s.points.size() == 1) {
                PointF pt = s.points.get(0);
                float px = ((pt.x * viewW) - offX) / fitScale;
                float py = ((pt.y * viewH) - offY) / fitScale;
                singlePoint = new PointF(px, py);
            } else {
                path = new Path();
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
            }

            renderStroke(canvas, s, path, singlePoint, strokePx, blurBakeBitmap, blurBakeMatrix);
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
