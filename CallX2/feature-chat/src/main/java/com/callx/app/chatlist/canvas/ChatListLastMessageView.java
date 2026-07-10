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
 * ChatListLastMessageView — v23 chat-list row rendering, Canvas-based.
 *
 * WHY THIS EXISTS
 * ────────────────
 * Same motivation as MessageBubbleCanvasView on the conversation screen:
 * the chat list row's "last message" line used to be a nested LinearLayout
 * — an iv_read_status ImageView (tick) + a tv_last_message TextView — sitting
 * inside item_chat.xml. Every bind/rebind of a visible row (which happens a
 * LOT during fast scrolling, since RecyclerView constantly recycles/rebinds)
 * paid for:
 *   • measure + layout of that inner LinearLayout AND both its children
 *   • a bitmap ImageView draw path + colorFilter switch, just to show a
 *     2-stroke check mark
 *   • a full TextView draw path for a single line of text
 *
 * This view collapses both children (and their parent LinearLayout) into
 * ONE plain View whose onDraw() paints the tick strokes directly with
 * canvas.drawLine() — exactly the same technique as
 * MessageBubbleCanvasView#drawTick()/#drawSingleTick() uses for message
 * bubbles in the chat screen — and the last-message text via a plain
 * TextUtils.ellipsize() + canvas.drawText(). No StaticLayout/TextView is
 * needed here since a chat-list row is always a single line.
 *
 * Ticks are only ever grey (sent/delivered) or blue (read) — no bitmap, no
 * drawable, no colorFilter switch, just a Paint.setColor() before two
 * drawLine() calls.
 *
 * PERF: both setters below are no-ops (skip invalidate entirely) when the
 * new value matches what's already drawn — a scroll-driven rebind of a row
 * whose content hasn't actually changed (e.g. a payload-only selection-mode
 * toggle) does zero text-measurement or draw work for this view. Text is
 * only re-ellipsized when the raw string OR the available width actually
 * changes (see maybeRebuildEllipsis()), not on every onDraw().
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
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float tickSizePx;
    private final float tickGapPx;

    private String rawText = "";
    private boolean italic = false;
    private CharSequence ellipsized = "";
    private int lastEllipsisWidth = -1;
    private boolean textDirty = true;

    private int tickState = TICK_NONE;
    private int tickColor = 0xFF94A3B8;

    public ChatListLastMessageView(Context ctx) {
        this(ctx, null);
    }

    public ChatListLastMessageView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float density = ctx.getResources().getDisplayMetrics().density;
        tickSizePx = TICK_SIZE_DP * density;
        tickGapPx  = TICK_GAP_DP * density;

        textPaint.setTextSize(TEXT_SIZE_SP * ctx.getResources().getDisplayMetrics().scaledDensity);
        textPaint.setColor(0xFF64748B); // text_secondary default, overwritten on first bind

        // PERF: style/strokeWidth/cap set ONCE here — mirrors the tickPaint
        // field comment in MessageBubbleCanvasView — never reset per-draw,
        // only the color varies (and only when it actually changes).
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(1.4f * density);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setColor(tickColor);
    }

    /**
     * Sets the last-message line's text/color/style (italic for the live
     * "typing..." row). No-op — skips invalidate entirely — if nothing
     * actually changed from what's already drawn.
     */
    public void setMessageText(String text, int color, boolean italic) {
        String safe = text == null ? "" : text;
        boolean changed = !safe.equals(rawText)
                || textPaint.getColor() != color
                || this.italic != italic;
        if (!changed) return;

        rawText = safe;
        this.italic = italic;
        textPaint.setColor(color);
        textPaint.setTypeface(Typeface.defaultFromStyle(italic ? Typeface.ITALIC : Typeface.NORMAL));
        textDirty = true; // available width for ellipsis is unaffected, but the string itself is new
        invalidate();
    }

    /**
     * Sets the read-receipt tick state (NONE / SENT / DELIVERED / READ) and
     * its draw color. No-op if the state (and color, when a tick is shown)
     * is unchanged from what's already drawn.
     */
    public void setTicks(int state, int color) {
        boolean unchanged = state == tickState && (state == TICK_NONE || color == tickColor);
        if (unchanged) return;

        tickState = state;
        tickColor = color;
        tickPaint.setColor(color);
        textDirty = true; // reserved tick width changed → text needs re-ellipsizing at the new offset
        invalidate();
    }

    private float tickReservedWidth() {
        if (tickState == TICK_NONE) return 0f;
        // Single tick spans ~1 tick-size horizontally; the doubled
        // delivered/read mark is offset by 0.35x and spans a bit further —
        // matches drawSingleTick()'s actual horizontal extent below.
        float span = (tickState == TICK_SENT) ? tickSizePx : tickSizePx * 1.35f;
        return span + tickGapPx;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        int desiredHeight = (int) Math.ceil(fm.descent - fm.ascent);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    private void maybeRebuildEllipsis(int availableWidth) {
        if (!textDirty && availableWidth == lastEllipsisWidth) return;
        ellipsized = TextUtils.ellipsize(rawText, textPaint, Math.max(0, availableWidth), TextUtils.TruncateAt.END);
        lastEllipsisWidth = availableWidth;
        textDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;

        float tickReserved = tickReservedWidth();
        if (tickState != TICK_NONE) {
            drawTicks(canvas, 0f, baseline);
        }

        int availableTextWidth = (int) (w - tickReserved);
        maybeRebuildEllipsis(availableTextWidth);
        canvas.drawText(ellipsized, 0, ellipsized.length(), tickReserved, baseline, textPaint);
    }

    // Mirrors MessageBubbleCanvasView#drawTick()/#drawSingleTick() exactly:
    // two drawLine() strokes per check mark, doubled up (offset on x) for
    // delivered/read.
    private void drawTicks(Canvas canvas, float x, float baselineY) {
        float size = tickSizePx;
        float y = baselineY - size * 0.4f;
        drawSingleTick(canvas, x, y, size);
        if (tickState == TICK_DELIVERED || tickState == TICK_READ) {
            drawSingleTick(canvas, x + size * 0.35f, y, size);
        }
    }

    private void drawSingleTick(Canvas canvas, float x, float y, float size) {
        canvas.drawLine(x, y + size * 0.5f, x + size * 0.35f, y + size * 0.8f, tickPaint);
        canvas.drawLine(x + size * 0.35f, y + size * 0.8f, x + size, y + size * 0.1f, tickPaint);
    }
}
