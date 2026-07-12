package com.callx.app.media.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

/**
 * MediaThumbCanvasView — Canvas replacement for the ImageView cell in
 * item_media_thumb.xml (GroupInfoActivity's "Media, Links & Docs" grid).
 *
 * WHY THIS EXISTS
 * ─────────────────
 * The old row was a FrameLayout > ImageView pair: Glide.into(ImageView) sets
 * an internal Drawable (a BitmapDrawable, or a placeholder Drawable while
 * loading), and every bind swaps that Drawable out — each swap is a
 * setImageDrawable() call that invalidates the ImageView and triggers a full
 * Drawable draw pass (matrix setup, since ImageView's own scaleType=centerCrop
 * math runs inside the Drawable/Matrix machinery on every draw call, not
 * once). For a 3-column grid that's up to 9 ImageViews alive at once, each
 * carrying its own Drawable + Matrix state.
 *
 * This view instead asks Glide for the decoded Bitmap directly (asBitmap(),
 * no .centerCrop() transform needed — the crop math is done once here in
 * onSizeChanged()/onBitmapReady(), not per-frame) and draws it straight with
 * canvas.drawBitmap(bitmap, matrix, paint). The center-crop Matrix is
 * computed once per (bitmap, view-size) pair and cached — onDraw() itself
 * does zero math, just a single drawBitmap call.
 *
 * A grid of 9 canvas cells with cached Matrices is materially cheaper to
 * scroll/lay out than 9 ImageViews each carrying independent Drawable state,
 * and it drops the redundant FrameLayout wrapper (this View draws its own
 * placeholder background, so no extra layer is needed).
 */
public class MediaThumbCanvasView extends View {

    private static final int PLACEHOLDER_BG = 0xFFE5E7EB; // matches @color/surface_input intent

    public interface OnThumbClickListener { void onThumbClick(); }

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholderPaint = new Paint();
    private final Matrix cropMatrix = new Matrix();
    private final RectF drawBounds = new RectF();

    private Bitmap bitmap;
    private boolean matrixDirty = true;

    private String pendingUrl;
    private CustomTarget<Bitmap> pendingTarget;

    private OnThumbClickListener clickListener;

    public MediaThumbCanvasView(Context context) { super(context); init(); }
    public MediaThumbCanvasView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public MediaThumbCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        placeholderPaint.setColor(PLACEHOLDER_BG);
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> {
            if (clickListener != null) clickListener.onThumbClick();
        });
    }

    public void setOnThumbClickListener(OnThumbClickListener l) {
        this.clickListener = l;
    }

    /**
     * Loads (or reuses) the thumbnail for {@code url}. Cheap no-op if the
     * same URL is already loaded/loading, so re-binds during scroll
     * (partial adapter refresh, config change, etc.) don't re-decode.
     */
    public void setImageUrl(@Nullable String url) {
        if (url != null && url.equals(pendingUrl) && bitmap != null) return;
        pendingUrl = url;

        if (pendingTarget != null) {
            Glide.with(getContext()).clear(pendingTarget);
            pendingTarget = null;
        }
        bitmap = null;
        matrixDirty = true;
        invalidate();

        if (url == null || url.isEmpty()) return;

        pendingTarget = new CustomTarget<Bitmap>() {
            @Override public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                                                    @Nullable Transition<? super Bitmap> transition) {
                bitmap = resource;
                matrixDirty = true;
                invalidate();
            }

            @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                bitmap = null;
                invalidate();
            }
        };
        Glide.with(getContext())
                .asBitmap()
                .load(url)
                .into(pendingTarget);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        matrixDirty = true;
    }

    /**
     * Recomputes the center-crop Matrix once per (bitmap, size) change — not
     * per draw. Content area respects the View's own padding (this view
     * replaces a FrameLayout>ImageView pair where the padding lived on the
     * FrameLayout, so it's honored here manually).
     */
    private void rebuildCropMatrixIfNeeded() {
        if (!matrixDirty || bitmap == null) return;
        int left = getPaddingLeft(), top = getPaddingTop();
        int vw = getWidth() - left - getPaddingRight();
        int vh = getHeight() - top - getPaddingBottom();
        if (vw <= 0 || vh <= 0) return;

        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        if (bw <= 0 || bh <= 0) return;

        float scale = Math.max(vw / (float) bw, vh / (float) bh);
        float scaledW = bw * scale;
        float scaledH = bh * scale;
        float dx = left + (vw - scaledW) / 2f;
        float dy = top + (vh - scaledH) / 2f;

        cropMatrix.reset();
        cropMatrix.setScale(scale, scale);
        cropMatrix.postTranslate(dx, dy);
        matrixDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int left = getPaddingLeft(), top = getPaddingTop();
        int right = getWidth() - getPaddingRight();
        int bottom = getHeight() - getPaddingBottom();
        if (right <= left || bottom <= top) return;

        if (bitmap == null || bitmap.isRecycled()) {
            drawBounds.set(left, top, right, bottom);
            canvas.drawRect(drawBounds, placeholderPaint);
            return;
        }

        rebuildCropMatrixIfNeeded();
        int save = canvas.save();
        canvas.clipRect(left, top, right, bottom);
        canvas.drawBitmap(bitmap, cropMatrix, bitmapPaint);
        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pendingTarget != null) {
            Glide.with(getContext()).clear(pendingTarget);
        }
    }
}
