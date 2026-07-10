package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

/**
 * ChatListLastMessageView — v85 ultra-perf hardening.
 *
 * v23 CHANGES: collapsed tick ImageView + last-message TextView into one canvas view.
 *
 * v85 PERF FIXES (zero-allocation hot path in onDraw — same treatment as NameTimeView):
 *
 *  1. FontMetrics CACHED in constructor (fmCache field).
 *     v23 called textPaint.getFontMetrics() on EVERY onDraw() AND onMeasure() call —
 *     that allocates a new Paint.FontMetrics object every scroll frame.
 *     Fix: store the metrics once, never call getFontMetrics() again.
 *
 *  2. baseline PRE-COMPUTED in onSizeChanged().
 *     v23 computed `h/2f - (fm.ascent + fm.descent)/2f` inside onDraw() on every
 *     frame. Now it's a plain float field read — zero arithmetic in the hot path.
 *
 *  3. tickReservedWidth CACHED in a field.
 *     Previously called tickReservedWidth() (a small method with conditional
 *     arithmetic) inside onDraw every frame. Now invalidated and recomputed only
 *     when tickState actually changes (in setTicks()).
 *
 *  4. availableTextWidth PRE-COMPUTED and stored.
 *     (w - tickReserved) is now recomputed only in onSizeChanged() or when
 *     tickState changes — not on every draw.
 *
 *  Result: onDraw() hot path is:
 *    [optional] drawTicks — 2-4 drawLine() calls, zero allocations
 *    maybeRebuildEllipsis — only runs when text/width actually changed
 *    canvas.drawText(ellipsized, ...) — one call, zero allocations
 */
public class ChatListLastMessageView extends View {

    public static final int TICK_NONE      = 0;
    public static final int TICK_SENT      = 1;
    public static final int TICK_DELIVERED = 2;
    public static final int TICK_READ      = 3;

    private static final float TEXT_SIZE_SP = 14f;
    private static final float TICK_SIZE_DP = 12f;
    private static final float TICK_GAP_DP  = 4f;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint     tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float tickSizePx;
    private final float tickGapPx;

    // v85: FontMetrics cached once — never re-allocated in draw/measure
    private final Paint.FontMetrics fmCache;
    private final int measuredHeight; // cached desired height for onMeasure

    private String rawText = "";
    private boolean italic = false;
    private CharSequence ellipsized = "";
    private int lastEllipsisWidth = -1;
    private boolean textDirty = true;

    private int tickState = TICK_NONE;
    private int tickColor = 0xFF94A3B8;

    // v85: pre-computed in onSizeChanged / setTicks — read in onDraw
    private float baseline         = 0f;
    private float cachedTickReserved = 0f;
    private int   availableTextWidth = 0;

    public ChatListLastMessageView(Context ctx) {
        this(ctx, null);
    }

    public ChatListLastMessageView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float density = ctx.getResources().getDisplayMetrics().density;
        float sp      = ctx.getResources().getDisplayMetrics().scaledDensity;
        tickSizePx = TICK_SIZE_DP * density;
        tickGapPx  = TICK_GAP_DP  * density;

        textPaint.setTextSize(TEXT_SIZE_SP * sp);
        textPaint.setColor(0xFF64748B);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(1.4f * density);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setColor(tickColor);

        // v85: cache FontMetrics once — zero allocations in draw/measure from here on
        fmCache = textPaint.getFontMetrics();
        measuredHeight = (int) Math.ceil(fmCache.descent - fmCache.ascent);
    }

    /**
     * Sets the last-message text/color/italic style.
     * No-op if nothing changed from what's already drawn.
     */
    public void setMessageText(String text, int color, boolean italic) {
        String safe = text == null ? "" : text;
        boolean changed = !safe.equals(rawText)
                || textPaint.getColor() != color
                || this.italic != italic;
        if (!changed) return;

        rawText    = safe;
        this.italic = italic;
        textPaint.setColor(color);
        textPaint.setTypeface(Typeface.defaultFromStyle(italic ? Typeface.ITALIC : Typeface.NORMAL));
        // After typeface change, fm might shift slightly — but we keep the same
        // cached fm for baseline (the shift is sub-pixel and imperceptible).
        textDirty = true;
        invalidate();
    }

    /**
     * Sets the read-receipt tick state and color.
     * No-op if unchanged.
     */
    public void setTicks(int state, int color) {
        boolean unchanged = state == tickState && (state == TICK_NONE || color == tickColor);
        if (unchanged) return;

        tickState = state;
        tickColor = color;
        tickPaint.setColor(color);
        // Recompute cached tick-reserved width and available text width
        cachedTickReserved = computeTickReservedWidth();
        availableTextWidth = Math.max(0, getWidth() - (int) cachedTickReserved);
        textDirty = true;
        invalidate();
    }

    private float computeTickReservedWidth() {
        if (tickState == TICK_NONE) return 0f;
        float span = (tickState == TICK_SENT) ? tickSizePx : tickSizePx * 1.35f;
        return span + tickGapPx;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        // v85: use cached height — no getFontMetrics() call
        setMeasuredDimension(width, resolveSize(measuredHeight, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // v85: pre-compute everything that depends on view size — onDraw reads fields
        baseline           = h / 2f - (fmCache.ascent + fmCache.descent) / 2f;
        cachedTickReserved = computeTickReservedWidth();
        availableTextWidth = Math.max(0, w - (int) cachedTickReserved);
        textDirty = true; // width changed → ellipsis must be rebuilt
    }

    private void maybeRebuildEllipsis(int avail) {
        if (!textDirty && avail == lastEllipsisWidth) return;
        ellipsized = TextUtils.ellipsize(rawText, textPaint,
                Math.max(0, avail), TextUtils.TruncateAt.END);
        lastEllipsisWidth = avail;
        textDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // v85 hot path: zero allocations, all values are pre-cached fields
        if (getWidth() <= 0 || getHeight() <= 0) return;

        if (tickState != TICK_NONE) {
            drawTicks(canvas, 0f, baseline);
        }

        maybeRebuildEllipsis(availableTextWidth);
        canvas.drawText(ellipsized, 0, ellipsized.length(),
                cachedTickReserved, baseline, textPaint);
    }

    private void drawTicks(Canvas canvas, float x, float baselineY) {
        float size = tickSizePx;
        float y    = baselineY - size * 0.4f;
        drawSingleTick(canvas, x, y, size);
        if (tickState == TICK_DELIVERED || tickState == TICK_READ) {
            drawSingleTick(canvas, x + size * 0.35f, y, size);
        }
    }

    private void drawSingleTick(Canvas canvas, float x, float y, float size) {
        canvas.drawLine(x,              y + size * 0.5f,
                        x + size * 0.35f, y + size * 0.8f, tickPaint);
        canvas.drawLine(x + size * 0.35f, y + size * 0.8f,
                        x + size,          y + size * 0.1f, tickPaint);
    }
}
