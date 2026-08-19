package com.callx.app.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws up to 3 overlapping collaborator avatars, Instagram-style: each
 * front avatar is drawn slightly higher and further right than the one
 * behind it, and — unlike a plain overlapping-circle stack — actually
 * punches a circular "notch" out of everything drawn so far wherever the
 * next avatar sits, so the back avatar shows a clean crescent-moon cutout
 * exactly where the front avatar overlaps it (with a border-colored ring
 * filling the gap between the two), instead of just being covered by a
 * flat opaque circle on top.
 *
 * Usage: call setAvatarCount(n) once you know how many avatars you'll
 * show (max 3), then feed bitmaps in via setAvatarBitmap(index, bitmap)
 * as they arrive from Glide (e.g. via asBitmap().into(CustomTarget)).
 */
public class CollabAvatarStackView extends View {

    private static final int MAX_AVATARS = 3;

    private final Bitmap[] avatarBitmaps = new Bitmap[MAX_AVATARS];
    private int avatarCount = 0;

    private final int avatarSizePx;
    private final int overlapStepPx;   // horizontal distance between consecutive avatar centers
    private final int verticalStepPx;  // each subsequent (front) avatar is drawn this much higher
    private final int borderWidthPx;

    private Bitmap compositeBitmap;
    private Canvas compositeCanvas;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF dstRect = new RectF();

    public CollabAvatarStackView(Context context) {
        this(context, null);
    }

    public CollabAvatarStackView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CollabAvatarStackView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = context.getResources().getDisplayMetrics().density;
        avatarSizePx = Math.round(32 * density);
        // ~58% overlap between consecutive avatars — matches the tighter
        // overlap Instagram uses (our old plain stack used 50%/12dp).
        overlapStepPx = Math.round(19 * density);
        verticalStepPx = Math.round(4 * density);
        // Border ring removed (was a black outline) — cutout notch now sizes
        // exactly to the avatar circle itself so there's no black ring.
        borderWidthPx = 0;

        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderWidthPx);
        borderPaint.setColor(Color.TRANSPARENT);

        placeholderPaint.setStyle(Paint.Style.FILL);
        placeholderPaint.setColor(0xFF3A3A3A);

        // Xfermode compositing (CLEAR) requires an unaccelerated layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** Call once you know how many avatars (1-3) you're about to show. */
    public void setAvatarCount(int count) {
        int clamped = Math.max(0, Math.min(MAX_AVATARS, count));
        if (clamped != avatarCount) {
            avatarCount = clamped;
            requestLayout();
        }
        invalidate();
    }

    /** Feed a decoded bitmap in for the given stack position (0 = back/owner). */
    public void setAvatarBitmap(int index, Bitmap bitmap) {
        if (index < 0 || index >= MAX_AVATARS) return;
        avatarBitmaps[index] = bitmap;
        invalidate();
    }

    public void clearAvatars() {
        for (int i = 0; i < MAX_AVATARS; i++) avatarBitmaps[i] = null;
        avatarCount = 0;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int n = Math.max(avatarCount, 1);
        int width = avatarSizePx + (n - 1) * overlapStepPx;
        int height = avatarSizePx + (n - 1) * verticalStepPx;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (avatarCount <= 0) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (compositeBitmap == null || compositeBitmap.getWidth() != w || compositeBitmap.getHeight() != h) {
            if (compositeBitmap != null) compositeBitmap.recycle();
            compositeBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            compositeCanvas = new Canvas(compositeBitmap);
        } else {
            compositeBitmap.eraseColor(Color.TRANSPARENT);
        }

        int radius = avatarSizePx / 2;
        int holeRadius = radius + borderWidthPx; // avatar + its own border ring
        int baseTop = h - avatarSizePx; // first (back) avatar sits at the bottom of the view

        for (int i = 0; i < avatarCount; i++) {
            float cx = radius + i * overlapStepPx;
            float cy = baseTop + radius - i * verticalStepPx;

            if (i > 0) {
                // Punch the crescent notch: clear a circle (avatar + border
                // sized) out of everything drawn so far at this avatar's spot.
                compositeCanvas.drawCircle(cx, cy, holeRadius, clearPaint);
            }

            Bitmap avatarBmp = avatarBitmaps[i];
            clipPath.reset();
            clipPath.addCircle(cx, cy, radius, Path.Direction.CW);
            compositeCanvas.save();
            compositeCanvas.clipPath(clipPath);
            if (avatarBmp != null && !avatarBmp.isRecycled()) {
                dstRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
                compositeCanvas.drawBitmap(avatarBmp, null, dstRect, bitmapPaint);
            } else {
                compositeCanvas.drawCircle(cx, cy, radius, placeholderPaint);
            }
            compositeCanvas.restore();
        }

        canvas.drawBitmap(compositeBitmap, 0, 0, null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (compositeBitmap != null) {
            compositeBitmap.recycle();
            compositeBitmap = null;
            compositeCanvas = null;
        }
    }
}
