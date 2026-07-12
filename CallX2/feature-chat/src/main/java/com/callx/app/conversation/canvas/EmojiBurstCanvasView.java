package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * EmojiBurstCanvasView — Canvas-rendered replacement for the old
 * TextView-based emoji burst overlay.
 *
 * WHY THIS EXISTS (same rationale as MessageBubbleCanvasView, applied to
 * the emoji burst): a TextView is a lot more machinery than this feature
 * needs — Editable/Layout construction, ellipsize handling, autosize,
 * compound-drawable checks, its own accessibility node — and critically,
 * TextView.setText() on a wrap_content view calls requestLayout(), which
 * queues a fresh measure+layout pass on the Choreographer. That pass is
 * cheap in isolation, but if it lands on the same frame as a RecyclerView
 * fling/scroll pass, it's still main-thread work competing for the same
 * 16ms budget — exactly the kind of thing that caused the original
 * feature to be pulled for jank.
 *
 * This view sidesteps all of it:
 *   • FIXED SIZE — always match_parent, resolved once in onMeasure() from
 *     the parent's spec. Nothing here is ever content-dependent, so
 *     changing the emoji NEVER calls requestLayout() — only invalidate().
 *   • NO LAYOUT MACHINERY — onDraw() does one canvas.drawText() call with
 *     pre-centered coordinates computed from Paint.FontMetrics. No
 *     StaticLayout, no Editable, no ellipsize, no measure-text-per-frame.
 *   • HARDWARE-LAYER FRIENDLY — the scale/fade animation is still driven
 *     by the base View's own scaleX/scaleY/alpha (set by the controller
 *     via ViewPropertyAnimator) while LAYER_TYPE_HARDWARE is on. Once the
 *     layer is built, every animation frame is pure GPU compositing of a
 *     cached texture — onDraw() is NOT re-invoked per frame, only once
 *     when the emoji text itself changes.
 *   • GONE-safe — while gone/invisible the view does no measuring, no
 *     drawing, and holds no layer, so it costs nothing at rest.
 *
 * All burst-triggering logic (recency guard, self-message guard, cooldown,
 * animation timing) stays in ChatEmojiBurstController — this class is a
 * pure, cheap renderer.
 */
public class EmojiBurstCanvasView extends View {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String emoji;

    // Cached vertical centering offset, derived from FontMetrics once
    // per text-size (never per frame, never per draw).
    private float baselineOffset;

    public EmojiBurstCanvasView(Context context) {
        super(context);
        init();
    }

    public EmojiBurstCanvasView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EmojiBurstCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float sizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 128f, getResources().getDisplayMetrics());
        textPaint.setTextSize(sizePx);
        textPaint.setTextAlign(Paint.Align.CENTER);
        recomputeBaselineOffset();
        // Not clickable/focusable — purely decorative, never intercepts touches.
        setClickable(false);
        setFocusable(false);
        // Defensive: guarantees onDraw() always runs. A plain View already
        // defaults to this, but stating it explicitly removes any doubt
        // since this view has no background to imply "drawing" on its own.
        setWillNotDraw(false);
    }

    private void recomputeBaselineOffset() {
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        // Standard formula to vertically center a single line of text
        // around a given y-coordinate.
        baselineOffset = (fm.descent + fm.ascent) / 2f;
    }

    /** Sets the emoji to render. Only invalidates — never requests layout,
     *  since this view's size never depends on its content. */
    public void setEmoji(String value) {
        this.emoji = value;
        invalidate();
    }

    /** Clears the rendered emoji. Cheap no-op if already empty. */
    public void clearEmoji() {
        if (this.emoji == null) return;
        this.emoji = null;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Fixed, content-independent size — resolved once from whatever
        // the parent handed us (match_parent in activity_chat.xml).
        setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (emoji == null || emoji.isEmpty()) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f - baselineOffset;
        canvas.drawText(emoji, cx, cy, textPaint);
    }
}
