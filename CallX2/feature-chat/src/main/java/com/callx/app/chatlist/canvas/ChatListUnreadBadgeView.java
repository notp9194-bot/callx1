package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

/**
 * ChatListUnreadBadgeView — v82 canvas optimisation.
 *
 * WHY THIS EXISTS
 * ───────────────
 * The old tv_unread_badge was a TextView with a @drawable/bg_unread_badge
 * background (a GradientDrawable pill shape). Every bind that toggled the badge
 * on/off forced:
 *   • a visibility-change layout pass on the TextView
 *   • a GradientDrawable fill + optional stroke draw
 *   • a separate TextView text draw
 *
 * This view collapses the pill shape AND the count text into a single plain
 * View whose onDraw() does:
 *   1. canvas.drawRoundRect()  — the pill background (brand_primary fill)
 *   2. canvas.drawText()       — the count ("5", "99+") centred inside
 *
 * No GradientDrawable, no TextView, no background drawable inflation.
 *
 * PERF: setBadgeCount() is a no-op when the count is unchanged, so payload
 * rebinds that don't touch the unread count do zero work here.
 */
public class ChatListUnreadBadgeView extends View {

    private static final float TEXT_SIZE_SP = 11f;
    private static final float MIN_SIZE_DP  = 20f;
    private static final float RADIUS_DP    = 10f;
    private static final float PAD_H_DP     = 6f;
    private static final float PAD_V_DP     = 2f;

    private final Paint  bgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF   pillRect  = new RectF();

    private final float density;
    private final float minSizePx;
    private final float radiusPx;
    private final float padHPx;
    private final float padVPx;

    private String label = "";
    private long  lastCount = -1;
    private boolean visible = false;

    public ChatListUnreadBadgeView(Context ctx) {
        this(ctx, null);
    }

    public ChatListUnreadBadgeView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density   = ctx.getResources().getDisplayMetrics().density;
        float sp  = ctx.getResources().getDisplayMetrics().scaledDensity;

        minSizePx = MIN_SIZE_DP * density;
        radiusPx  = RADIUS_DP  * density;
        padHPx    = PAD_H_DP   * density;
        padVPx    = PAD_V_DP   * density;

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(0xFF4CAF50); // brand_primary

        textPaint.setTextSize(TEXT_SIZE_SP * sp);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setColor(0xFFFFFFFF); // white
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * Sets the unread count. Pass 0 to hide. No-op if count is unchanged.
     */
    public void setBadgeCount(long count) {
        if (count == lastCount) return;
        lastCount = count;
        if (count <= 0) {
            visible = false;
            label   = "";
        } else {
            visible = true;
            label   = count > 99 ? "99+" : String.valueOf(count);
        }
        requestLayout();
        invalidate();
    }

    /** Returns whether the badge is currently showing (count > 0). */
    public boolean hasCount() { return visible; }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!visible) {
            setMeasuredDimension(0, 0);
            return;
        }
        float textW = textPaint.measureText(label);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textH = fm.descent - fm.ascent;

        float desiredW = Math.max(minSizePx, textW + padHPx * 2);
        float desiredH = Math.max(minSizePx, textH + padVPx * 2);

        setMeasuredDimension(
            resolveSize((int) Math.ceil(desiredW), widthMeasureSpec),
            resolveSize((int) Math.ceil(desiredH), heightMeasureSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible || label.isEmpty()) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        pillRect.set(0, 0, w, h);
        canvas.drawRoundRect(pillRect, radiusPx, radiusPx, bgPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textBaseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, w / 2f, textBaseline, textPaint);
    }
}
