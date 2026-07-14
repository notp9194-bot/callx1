package com.callx.app.conversation.controllers;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * CropOverlayView — interactive drag-handle crop region view.
 *
 * Features:
 *  - Draws the source bitmap scaled to fit
 *  - Semi-transparent overlay outside the crop region
 *  - Rule-of-thirds grid inside the crop region
 *  - Eight drag handles (corners + edge midpoints)
 *  - Aspect-ratio lock (setAspectRatio; 0 = free)
 *  - Returns crop region as normalized RectF [0..1, 0..1] via getCropFraction()
 */
public class CropOverlayView extends View {

    // ── Handle IDs ────────────────────────────────────────────────────────
    private static final int NONE    = -1;
    private static final int CORNER_TL = 0, CORNER_TR = 1, CORNER_BL = 2, CORNER_BR = 3;
    private static final int EDGE_T = 4, EDGE_B = 5, EDGE_L = 6, EDGE_R = 7;
    private static final int HANDLE_BODY = 8; // drag entire rect

    // ── Paint ─────────────────────────────────────────────────────────────
    private final Paint dimPaint   = new Paint();
    private final Paint cropPaint  = new Paint();
    private final Paint gridPaint  = new Paint();
    private final Paint handlePaint= new Paint();
    private final Paint bitmapPaint= new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    // ── State ─────────────────────────────────────────────────────────────
    private Bitmap bitmap;
    private final Matrix bitmapMatrix = new Matrix();
    private final RectF  bitmapDst    = new RectF();   // where bitmap is drawn (fit-center)

    private final RectF  cropRect     = new RectF();   // in view coords
    private float        aspectRatio  = 0f;            // 0 = free
    private float        minSize      = 80f;

    private int   activeHandle   = NONE;
    private float lastX, lastY;
    private boolean   initialized = false;

    // ── Constructor ───────────────────────────────────────────────────────

    public CropOverlayView(Context ctx) { super(ctx); init(); }
    public CropOverlayView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public CropOverlayView(Context ctx, AttributeSet a, int def) { super(ctx, a, def); init(); }

    private void init() {
        dimPaint.setColor(0xA0000000);
        dimPaint.setStyle(Paint.Style.FILL);

        cropPaint.setColor(Color.WHITE);
        cropPaint.setStyle(Paint.Style.STROKE);
        cropPaint.setStrokeWidth(2f);
        cropPaint.setAntiAlias(true);

        gridPaint.setColor(0x80FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setAntiAlias(true);

        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setAntiAlias(true);

        setBackgroundColor(Color.BLACK);
        minSize = getResources().getDisplayMetrics().density * 60;
    }

    // ── Public API ───────────────────────────────────────────────────────

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        initialized = false;
        requestLayout();
        invalidate();
    }

    public void setAspectRatio(float ratio) {
        this.aspectRatio = ratio;
        if (initialized) snapAspectRatio(true);
        invalidate();
    }

    /**
     * Returns the crop region as normalized fractions [0..1] relative to the bitmap.
     * Returns null if no bitmap or not yet initialized.
     */
    public RectF getCropFraction() {
        if (bitmap == null || !initialized || bitmapDst.isEmpty()) return null;
        // Map from view coords to bitmap coords
        float scaleX = bitmap.getWidth()  / bitmapDst.width();
        float scaleY = bitmap.getHeight() / bitmapDst.height();
        float l = Math.max(0f, Math.min(1f, (cropRect.left   - bitmapDst.left) * scaleX / bitmap.getWidth()));
        float t = Math.max(0f, Math.min(1f, (cropRect.top    - bitmapDst.top)  * scaleY / bitmap.getHeight()));
        float r = Math.max(0f, Math.min(1f, (cropRect.right  - bitmapDst.left) * scaleX / bitmap.getWidth()));
        float b = Math.max(0f, Math.min(1f, (cropRect.bottom - bitmapDst.top)  * scaleY / bitmap.getHeight()));
        return new RectF(l, t, r, b);
    }

    // ── Layout + bitmap fit ───────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w > 0 && h > 0) {
            computeBitmapDst(w, h);
            if (!initialized && bitmap != null) initCropRect();
        }
    }

    private void computeBitmapDst(int vw, int vh) {
        if (bitmap == null) return;
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        float scale = Math.min(vw / bw, vh / bh);
        float dw = bw * scale;
        float dh = bh * scale;
        float dx = (vw - dw) / 2f;
        float dy = (vh - dh) / 2f;
        bitmapDst.set(dx, dy, dx + dw, dy + dh);
        bitmapMatrix.reset();
        bitmapMatrix.postScale(scale, scale);
        bitmapMatrix.postTranslate(dx, dy);
    }

    private void initCropRect() {
        float pad = minSize / 2;
        cropRect.set(bitmapDst.left + pad, bitmapDst.top + pad,
                     bitmapDst.right - pad, bitmapDst.bottom - pad);
        if (aspectRatio > 0) snapAspectRatio(false);
        initialized = true;
    }

    // ── Draw ─────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // Draw bitmap
        if (bitmap != null) canvas.drawBitmap(bitmap, bitmapMatrix, bitmapPaint);

        if (!initialized) return;

        // Dim outside crop
        canvas.drawRect(0, 0, w, cropRect.top,    dimPaint);
        canvas.drawRect(0, cropRect.bottom, w, h,  dimPaint);
        canvas.drawRect(0, cropRect.top, cropRect.left, cropRect.bottom, dimPaint);
        canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, dimPaint);

        // Crop border
        canvas.drawRect(cropRect, cropPaint);

        // Rule of thirds grid
        float gx1 = cropRect.left + cropRect.width() / 3f;
        float gx2 = cropRect.left + cropRect.width() * 2f / 3f;
        float gy1 = cropRect.top  + cropRect.height()/ 3f;
        float gy2 = cropRect.top  + cropRect.height()* 2f / 3f;
        canvas.drawLine(gx1, cropRect.top, gx1, cropRect.bottom, gridPaint);
        canvas.drawLine(gx2, cropRect.top, gx2, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left, gy1, cropRect.right, gy1, gridPaint);
        canvas.drawLine(cropRect.left, gy2, cropRect.right, gy2, gridPaint);

        // Drag handles — 12dp radius circles at corners + edge midpoints
        float r = getResources().getDisplayMetrics().density * 8f;
        float mx = cropRect.centerX(), my = cropRect.centerY();
        drawHandle(canvas, cropRect.left,  cropRect.top,    r); // TL
        drawHandle(canvas, cropRect.right, cropRect.top,    r); // TR
        drawHandle(canvas, cropRect.left,  cropRect.bottom, r); // BL
        drawHandle(canvas, cropRect.right, cropRect.bottom, r); // BR
        drawHandle(canvas, mx,             cropRect.top,    r); // T
        drawHandle(canvas, mx,             cropRect.bottom, r); // B
        drawHandle(canvas, cropRect.left,  my,             r); // L
        drawHandle(canvas, cropRect.right, my,             r); // R
    }

    private void drawHandle(Canvas canvas, float cx, float cy, float r) {
        // White filled circle with dark shadow
        handlePaint.setColor(0x55000000);
        canvas.drawCircle(cx + 1.5f, cy + 1.5f, r, handlePaint);
        handlePaint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, r, handlePaint);
    }

    // ── Touch ─────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!initialized) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activeHandle = findHandle(x, y);
                lastX = x; lastY = y;
                return activeHandle != NONE;
            case MotionEvent.ACTION_MOVE:
                if (activeHandle == NONE) return false;
                moveDelta(x - lastX, y - lastY);
                lastX = x; lastY = y;
                if (aspectRatio > 0) snapAspectRatio(false);
                clampToBitmap();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeHandle = NONE;
                return true;
        }
        return false;
    }

    private static final float HANDLE_RADIUS = 48f; // hit-test radius in px

    private int findHandle(float x, float y) {
        float hr = HANDLE_RADIUS;
        float mx = cropRect.centerX(), my = cropRect.centerY();
        if (dist(x, y, cropRect.left,  cropRect.top)    < hr) return CORNER_TL;
        if (dist(x, y, cropRect.right, cropRect.top)    < hr) return CORNER_TR;
        if (dist(x, y, cropRect.left,  cropRect.bottom) < hr) return CORNER_BL;
        if (dist(x, y, cropRect.right, cropRect.bottom) < hr) return CORNER_BR;
        if (dist(x, y, mx,             cropRect.top)    < hr) return EDGE_T;
        if (dist(x, y, mx,             cropRect.bottom) < hr) return EDGE_B;
        if (dist(x, y, cropRect.left,  my)              < hr) return EDGE_L;
        if (dist(x, y, cropRect.right, my)              < hr) return EDGE_R;
        if (cropRect.contains(x, y))                          return HANDLE_BODY;
        return NONE;
    }

    private void moveDelta(float dx, float dy) {
        switch (activeHandle) {
            case CORNER_TL:
                cropRect.left  = Math.min(cropRect.left  + dx, cropRect.right  - minSize);
                cropRect.top   = Math.min(cropRect.top   + dy, cropRect.bottom - minSize);
                break;
            case CORNER_TR:
                cropRect.right = Math.max(cropRect.right + dx, cropRect.left + minSize);
                cropRect.top   = Math.min(cropRect.top   + dy, cropRect.bottom - minSize);
                break;
            case CORNER_BL:
                cropRect.left   = Math.min(cropRect.left + dx, cropRect.right  - minSize);
                cropRect.bottom = Math.max(cropRect.bottom+ dy, cropRect.top  + minSize);
                break;
            case CORNER_BR:
                cropRect.right  = Math.max(cropRect.right + dx, cropRect.left + minSize);
                cropRect.bottom = Math.max(cropRect.bottom+ dy, cropRect.top  + minSize);
                break;
            case EDGE_T:
                cropRect.top   = Math.min(cropRect.top   + dy, cropRect.bottom - minSize);
                break;
            case EDGE_B:
                cropRect.bottom= Math.max(cropRect.bottom+ dy, cropRect.top  + minSize);
                break;
            case EDGE_L:
                cropRect.left  = Math.min(cropRect.left  + dx, cropRect.right  - minSize);
                break;
            case EDGE_R:
                cropRect.right = Math.max(cropRect.right + dx, cropRect.left + minSize);
                break;
            case HANDLE_BODY:
                cropRect.offset(dx, dy);
                break;
        }
    }

    private void snapAspectRatio(boolean animate) {
        if (aspectRatio <= 0) return;
        float currentW = cropRect.width();
        float currentH = cropRect.height();
        float targetH = currentW / aspectRatio;
        float cx = cropRect.centerX();
        float cy = cropRect.centerY();
        float nTop  = cy - targetH / 2;
        float nBot  = cy + targetH / 2;
        cropRect.top    = nTop;
        cropRect.bottom = nBot;
        clampToBitmap();
    }

    private void clampToBitmap() {
        if (bitmapDst.isEmpty()) return;
        if (cropRect.left   < bitmapDst.left)   { cropRect.right  += bitmapDst.left   - cropRect.left;   cropRect.left   = bitmapDst.left; }
        if (cropRect.top    < bitmapDst.top)     { cropRect.bottom += bitmapDst.top    - cropRect.top;    cropRect.top    = bitmapDst.top; }
        if (cropRect.right  > bitmapDst.right)   { cropRect.left   -= cropRect.right   - bitmapDst.right;  cropRect.right  = bitmapDst.right; }
        if (cropRect.bottom > bitmapDst.bottom)  { cropRect.top    -= cropRect.bottom  - bitmapDst.bottom; cropRect.bottom = bitmapDst.bottom; }
        // Re-clamp after body-drag
        cropRect.left   = Math.max(cropRect.left,   bitmapDst.left);
        cropRect.top    = Math.max(cropRect.top,    bitmapDst.top);
        cropRect.right  = Math.min(cropRect.right,  bitmapDst.right);
        cropRect.bottom = Math.min(cropRect.bottom, bitmapDst.bottom);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
