package com.callx.app.chips.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * HashtagChipCanvasView — Canvas replacement for item_hashtag_chip.xml
 * (a TextView with a bg_speed_chip.xml GradientDrawable background).
 *
 * WHY THIS EXISTS
 * ─────────────────
 * The old row was a plain TextView whose android:background pointed at an
 * XML <shape> (solid fill + stroke + rounded corners). Every inflate creates
 * a fresh GradientDrawable instance, and TextView's own draw path runs
 * background.draw() (shape rasterization) *then* its own text layout draw on
 * top — two separate drawable-backed passes per chip, for what is otherwise
 * static, five-glyph-average content ("#trending"). Used in a horizontally
 * scrolling row (trending hashtags, both in feature-chat's compose bar and
 * feature-reels' search screen), so this repeats per chip on every fling.
 *
 * This view draws the pill and the text together in one onDraw(): a single
 * canvas.drawRoundRect() for the fill+stroke, then one canvas.drawText() —
 * no Drawable objects, no second draw pass. wrap_content width is computed
 * once in onMeasure() from Paint#measureText(), matching the original
 * TextView's paddingStart/End(16dp) + text width behavior.
 */
public class HashtagChipCanvasView extends View {

    private static final float HEIGHT_DP = 36f;
    private static final float H_PADDING_DP = 16f;
    private static final float CORNER_RADIUS_DP = 6f;
    private static final float STROKE_WIDTH_DP = 1f;
    private static final float TEXT_SIZE_SP = 14f;

    private static final int FILL_COLOR = 0x44000000;
    private static final int STROKE_COLOR = 0x66FFFFFF;
    private static final int TEXT_COLOR = 0xFF4CAF50; // matches @color/brand_primary intent

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();

    private final float heightPx, hPaddingPx, cornerRadiusPx, strokeWidthPx;

    private String text = "";
    private float measuredTextWidth = 0f;

    public HashtagChipCanvasView(Context context) { this(context, null); }

    public HashtagChipCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        heightPx = HEIGHT_DP * density;
        hPaddingPx = H_PADDING_DP * density;
        cornerRadiusPx = CORNER_RADIUS_DP * density;
        strokeWidthPx = STROKE_WIDTH_DP * density;

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(FILL_COLOR);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidthPx);
        strokePaint.setColor(STROKE_COLOR);

        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP, context.getResources().getDisplayMetrics()));
        textPaint.setFakeBoldText(true); // approximates sans-serif-medium without a Typeface lookup

        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
    }

    /** Sets the chip's label (e.g. "#trending"). No-op if unchanged. */
    public void setText(String newText) {
        String safe = newText != null ? newText : "";
        if (safe.equals(text)) return;
        text = safe;
        measuredTextWidth = textPaint.measureText(text);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = (int) Math.ceil(measuredTextWidth + hPaddingPx * 2);
        int width = resolveSize(desiredWidth, widthMeasureSpec);
        int height = resolveSize((int) Math.ceil(heightPx), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float half = strokeWidthPx / 2f;
        pillRect.set(half, half, w - half, h - half);
        canvas.drawRoundRect(pillRect, cornerRadiusPx, cornerRadiusPx, fillPaint);
        canvas.drawRoundRect(pillRect, cornerRadiusPx, cornerRadiusPx, strokePaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = h / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, w / 2f, textY, textPaint);
    }
}
