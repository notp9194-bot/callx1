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
 * ChatRowContentView — v90 view-tree consolidation.
 *
 * Merges {@link ChatListNameTimeView} (name left / time right) and
 * {@link ChatListLastMessageView} (last-message text + read-receipt ticks)
 * into ONE view with a single onMeasure/onLayout/onSizeChanged/onDraw pass,
 * stacked internally as two text rows. Both source views were already
 * canvas-rendered and zero-allocation in their hot draw paths (cached
 * FontMetrics, cached ellipsis, cached baselines) — this pass doesn't touch
 * that; it removes the SECOND child view from item_chat.xml's vertical
 * LinearLayout column, so RecyclerView has one fewer view to measure and
 * lay out per bind instead of two.
 *
 * Scope note: {@link ChatListNameTimeView}/{@link ChatListLastMessageView}
 * are left untouched and still used as-is by item_group.xml / GroupAdapter
 * — only item_chat.xml / ChatListAdapter (1-on-1 chat list) switch to this
 * merged view, to keep the group-chat row path completely unaffected.
 *
 * All setter signatures are kept identical to the two source views so
 * ChatListAdapter's existing call sites work by simply pointing both the
 * old `nameTimeView` and `lastMessageView` ViewHolder fields at this same
 * instance (see ChatListAdapter.ViewHolder).
 */
public class ChatRowContentView extends View {

    public static final int TICK_NONE      = 0;
    public static final int TICK_SENT      = 1;
    public static final int TICK_DELIVERED = 2;
    public static final int TICK_READ      = 3;

    private static final float NAME_SIZE_SP = 16f;
    private static final float TIME_SIZE_SP = 11f;
    private static final float MSG_SIZE_SP  = 14f;
    private static final float TICK_SIZE_DP = 12f;
    private static final float TICK_GAP_DP  = 4f;

    // ── Row 1: name (left, bold) + time (right, muted) ──────────────────
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fmName;
    private final Paint.FontMetrics fmTime;
    private final int nameRowHeight;
    private final float nameTimeGapPx;

    private String rawName = "";
    private String rawTime = "";
    private CharSequence ellipsizedName = "";
    private int lastNameWidth = -1;
    private boolean nameDirty = true;
    private String cachedTimeStr = null;
    private float cachedTimeWidth = 0f;
    private float nameBaseline = 0f; // relative to row 1's own top (y=0)
    private float timeBaseline = 0f;

    // ── Row 2: last-message text + read-receipt ticks ────────────────────
    private final TextPaint msgPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG
            | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float tickSizePx;
    private final float tickGapPx;
    private final Paint.FontMetrics fmMsg;
    private final int msgRowHeight;

    private String rawMsg = "";
    private boolean msgItalic = false;
    private CharSequence ellipsizedMsg = "";
    private int lastMsgEllipsisWidth = -1;
    private boolean msgDirty = true;
    private int tickState = TICK_NONE;
    private int tickColor = 0xFF94A3B8;
    private float cachedTickReserved = 0f;
    private int availableMsgWidth = 0;
    /** Baseline for row 2, measured from the TOP of the whole view (i.e.
     *  already includes nameRowHeight as an offset) — set in onSizeChanged. */
    private float msgBaselineAbs = 0f;
    private float msgRowTop = 0f;

    public ChatRowContentView(Context ctx) {
        this(ctx, null);
    }

    public ChatRowContentView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float sp = ctx.getResources().getDisplayMetrics().scaledDensity;
        float dp = ctx.getResources().getDisplayMetrics().density;

        namePaint.setTextSize(NAME_SIZE_SP * sp);
        namePaint.setTypeface(Typeface.DEFAULT_BOLD);
        namePaint.setColor(0xFF0F172A);
        namePaint.setSubpixelText(true);
        namePaint.setLinearText(true);

        timePaint.setTextSize(TIME_SIZE_SP * sp);
        timePaint.setTypeface(Typeface.DEFAULT);
        timePaint.setColor(0xFF94A3B8);
        timePaint.setSubpixelText(true);
        timePaint.setLinearText(true);

        fmName = namePaint.getFontMetrics();
        fmTime = timePaint.getFontMetrics();
        int nameTextHeight = (int) Math.ceil(fmName.descent - fmName.ascent);
        int timeTextHeight = (int) Math.ceil(fmTime.descent - fmTime.ascent);
        nameRowHeight = Math.max(nameTextHeight, timeTextHeight);
        nameTimeGapPx = 8f * dp;

        tickSizePx = TICK_SIZE_DP * dp;
        tickGapPx  = TICK_GAP_DP  * dp;

        msgPaint.setTextSize(MSG_SIZE_SP * sp);
        msgPaint.setColor(0xFF64748B);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(1.4f * dp);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setColor(tickColor);

        fmMsg = msgPaint.getFontMetrics();
        msgRowHeight = (int) Math.ceil(fmMsg.descent - fmMsg.ascent);
    }

    // ── Row 1 setters (identical signatures to ChatListNameTimeView) ────

    public void setName(String name) {
        String safe = name == null ? "" : name;
        if (safe.equals(rawName)) return;
        rawName = safe;
        nameDirty = true;
        invalidate();
    }

    public void setNameColor(int color) {
        if (namePaint.getColor() == color) return;
        namePaint.setColor(color);
        invalidate();
    }

    public void setTime(String time) {
        String safe = time == null ? "" : time;
        if (safe.equals(rawTime)) return;
        rawTime = safe;
        cachedTimeStr = null;
        nameDirty = true; // name avail width may change
        invalidate();
    }

    public void setTimeColor(int color) {
        if (timePaint.getColor() == color) return;
        timePaint.setColor(color);
        invalidate();
    }

    // ── Row 2 setters (identical signatures to ChatListLastMessageView) ─

    public void setMessageText(String text, int color, boolean italic) {
        String safe = text == null ? "" : text;
        boolean changed = !safe.equals(rawMsg)
                || msgPaint.getColor() != color
                || this.msgItalic != italic;
        if (!changed) return;

        rawMsg = safe;
        this.msgItalic = italic;
        msgPaint.setColor(color);
        msgPaint.setTypeface(Typeface.defaultFromStyle(italic ? Typeface.ITALIC : Typeface.NORMAL));
        msgDirty = true;
        invalidate();
    }

    public void setTicks(int state, int color) {
        boolean unchanged = state == tickState && (state == TICK_NONE || color == tickColor);
        if (unchanged) return;

        tickState = state;
        tickColor = color;
        tickPaint.setColor(color);
        cachedTickReserved = computeTickReservedWidth();
        availableMsgWidth = Math.max(0, getWidth() - (int) cachedTickReserved);
        msgDirty = true;
        invalidate();
    }

    private float computeTickReservedWidth() {
        if (tickState == TICK_NONE) return 0f;
        float span = (tickState == TICK_SENT) ? tickSizePx : tickSizePx * 1.35f;
        return span + tickGapPx;
    }

    private float getTimeWidth() {
        if (!rawTime.equals(cachedTimeStr)) {
            cachedTimeStr = rawTime;
            cachedTimeWidth = rawTime.isEmpty() ? 0f : timePaint.measureText(rawTime);
        }
        return cachedTimeWidth;
    }

    // ── Single measure/layout/draw pass for BOTH rows ────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int desiredH = nameRowHeight + msgRowHeight;
        setMeasuredDimension(w, resolveSize(desiredH, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // Row 1 baselines, relative to row 1's own top (y=0..nameRowHeight)
        nameBaseline = nameRowHeight / 2f - (fmName.ascent + fmName.descent) / 2f;
        timeBaseline = nameRowHeight / 2f - (fmTime.ascent + fmTime.descent) / 2f;

        // Row 2 sits directly below row 1
        msgRowTop = nameRowHeight;
        msgBaselineAbs = msgRowTop + msgRowHeight / 2f - (fmMsg.ascent + fmMsg.descent) / 2f;

        cachedTickReserved = computeTickReservedWidth();
        availableMsgWidth = Math.max(0, w - (int) cachedTickReserved);
        nameDirty = true;
        msgDirty = true;
    }

    private void rebuildNameEllipsisIfNeeded(int nameWidth) {
        if (!nameDirty && nameWidth == lastNameWidth) return;
        CharSequence cached = com.callx.app.chatlist.ChatListTextPrecompute
                .getName(rawName, nameWidth);
        ellipsizedName = (cached != null)
                ? cached
                : TextUtils.ellipsize(rawName, namePaint,
                        Math.max(0f, nameWidth), TextUtils.TruncateAt.END);
        lastNameWidth = nameWidth;
        nameDirty = false;
    }

    private void rebuildMsgEllipsisIfNeeded(int avail) {
        if (!msgDirty && avail == lastMsgEllipsisWidth) return;
        CharSequence cached = com.callx.app.chatlist.ChatListTextPrecompute
                .getMessage(rawMsg, avail);
        ellipsizedMsg = (cached != null)
                ? cached
                : TextUtils.ellipsize(rawMsg, msgPaint,
                        Math.max(0, avail), TextUtils.TruncateAt.END);
        lastMsgEllipsisWidth = avail;
        msgDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        if (w <= 0 || getHeight() <= 0) return;

        // ── Row 1: name + time ──
        float timeW = getTimeWidth();
        int nameAvail = (int) (w - timeW - (timeW > 0 ? nameTimeGapPx : 0));
        rebuildNameEllipsisIfNeeded(nameAvail);

        canvas.drawText(ellipsizedName, 0, ellipsizedName.length(), 0f, nameBaseline, namePaint);
        if (timeW > 0f) {
            canvas.drawText(rawTime, w - timeW, timeBaseline, timePaint);
        }

        // ── Row 2: ticks + last-message ──
        if (tickState != TICK_NONE) {
            drawTicks(canvas, 0f, msgBaselineAbs);
        }
        rebuildMsgEllipsisIfNeeded(availableMsgWidth);
        canvas.drawText(ellipsizedMsg, 0, ellipsizedMsg.length(),
                cachedTickReserved, msgBaselineAbs, msgPaint);
    }

    private void drawTicks(Canvas canvas, float x, float baselineY) {
        float size = tickSizePx;
        float y = baselineY - size * 0.4f;
        drawSingleTick(canvas, x, y, size);
        if (tickState == TICK_DELIVERED || tickState == TICK_READ) {
            drawSingleTick(canvas, x + size * 0.35f, y, size);
        }
    }

    private void drawSingleTick(Canvas canvas, float x, float y, float size) {
        canvas.drawLine(x,               y + size * 0.5f,
                        x + size * 0.35f, y + size * 0.8f, tickPaint);
        canvas.drawLine(x + size * 0.35f, y + size * 0.8f,
                        x + size,          y + size * 0.1f, tickPaint);
    }
}
