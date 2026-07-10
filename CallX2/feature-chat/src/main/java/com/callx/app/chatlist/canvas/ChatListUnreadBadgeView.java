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
 * ChatListUnreadBadgeView — v83 Telegram-level perf hardening.
 *
 * v82 CHANGES: collapsed GradientDrawable pill + TextView into one canvas view.
 *
 * v83 PERF FIXES (zero-allocation hot path in onDraw):
 *  1. FontMetrics cached once in constructor — onDraw() and onMeasure() never
 *     call getFontMetrics() (which allocates a new FontMetrics object every call).
 *  2. textBaseline pre-computed in onSizeChanged() — no division inside onDraw().
 *  3. textCx pre-computed in onSizeChanged() — no division inside onDraw().
 *  4. pillRect.set() is still needed on size change but NOT per-draw (pillRect
 *     is now set in onSizeChanged, not onDraw).
 *
 * Result: onDraw() path is two canvas calls:
 *   canvas.drawRoundRect(pillRect, ...) + canvas.drawText(label, ...)
 */
public class ChatListUnreadBadgeView extends View {

    private static final float TEXT_SIZE_SP = 11f;
    private static final float MIN_SIZE_DP  = 20f;
    private static final float RADIUS_DP    = 10f;
    private static final float PAD_H_DP     = 6f;
    private static final float PAD_V_DP     = 2f;

    private final Paint     bgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF     pillRect  = new RectF();

    private final float density;
    private final float minSizePx;
    private final float radiusPx;
    private final float padHPx;
    private final float padVPx;

    // v83: FontMetrics cached — never re-allocated in draw/measure
    private final Paint.FontMetrics fmCache;
    private final float textHeightHalf;  // (descent - ascent) / 2, pre-computed

    private String label     = "";
    private long   lastCount = -1;
    private boolean visible  = false;

    // v83: pre-computed draw coords, updated in onSizeChanged
    private float textBaseline = 0f;
    private float textCx       = 0f;

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
        bgPaint.setColor(0xFF4CAF50);

        textPaint.setTextSize(TEXT_SIZE_SP * sp);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // v83: cache FontMetrics once — never allocate in draw/measure
        fmCache = textPaint.getFontMetrics();
        textHeightHalf = (fmCache.descent - fmCache.ascent) / 2f;
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
        // v83: use cached textHeightHalf — no getFontMetrics() call
        float textH = textHeightHalf * 2f;

        float desiredW = Math.max(minSizePx, textW + padHPx * 2);
        float desiredH = Math.max(minSizePx, textH + padVPx * 2);

        setMeasuredDimension(
            resolveSize((int) Math.ceil(desiredW), widthMeasureSpec),
            resolveSize((int) Math.ceil(desiredH), heightMeasureSpec)
        );
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // v83: pre-compute all draw coords here, not in onDraw
        pillRect.set(0, 0, w, h);
        textCx       = w / 2f;
        // baseline = centre - (ascent+descent)/2 using cached fm
        textBaseline = h / 2f - (fmCache.ascent + fmCache.descent) / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // v83 hot path: zero allocations — all coords are pre-cached fields
        if (!visible || label.isEmpty()) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawRoundRect(pillRect, radiusPx, radiusPx, bgPaint);
        canvas.drawText(label, textCx, textBaseline, textPaint);
    }
}
