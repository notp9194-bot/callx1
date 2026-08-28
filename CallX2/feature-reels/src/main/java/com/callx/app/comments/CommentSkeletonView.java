package com.callx.app.comments;

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
 * Instagram-style comment-list loading skeleton, drawn entirely in
 * {@link #onDraw}, as ONE View.
 *
 * WHY a custom View instead of the previous ShimmerFrameLayout +
 * 6x <include layout="item_comment_skeleton"/> markup:
 *  - That markup was ~30+ real View objects (6 rows × avatar + 3 line
 *    Views + row container), each independently measured/laid out/drawn
 *    on every pass, plus a whole extra ShimmerFrameLayout in the tree.
 *  - ShimmerFrameLayout itself works by rendering its child subtree into
 *    an offscreen Bitmap and re-compositing a moving mask over it every
 *    frame — an extra full-size bitmap allocation + composite pass purely
 *    for the shimmer sweep.
 *  - Here, all rows are flat rectangles/ovals drawn straight onto the
 *    View's own Canvas with ONE reused Paint carrying a LinearGradient
 *    shader; the "shimmer" is just translating that shader's Matrix each
 *    frame (cheap: no new Shader, no new Paint, no offscreen bitmap, no
 *    child view tree at all). Net effect: same visual sweep, a fraction
 *    of the object count and one draw pass instead of two.
 *
 * Theme-aware: base/highlight colors come from @color/skeleton_base and
 * @color/skeleton_highlight (values/ vs values-night/ in :core), read
 * once in the constructor — no per-frame resource lookups.
 *
 * Lifecycle: animation only runs between {@link #start()} and
 * {@link #stop()} — the fragment calls start() when showing this view and
 * stop() the moment real comments (or the empty state) resolve, AND from
 * onPause()/onDestroyView(), so it never burns frames while off-screen or
 * backgrounded. setLayerType(HARDWARE) is applied only while the shader
 * animation is actually running (GPU-composited shader translate) and
 * dropped back to NONE on stop() so no hardware layer sits around idle
 * holding GPU memory once the skeleton is gone.
 */
public class CommentSkeletonView extends View {

    private static final int ROW_COUNT = 6;

    // Row geometry — mirrors item_comment_skeleton.xml's dp values exactly
    // so the real ReelCommentsAdapter rows line up under this with no jump.
    private static final float ROW_PADDING_START = 12f;
    private static final float ROW_PADDING_TOP = 10f;
    private static final float ROW_PADDING_BOTTOM = 8f;
    private static final float AVATAR_SIZE = 36f;
    private static final float AVATAR_MARGIN_END = 10f;
    private static final float AVATAR_MARGIN_TOP = 2f;
    private static final float LINE_HEIGHT = 10f;
    private static final float LINE_RADIUS = 4f;
    private static final float NAME_LINE_WIDTH = 90f;
    private static final float TEXT_LINE1_WIDTH = 220f;
    private static final float TEXT_LINE1_MARGIN_TOP = 8f;
    private static final float TEXT_LINE2_WIDTH = 140f;
    private static final float TEXT_LINE2_MARGIN_TOP = 6f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();       // reused scratch rect — no per-draw allocation
    private final Matrix shaderMatrix = new Matrix();

    private float density;
    private float rowHeightPx;
    private LinearGradient gradient;
    private float sweepWidthPx;
    private ValueAnimator animator;
    private float translateFraction = 0f; // 0..1, drives shaderMatrix each frame

    public CommentSkeletonView(Context context) {
        super(context);
        init(context);
    }

    public CommentSkeletonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        rowHeightPx = (AVATAR_MARGIN_TOP + AVATAR_SIZE + ROW_PADDING_TOP + ROW_PADDING_BOTTOM) * density;

        int base = androidx.core.content.ContextCompat.getColor(
                context, com.callx.app.core.R.color.skeleton_base);
        int highlight = androidx.core.content.ContextCompat.getColor(
                context, com.callx.app.core.R.color.skeleton_highlight);
        // 3-stop gradient (base → highlight → base) translated left-to-right
        // each frame — the classic shimmer sweep, built once and reused.
        paint.setColor(base); // fallback fill before the first layout pass builds the shader
        this.baseColor = base;
        this.highlightColor = highlight;

        // Runs entirely off dp math — no per-frame allocation once built in onSizeChanged.
    }

    private int baseColor;
    private int highlightColor;

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0) return;
        // Sweep band ~40% of the view width, matching Shimmer library's default feel.
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
        // translateFraction 0 → band starts fully left of view; 1 → fully past the right edge.
        float dx = -sweepWidthPx + translateFraction * (width + 2 * sweepWidthPx);
        shaderMatrix.setTranslate(dx, 0);
        gradient.setLocalMatrix(shaderMatrix);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (gradient == null) return; // not sized yet

        float paddingStartPx = ROW_PADDING_START * density;
        float avatarSizePx = AVATAR_SIZE * density;
        float lineHeightPx = LINE_HEIGHT * density;
        float lineRadiusPx = LINE_RADIUS * density;
        float textStartPx = paddingStartPx + avatarSizePx + AVATAR_MARGIN_END * density;

        int visibleRows = Math.min(ROW_COUNT, (int) Math.ceil(getHeight() / rowHeightPx) + 1);
        for (int i = 0; i < visibleRows; i++) {
            float rowTop = i * rowHeightPx;

            // Avatar circle
            float avatarTop = rowTop + (ROW_PADDING_TOP + AVATAR_MARGIN_TOP) * density;
            rect.set(paddingStartPx, avatarTop, paddingStartPx + avatarSizePx, avatarTop + avatarSizePx);
            canvas.drawOval(rect, paint);

            // Name line
            float nameTop = rowTop + (ROW_PADDING_TOP + AVATAR_MARGIN_TOP) * density;
            rect.set(textStartPx, nameTop, textStartPx + NAME_LINE_WIDTH * density, nameTop + lineHeightPx);
            canvas.drawRoundRect(rect, lineRadiusPx, lineRadiusPx, paint);

            // Comment text line 1
            float line1Top = nameTop + lineHeightPx + TEXT_LINE1_MARGIN_TOP * density;
            rect.set(textStartPx, line1Top, textStartPx + TEXT_LINE1_WIDTH * density, line1Top + lineHeightPx);
            canvas.drawRoundRect(rect, lineRadiusPx, lineRadiusPx, paint);

            // Comment text line 2
            float line2Top = line1Top + lineHeightPx + TEXT_LINE2_MARGIN_TOP * density;
            rect.set(textStartPx, line2Top, textStartPx + TEXT_LINE2_WIDTH * density, line2Top + lineHeightPx);
            canvas.drawRoundRect(rect, lineRadiusPx, lineRadiusPx, paint);
        }
    }

    /** Starts the shimmer sweep. Safe to call repeatedly (no-op if already running). */
    public void start() {
        if (animator != null && animator.isRunning()) return;
        // Hardware layer ONLY while animating — a shader-matrix translate is
        // cheap to composite on the GPU as a layer, but there's no reason to
        // hold that layer in GPU memory once the sweep stops.
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
        stop(); // belt-and-suspenders: never leave the ValueAnimator running past the View's life
        super.onDetachedFromWindow();
    }
}
