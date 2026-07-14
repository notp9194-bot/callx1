package com.callx.app.conversation.controllers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * CropOverlayView — WhatsApp-grade interactive image crop view.
 *
 * Architecture (single view, two-layer rendering):
 *  Layer 1 — image: Bitmap drawn with {@link #imageMatrix} (pan + scale).
 *  Layer 2 — overlay: dim outside crop box, rule-of-thirds grid inside,
 *             L-shaped corner handles, thin edge handles.
 *
 * Touch dispatch:
 *  • Touch starts near a corner/edge handle → resize the crop box.
 *  • Touch starts inside or outside crop box (not near handle) → pan / pinch-zoom the image.
 *
 * Constraints enforced after EVERY transform:
 *  • Image always fills the crop box — no black gaps ever visible inside it.
 *  • Crop box never leaves the view padding area.
 *  • Aspect ratio is locked when {@link #setAspectRatio(float)} > 0.
 *
 * Output:
 *  {@link #getCroppedBitmap()} inverts imageMatrix, maps crop box to bitmap
 *  pixel coords, and returns a cropped Bitmap ready for JPEG encoding.
 */
public class CropOverlayView extends View {

    // ─── Handle enumeration ───────────────────────────────────────────────
    private static final int NONE        = -1;
    private static final int TL          =  0;  // top-left corner
    private static final int TR          =  1;  // top-right corner
    private static final int BL          =  2;  // bottom-left corner
    private static final int BR          =  3;  // bottom-right corner
    private static final int EDGE_T      =  4;
    private static final int EDGE_B      =  5;
    private static final int EDGE_L      =  6;
    private static final int EDGE_R      =  7;

    // ─── Touch modes ──────────────────────────────────────────────────────
    private static final int MODE_NONE   = 0;
    private static final int MODE_CROP   = 1;   // dragging a crop handle
    private static final int MODE_PAN    = 2;   // panning image (1 finger)
    private static final int MODE_PINCH  = 3;   // pinch-zooming image (2 fingers)

    // ─── Paints ───────────────────────────────────────────────────────────
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint    = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint gridPaint   = new Paint();
    private final Paint handlePaint = new Paint();
    private final Paint edgePaint   = new Paint();

    // ─── Image state ─────────────────────────────────────────────────────
    private Bitmap      bitmap;
    private final Matrix imageMatrix    = new Matrix();
    private final Matrix invImageMatrix = new Matrix();

    // ─── Crop box (in view coords) ────────────────────────────────────────
    private final RectF cropRect = new RectF();
    private float aspectRatio = 0f;         // 0 = free
    private float minCropPx;                // min width/height in px
    private float cropRotationDeg = 0f;     // straight-only (unused for now)

    // ─── Layout padding (crop box can't go beyond) ────────────────────────
    private float padL, padT, padR, padB;

    // ─── Handle dimensions ────────────────────────────────────────────────
    private float handleTouchRadius;    // px — touch target for handles
    private float handleArmLen;         // px — length of the L-arm drawn
    private float handleThickness;      // px — stroke width of L

    // ─── Grid visibility ─────────────────────────────────────────────────
    private boolean showGrid = false;

    // ─── Touch state ─────────────────────────────────────────────────────
    private int     touchMode   = MODE_NONE;
    private int     activeHandle = NONE;
    private float   panLastX, panLastY;
    // Pinch
    private float   pinchMidX, pinchMidY;
    private float   pinchLastSpan;
    private float   pinchLastMidX, pinchLastMidY;
    // For crop handle dragging
    private float   cropStartX, cropStartY;
    private final RectF cropRectSaved = new RectF();

    private boolean initialized = false;

    // ─── Callbacks ────────────────────────────────────────────────────────
    public interface OnCropChangedListener {
        void onCropChanged();
    }
    private OnCropChangedListener cropChangedListener;

    // ═════════════════════════════════════════════════════════════════════
    // Construction
    // ═════════════════════════════════════════════════════════════════════

    public CropOverlayView(Context ctx) { super(ctx); init(); }
    public CropOverlayView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public CropOverlayView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        float dp = getResources().getDisplayMetrics().density;

        dimPaint.setColor(0xA8000000);
        dimPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f * dp);
        borderPaint.setAntiAlias(true);

        gridPaint.setColor(0x80FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(0.8f * dp);
        gridPaint.setAntiAlias(true);

        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setStrokeWidth(3f * dp);
        handlePaint.setStrokeCap(Paint.Cap.SQUARE);
        handlePaint.setAntiAlias(true);

        edgePaint.setColor(0xCCFFFFFF);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(2f * dp);
        edgePaint.setAntiAlias(true);

        handleTouchRadius = 36 * dp;
        handleArmLen      = 22 * dp;
        handleThickness   = 3  * dp;
        minCropPx         = 80 * dp;

        padL = padT = padR = padB = 16 * dp;

        setBackgroundColor(Color.BLACK);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        initialized = false;
        if (getWidth() > 0 && getHeight() > 0) initLayout();
        invalidate();
    }

    public void setAspectRatio(float ratio) {
        this.aspectRatio = ratio;
        if (initialized) {
            snapAspectRatio();
            constrainImage();
            invalidate();
        }
    }

    public void rotate90() {
        // Rotate bitmap 90° CW and reinit
        if (bitmap == null) return;
        Matrix m = new Matrix();
        m.postRotate(90);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        bitmap.recycle();
        bitmap = rotated;
        initialized = false;
        initLayout();
        invalidate();
    }

    public void setOnCropChangedListener(OnCropChangedListener l) {
        cropChangedListener = l;
    }

    /**
     * Returns a cropped Bitmap (original resolution) corresponding to the
     * current crop box and image transform. Never returns null if bitmap set.
     */
    public Bitmap getCroppedBitmap() {
        if (bitmap == null) return null;

        // Invert the image matrix to map view coords → bitmap coords
        imageMatrix.invert(invImageMatrix);

        // Map crop rect corners through the inverse matrix
        float[] pts = {
            cropRect.left,  cropRect.top,
            cropRect.right, cropRect.bottom
        };
        invImageMatrix.mapPoints(pts);

        int bx = (int) Math.max(0, pts[0]);
        int by = (int) Math.max(0, pts[1]);
        int bw = (int) Math.min(bitmap.getWidth()  - bx, pts[2] - pts[0]);
        int bh = (int) Math.min(bitmap.getHeight() - by, pts[3] - pts[1]);

        bw = Math.max(1, bw);
        bh = Math.max(1, bh);

        return Bitmap.createBitmap(bitmap, bx, by, bw, bh);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Layout init
    // ═════════════════════════════════════════════════════════════════════

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (bitmap != null && !initialized) initLayout();
    }

    private void initLayout() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;

        float vw = getWidth()  - padL - padR;
        float vh = getHeight() - padT - padB;
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();

        // Scale to fit-center inside the padded view area
        float scale = Math.min(vw / bw, vh / bh);

        // Center
        float tx = padL + (vw - bw * scale) / 2f;
        float ty = padT + (vh - bh * scale) / 2f;

        imageMatrix.reset();
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(tx, ty);

        // Initial crop box = 90 % of the image area in view coords
        RectF imgBounds = imageBoundsInView();
        float cx = imgBounds.centerX(), cy = imgBounds.centerY();
        float hw = imgBounds.width()  * 0.45f;
        float hh = imgBounds.height() * 0.45f;
        cropRect.set(cx - hw, cy - hh, cx + hw, cy + hh);

        if (aspectRatio > 0) snapAspectRatio();
        constrainImage();

        initialized = true;
        invalidate();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Drawing
    // ═════════════════════════════════════════════════════════════════════

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;

        // 1. Image
        canvas.save();
        canvas.concat(imageMatrix);
        canvas.drawBitmap(bitmap, 0, 0, bitmapPaint);
        canvas.restore();

        // 2. Dim region outside crop
        drawDim(canvas);

        // 3. Crop border
        canvas.drawRect(cropRect, borderPaint);

        // 4. Rule-of-thirds grid (always on while dragging, optional otherwise)
        if (showGrid) drawGrid(canvas);

        // 5. Corner handles
        drawCornerHandle(canvas, cropRect.left,  cropRect.top,     1f,  1f);
        drawCornerHandle(canvas, cropRect.right, cropRect.top,    -1f,  1f);
        drawCornerHandle(canvas, cropRect.left,  cropRect.bottom,  1f, -1f);
        drawCornerHandle(canvas, cropRect.right, cropRect.bottom, -1f, -1f);

        // 6. Edge mid handles (short lines)
        float mx = cropRect.centerX(), my = cropRect.centerY();
        float halfEdge = handleArmLen * 0.6f;
        // Top
        canvas.drawLine(mx - halfEdge, cropRect.top, mx + halfEdge, cropRect.top, handlePaint);
        // Bottom
        canvas.drawLine(mx - halfEdge, cropRect.bottom, mx + halfEdge, cropRect.bottom, handlePaint);
        // Left
        canvas.drawLine(cropRect.left, my - halfEdge, cropRect.left, my + halfEdge, handlePaint);
        // Right
        canvas.drawLine(cropRect.right, my - halfEdge, cropRect.right, my + halfEdge, handlePaint);
    }

    private void drawDim(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        // Top band
        canvas.drawRect(0, 0, w, cropRect.top, dimPaint);
        // Bottom band
        canvas.drawRect(0, cropRect.bottom, w, h, dimPaint);
        // Left strip (between top/bottom bands)
        canvas.drawRect(0, cropRect.top, cropRect.left, cropRect.bottom, dimPaint);
        // Right strip
        canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, dimPaint);
    }

    private void drawGrid(Canvas canvas) {
        float cw = cropRect.width(), ch = cropRect.height();
        // Thirds horizontal
        canvas.drawLine(cropRect.left, cropRect.top + ch / 3f, cropRect.right, cropRect.top + ch / 3f, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + ch * 2f / 3f, cropRect.right, cropRect.top + ch * 2f / 3f, gridPaint);
        // Thirds vertical
        canvas.drawLine(cropRect.left + cw / 3f, cropRect.top, cropRect.left + cw / 3f, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left + cw * 2f / 3f, cropRect.top, cropRect.left + cw * 2f / 3f, cropRect.bottom, gridPaint);
    }

    /**
     * Draws an L-shaped corner handle.
     * (cx, cy) = corner point, (dx, dy) = direction inward (+1 or -1).
     */
    private void drawCornerHandle(Canvas canvas, float cx, float cy, float dx, float dy) {
        float arm = handleArmLen;
        // Horizontal arm
        canvas.drawLine(cx, cy, cx + arm * dx, cy, handlePaint);
        // Vertical arm
        canvas.drawLine(cx, cy, cx, cy + arm * dy, handlePaint);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Touch handling
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return false;

        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX(), y = event.getY();
                int handle = hitTestHandle(x, y);
                if (handle != NONE) {
                    touchMode    = MODE_CROP;
                    activeHandle = handle;
                    cropStartX   = x;
                    cropStartY   = y;
                    cropRectSaved.set(cropRect);
                    showGrid = true;
                } else {
                    touchMode  = MODE_PAN;
                    panLastX   = x;
                    panLastY   = y;
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                if (touchMode == MODE_PAN && pointerCount == 2) {
                    touchMode     = MODE_PINCH;
                    pinchLastSpan = fingerSpan(event);
                    pinchLastMidX = (event.getX(0) + event.getX(1)) / 2f;
                    pinchLastMidY = (event.getY(0) + event.getY(1)) / 2f;
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (touchMode == MODE_CROP) {
                    handleCropDrag(event.getX(), event.getY());
                    invalidate();
                } else if (touchMode == MODE_PINCH && pointerCount >= 2) {
                    float span  = fingerSpan(event);
                    float midX  = (event.getX(0) + event.getX(1)) / 2f;
                    float midY  = (event.getY(0) + event.getY(1)) / 2f;
                    float scale = span / Math.max(1f, pinchLastSpan);
                    float dPanX = midX - pinchLastMidX;
                    float dPanY = midY - pinchLastMidY;
                    applyImageTransform(scale, midX, midY, dPanX, dPanY);
                    pinchLastSpan = span;
                    pinchLastMidX = midX;
                    pinchLastMidY = midY;
                    invalidate();
                } else if (touchMode == MODE_PAN) {
                    float dx = event.getX() - panLastX;
                    float dy = event.getY() - panLastY;
                    imageMatrix.postTranslate(dx, dy);
                    panLastX = event.getX();
                    panLastY = event.getY();
                    constrainImage();
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                if (touchMode == MODE_PINCH) {
                    // Stay in pinch until all pointers up; switch to PAN with remaining pointer
                    touchMode = MODE_PAN;
                    int remaining = event.getActionIndex() == 0 ? 1 : 0;
                    panLastX = event.getX(remaining);
                    panLastY = event.getY(remaining);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                touchMode    = MODE_NONE;
                activeHandle = NONE;
                showGrid     = false;
                constrainImage();
                invalidate();
                return true;
            }
        }
        return false;
    }

    // ─── Crop box handle dragging ─────────────────────────────────────────

    private void handleCropDrag(float x, float y) {
        float dx = x - cropStartX;
        float dy = y - cropStartY;
        cropRect.set(cropRectSaved);

        RectF bounds = viewBounds();

        switch (activeHandle) {
            case TL:
                cropRect.left = clamp(cropRectSaved.left + dx, bounds.left, cropRect.right - minCropPx);
                cropRect.top  = clamp(cropRectSaved.top  + dy, bounds.top,  cropRect.bottom - minCropPx);
                break;
            case TR:
                cropRect.right = clamp(cropRectSaved.right + dx, cropRect.left + minCropPx, bounds.right);
                cropRect.top   = clamp(cropRectSaved.top   + dy, bounds.top, cropRect.bottom - minCropPx);
                break;
            case BL:
                cropRect.left   = clamp(cropRectSaved.left   + dx, bounds.left, cropRect.right - minCropPx);
                cropRect.bottom = clamp(cropRectSaved.bottom + dy, cropRect.top + minCropPx, bounds.bottom);
                break;
            case BR:
                cropRect.right  = clamp(cropRectSaved.right  + dx, cropRect.left + minCropPx, bounds.right);
                cropRect.bottom = clamp(cropRectSaved.bottom + dy, cropRect.top + minCropPx, bounds.bottom);
                break;
            case EDGE_T:
                cropRect.top = clamp(cropRectSaved.top + dy, bounds.top, cropRect.bottom - minCropPx);
                break;
            case EDGE_B:
                cropRect.bottom = clamp(cropRectSaved.bottom + dy, cropRect.top + minCropPx, bounds.bottom);
                break;
            case EDGE_L:
                cropRect.left = clamp(cropRectSaved.left + dx, bounds.left, cropRect.right - minCropPx);
                break;
            case EDGE_R:
                cropRect.right = clamp(cropRectSaved.right + dx, cropRect.left + minCropPx, bounds.right);
                break;
        }

        if (aspectRatio > 0) snapAspectRatioFromHandle(activeHandle);
        constrainImage();

        if (cropChangedListener != null) cropChangedListener.onCropChanged();
    }

    // ─── Image pan/zoom transform ─────────────────────────────────────────

    private void applyImageTransform(float scale, float pivotX, float pivotY, float dPanX, float dPanY) {
        imageMatrix.postScale(scale, scale, pivotX, pivotY);
        imageMatrix.postTranslate(dPanX, dPanY);
        // Clamp scale: image must still fill crop box after scaling
        constrainImage();
    }

    // ─── Constraint: image always fills the crop box ──────────────────────

    private void constrainImage() {
        if (bitmap == null) return;

        RectF imgBounds = imageBoundsInView();
        float bw = imgBounds.width(), bh = imgBounds.height();
        float cw = cropRect.width(),  ch = cropRect.height();

        // 1. If image is smaller than crop box, scale up so it fills it.
        float scaleX = cw / bw, scaleY = ch / bh;
        if (scaleX > 1f || scaleY > 1f) {
            float needed = Math.max(scaleX, scaleY);
            float px = cropRect.centerX(), py = cropRect.centerY();
            imageMatrix.postScale(needed, needed, px, py);
            imgBounds = imageBoundsInView();
        }

        // 2. Translate so image covers crop box on all sides (no gaps)
        float dx = 0, dy = 0;
        if (imgBounds.left > cropRect.left)   dx = cropRect.left   - imgBounds.left;
        if (imgBounds.right < cropRect.right)  dx = cropRect.right  - imgBounds.right;
        if (imgBounds.top  > cropRect.top)    dy = cropRect.top    - imgBounds.top;
        if (imgBounds.bottom < cropRect.bottom) dy = cropRect.bottom - imgBounds.bottom;
        if (dx != 0 || dy != 0) imageMatrix.postTranslate(dx, dy);
    }

    // ─── Aspect ratio snap ────────────────────────────────────────────────

    private void snapAspectRatio() {
        if (aspectRatio <= 0) return;
        float cx = cropRect.centerX(), cy = cropRect.centerY();
        float cw = cropRect.width(),   ch = cropRect.height();
        float newH = cw / aspectRatio;
        if (cy - newH / 2 < padT || cy + newH / 2 > getHeight() - padB) {
            // Try adjusting width from height
            float newW = ch * aspectRatio;
            cropRect.set(cx - newW / 2, cy - ch / 2, cx + newW / 2, cy + ch / 2);
        } else {
            cropRect.set(cx - cw / 2, cy - newH / 2, cx + cw / 2, cy + newH / 2);
        }
        clampCropToViewBounds();
    }

    private void snapAspectRatioFromHandle(int handle) {
        if (aspectRatio <= 0) return;
        // After moving a corner/edge, snap height to maintain ratio (keep width as reference)
        float cw = cropRect.width();
        float newH = cw / aspectRatio;
        // Adjust bottom or top depending on which handle was moved
        if (handle == TL || handle == EDGE_L || handle == EDGE_T) {
            cropRect.top = cropRect.bottom - newH;
        } else {
            cropRect.bottom = cropRect.top + newH;
        }
        clampCropToViewBounds();
    }

    // ─── Hit testing ─────────────────────────────────────────────────────

    private int hitTestHandle(float x, float y) {
        float r = handleTouchRadius;
        // Corners first (priority over edges)
        if (dist(x, y, cropRect.left,  cropRect.top)    < r) return TL;
        if (dist(x, y, cropRect.right, cropRect.top)    < r) return TR;
        if (dist(x, y, cropRect.left,  cropRect.bottom) < r) return BL;
        if (dist(x, y, cropRect.right, cropRect.bottom) < r) return BR;
        // Edge midpoints
        float mx = cropRect.centerX(), my = cropRect.centerY();
        if (dist(x, y, mx, cropRect.top)    < r) return EDGE_T;
        if (dist(x, y, mx, cropRect.bottom) < r) return EDGE_B;
        if (dist(x, y, cropRect.left,  my)  < r) return EDGE_L;
        if (dist(x, y, cropRect.right, my)  < r) return EDGE_R;
        return NONE;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    /** Returns the bitmap's bounding rect mapped through imageMatrix. */
    private RectF imageBoundsInView() {
        RectF r = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        imageMatrix.mapRect(r);
        return r;
    }

    private RectF viewBounds() {
        return new RectF(padL, padT, getWidth() - padR, getHeight() - padB);
    }

    private void clampCropToViewBounds() {
        RectF b = viewBounds();
        cropRect.left   = Math.max(cropRect.left,   b.left);
        cropRect.top    = Math.max(cropRect.top,    b.top);
        cropRect.right  = Math.min(cropRect.right,  b.right);
        cropRect.bottom = Math.min(cropRect.bottom, b.bottom);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float fingerSpan(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
