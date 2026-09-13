package com.callx.app.notifications;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Instagram-style skeleton loader for ReelNotificationsActivity's initial
 * load — shown in place of a plain ProgressBar while the first Firebase
 * page is fetching.
 *
 * Same idiom as {@link com.callx.app.comments.CommentSkeletonView}: rows are
 * flat rectangles/ovals drawn straight onto this View's own Canvas with ONE
 * reused Paint carrying a LinearGradient shader, animated by translating the
 * shader's Matrix each frame — no child view tree, no offscreen bitmap, no
 * ShimmerFrameLayout. See that class's doc for the full rationale.
 *
 * Row geometry mirrors ReelNotifAdapter#buildItemRow()'s dp values (dp(12)
 * row padding, dp(64) avatar wrap, dp(40) avatar, dp(10) avatar margin-end)
 * so the real rows line up under this with no jump when the skeleton swaps
 * out for content.
 *
 * Lifecycle: {@link #start()} when shown, {@link #stop()} the moment real
 * data (or the empty state) resolves — also called from
 * onDetachedFromWindow() as a backstop so it never burns frames off-screen.
 */
public class ReelNotifSkeletonView extends View {

    private static final int ROW_COUNT = 8;

    private static final float ROW_PADDING = 12f;
    private static final float AVATAR_WRAP_SIZE = 64f;
    private static final float AVATAR_SIZE = 40f;
    private static final float AVATAR_MARGIN_END = 10f;
    private static final float ROW_HEIGHT = 70f; // 12 + 46 (avatarWrap) + 12

    private static final float LINE_RADIUS = 4f;
    private static final float TITLE_HEIGHT = 12f;
    private static final float TITLE_WIDTH = 130f;
    private static final float BODY_HEIGHT = 10f;
    private static final float BODY_WIDTH = 200f;
    private static final float BODY_MARGIN_TOP = 8f;
    private static final float TIME_HEIGHT = 8f;
    private static final float TIME_WIDTH = 40f;
    private static final float TIME_MARGIN_TOP = 7f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix shaderMatrix = new Matrix();

    private float density;
    private float rowHeightPx;
    private LinearGradient gradient;
    private float sweepWidthPx;
    private ValueAnimator animator;
    private float translateFraction = 0f;
    private int baseColor;
    private int highlightColor;

    public ReelNotifSkeletonView(Context context) {
        super(context);
        init(context);
    }

    public ReelNotifSkeletonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        rowHeightPx = ROW_HEIGHT * density;
        baseColor = androidx.core.content.ContextCompat.getColor(
                context, com.callx.app.core.R.color.skeleton_base);
        highlightColor = androidx.core.content.ContextCompat.getColor(
                context, com.callx.app.core.R.color.skeleton_highlight);
        paint.setColor(baseColor); // fallback fill before the first layout pass builds the shader
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0) return;
        sweepWidthPx = w * 0.4f;
        gradient = new LinearGradient(
                -sweepWidthPx, 0, 0, 0,
                new int[]{baseColor, highlightColor, baseColor},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        paint.setShader(gradient);
        applyShaderTranslate(w);
    }

    private void applyShaderTranslate(int width) {
        if (gradient == null) return;
        float dx = -sweepWidthPx + translateFraction * (width + 2 * sweepWidthPx);
        shaderMatrix.setTranslate(dx, 0);
        gradient.setLocalMatrix(shaderMatrix);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (gradient == null) return;

        float rowPaddingPx = ROW_PADDING * density;
        float avatarWrapPx = AVATAR_WRAP_SIZE * density;
        float avatarSizePx = AVATAR_SIZE * density;
        float textStartPx = rowPaddingPx + avatarWrapPx + AVATAR_MARGIN_END * density;
        float lineRadiusPx = LINE_RADIUS * density;

        int visibleRows = Math.min(ROW_COUNT, (int) Math.ceil(getHeight() / rowHeightPx) + 1);
        for (int i = 0; i < visibleRows; i++) {
            float rowTop = i * rowHeightPx;
            float avatarTop = rowTop + (rowHeightPx - avatarSizePx) / 2f;

            // Avatar circle
            rect.set(rowPaddingPx, avatarTop, rowPaddingPx + avatarSizePx, avatarTop + avatarSizePx);
            canvas.drawOval(rect, paint);

            // Title line
            float titleTop = rowTop + (rowHeightPx - avatarSizePx) / 2f;
            rect.set(textStartPx, titleTop, textStartPx + TITLE_WIDTH * density, titleTop + TITLE_HEIGHT * density);
            canvas.drawRoundRect(rect, lineRadiusPx, lineRadiusPx, paint);

            // Body line
            float bodyTop = titleTop + TITLE_HEIGHT * density + BODY_MARGIN_TOP * density;
            rect.set(textStartPx, bodyTop, textStartPx + BODY_WIDTH * density, bodyTop + BODY_HEIGHT * density);
            canvas.drawRoundRect(rect, lineRadiusPx, lineRadiusPx, paint);

            // Time line
            float timeTop = bodyTop + BODY_HEIGHT * density + TIME_MARGIN_TOP * density;
            rect.set(textStartPx, timeTop, textStartPx + TIME_WIDTH * density, timeTop + TIME_HEIGHT * density);
            canvas.drawRoundRect(rect, lineRadiusPx, lineRadiusPx, paint);
        }
    }

    /** Starts the shimmer sweep. Safe to call repeatedly (no-op if already running). */
    public void start() {
        if (animator != null && animator.isRunning()) return;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            translateFraction = (float) a.getAnimatedValue();
            applyShaderTranslate(getWidth());
            invalidate();
        });
        animator.start();
    }

    /** Stops the sweep and drops the hardware layer. Safe to call repeatedly. */
    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        setLayerType(LAYER_TYPE_NONE, null);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
