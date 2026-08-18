package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * DateSeparatorCanvasView — Canvas-rendered replacement for
 * item_date_separator.xml (FrameLayout > TextView chip with bg_date_chip
 * background). Last remaining View-inflated row in the message list
 * (TYPE_DATE_SEPARATOR) — every other bubble/system row already renders
 * through MessageBubbleCanvasView. Low-frequency (once per day change),
 * so this is a cleanup/consistency pass rather than a scroll-jank fix,
 * but it removes the last per-item inflate() + findViewById() pair from
 * onCreateViewHolder/onBindViewHolder and lets this row recycle as a
 * plain View like every other holder in the list.
 *
 * Visuals match bg_date_chip.xml exactly: solid #3A3A4A fill, 10dp corner
 * radius, white 11sp bold centered text, 12dp/3dp chip padding, 10dp/10dp
 * outer top/bottom padding — all baked in as constants below since the
 * chip's look never varies at runtime (only the label text changes).
 */
public class DateSeparatorCanvasView extends View {

    private static final float OUTER_PAD_V_DP = 10f;
    private static final float CHIP_PAD_H_DP = 12f;
    private static final float CHIP_PAD_V_DP = 3f;
    private static final float CHIP_RADIUS_DP = 10f;
    private static final float TEXT_SP = 11f;
    private static final int CHIP_COLOR = 0xFF3A3A4A;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF chipRect = new RectF();
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();

    private int outerPadVPx, chipPadHPx, chipPadVPx, chipRadiusPx;
    private String label = "";
    private float textWidth = 0f;

    public DateSeparatorCanvasView(Context context) { super(context); init(); }
    public DateSeparatorCanvasView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public DateSeparatorCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        outerPadVPx = (int) (OUTER_PAD_V_DP * density);
        chipPadHPx = (int) (CHIP_PAD_H_DP * density);
        chipPadVPx = (int) (CHIP_PAD_V_DP * density);
        chipRadiusPx = (int) (CHIP_RADIUS_DP * density);

        chipPaint.setStyle(Paint.Style.FILL);
        chipPaint.setColor(CHIP_COLOR);

        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP,
                getResources().getDisplayMetrics()));
        textPaint.getFontMetrics(fontMetrics);

        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setSaveEnabled(false);
    }

    /** @param text e.g. "Today", "Yesterday", or "MMM d" — same strings bindMessage() used to feed tv_date_label. */
    public void setLabel(@Nullable String text) {
        String next = text != null ? text : "";
        if (next.equals(label)) return;
        label = next;
        textWidth = textPaint.measureText(label);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int chipHeight = (int) Math.ceil(fontMetrics.descent - fontMetrics.ascent) + chipPadVPx * 2;
        int height = outerPadVPx + chipHeight + outerPadVPx;
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (label.isEmpty()) return;

        int viewWidth = getWidth();
        float chipWidth = textWidth + chipPadHPx * 2f;
        float chipHeight = (float) Math.ceil(fontMetrics.descent - fontMetrics.ascent) + chipPadVPx * 2f;
        float left = (viewWidth - chipWidth) / 2f;
        float top = outerPadVPx;

        chipRect.set(left, top, left + chipWidth, top + chipHeight);
        canvas.drawRoundRect(chipRect, chipRadiusPx, chipRadiusPx, chipPaint);

        float baseline = top + chipPadVPx - fontMetrics.ascent;
        canvas.drawText(label, left + chipPadHPx, baseline, textPaint);
    }
}
