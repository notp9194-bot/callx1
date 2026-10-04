package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

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
    /** Vertical breathing room between the name row and the last-message row. */
    private static final float ROW_GAP_DP   = 4f;
    /** Pinned-chat icon: drawn at the right end of the message row.
     *  MUST match ChatListTextPrecompute.PIN_SIZE_DP / PIN_GAP_DP. */
    private static final float PIN_SIZE_DP  = 14f;
    private static final float PIN_GAP_DP   = 6f;
    private static final float PIN_BOX      = 24f; // design grid of PIN_PATH
    /** Muted-chat icon (bell with slash), drawn left of the pin / at the right end.
     *  MUST match ChatListTextPrecompute.MUTE_SIZE_DP / MUTE_GAP_DP. */
    private static final float MUTE_SIZE_DP = 14f;
    private static final float MUTE_GAP_DP  = 6f;

    /** Shared, read-only push-pin glyph on a 24x24 grid (built once per process). */
    private static final Path PIN_PATH = buildPinPath();

    /** Shared bell glyph (24x24 grid); the slash is a separate stroke. */
    private static final Path MUTE_BELL_PATH = buildMuteBellPath();

    private static Path buildMuteBellPath() {
        Path p = new Path();
        p.moveTo(5.5f, 17f);
        p.lineTo(18.5f, 17f);
        p.lineTo(16.8f, 15f);
        p.lineTo(16.8f, 10.5f);
        p.cubicTo(16.8f, 7.4f, 14.8f, 5.6f, 12f, 5.6f);
        p.cubicTo(9.2f, 5.6f, 7.2f, 7.4f, 7.2f, 10.5f);
        p.lineTo(7.2f, 15f);
        p.close();
        p.addCircle(12f, 19.4f, 1.7f, Path.Direction.CW);
        return p;
    }

    private static Path buildPinPath() {
        Path p = new Path();
        p.addRoundRect(new RectF(6f, 2f, 18f, 4.6f), 1.3f, 1.3f, Path.Direction.CW); // cap
        p.moveTo(8.5f, 4.6f);                                                         // body
        p.lineTo(8.5f, 10f);
        p.lineTo(5.5f, 13f);
        p.lineTo(5.5f, 14.8f);
        p.lineTo(18.5f, 14.8f);
        p.lineTo(18.5f, 13f);
        p.lineTo(15.5f, 10f);
        p.lineTo(15.5f, 4.6f);
        p.close();
        p.addRect(11.2f, 14.8f, 12.8f, 22f, Path.Direction.CW);                      // needle
        return p;
    }

    // ── Row 1: name (left, bold) + time (right, muted) ──────────────────
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fmName;
    private final Paint.FontMetrics fmTime;
    private final int nameRowHeight;
    private final float nameTimeGapPx;
    private final float rowGapPx;
    private final float pinSizePx;
    private final float pinGapPx;
    private final float pinScale;
    private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean pinned = false;
    private final float muteSizePx;
    private final float muteGapPx;
    private final float muteScale;
    private final Paint mutePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint muteSlashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean muted = false;
    private float cachedIconsReserved = 0f;

    private String rawName = "";
    private String rawTime = "";
    private CharSequence ellipsizedName = "";
    private int lastNameWidth = -1;
    private boolean nameDirty = true;
    private String cachedTimeStr = null;
    private float cachedTimeWidth = 0f;
    private float nameBaseline = 0f; // relative to row 1's own top (y=0)
    private float timeBaseline = 0f;

    // ── Verified badge (drawn right after the name) ──────────────────────
    private boolean isVerified = false;
    private Bitmap verifiedBadgeBitmap;
    private final float verifiedBadgeSizePx;
    private final float verifiedBadgeGapPx;

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
    /** Unread rows draw the preview bold — see setMessageText(..., bold). */
    private boolean msgBold = false;
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
        rowGapPx = ROW_GAP_DP * dp;
        pinSizePx = PIN_SIZE_DP * dp;
        pinGapPx  = PIN_GAP_DP * dp;
        pinScale  = pinSizePx / PIN_BOX;
        pinPaint.setStyle(Paint.Style.FILL);
        pinPaint.setColor(0xFF94A3B8);
        muteSizePx = MUTE_SIZE_DP * dp;
        muteGapPx  = MUTE_GAP_DP * dp;
        muteScale  = muteSizePx / PIN_BOX;
        mutePaint.setStyle(Paint.Style.FILL);
        mutePaint.setColor(0xFF94A3B8);
        muteSlashPaint.setStyle(Paint.Style.STROKE);
        muteSlashPaint.setStrokeWidth(2.2f);          // grid units (canvas is scaled)
        muteSlashPaint.setStrokeCap(Paint.Cap.ROUND);
        muteSlashPaint.setColor(0xFF94A3B8);
        verifiedBadgeSizePx = 13f * dp;
        verifiedBadgeGapPx = 3f * dp;

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

    /** Shows/hides the verified badge right after the name. Call from VerifiedBadgeUtils. */
    public void setVerified(boolean verified) {
        if (this.isVerified == verified) return;
        this.isVerified = verified;
        if (verified && verifiedBadgeBitmap == null) {
            verifiedBadgeBitmap = loadVerifiedBadgeBitmap(getContext());
        }
        nameDirty = true; // name available width changes when badge reserves space
        invalidate();
    }

    private Bitmap loadVerifiedBadgeBitmap(Context ctx) {
        Drawable d = ContextCompat.getDrawable(ctx, com.callx.app.core.R.drawable.ic_verified_pink);
        if (d == null) return null;
        int size = Math.max(1, Math.round(verifiedBadgeSizePx));
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return bmp;
    }

    // ── Row 2 setters (identical signatures to ChatListLastMessageView) ─

    public void setMessageText(String text, int color, boolean italic) {
        setMessageText(text, color, italic, false);
    }

    /**
     * @param bold true for unread rows — preview is drawn bold so unread chats
     *             stand out at a glance. Only swaps the cached Typeface on a
     *             state change (no per-draw allocation).
     */
    public void setMessageText(String text, int color, boolean italic, boolean bold) {
        String safe = text == null ? "" : text;
        boolean changed = !safe.equals(rawMsg)
                || msgPaint.getColor() != color
                || this.msgItalic != italic
                || this.msgBold != bold;
        if (!changed) return;

        rawMsg = safe;
        this.msgItalic = italic;
        this.msgBold = bold;
        msgPaint.setColor(color);
        int style = (italic ? Typeface.ITALIC : Typeface.NORMAL)
                | (bold ? Typeface.BOLD : Typeface.NORMAL);
        msgPaint.setTypeface(Typeface.defaultFromStyle(style));
        msgDirty = true;
        invalidate();
    }

    /**
     * Shows/hides the pin icon at the right end of the message row (replaces the
     * old "📌 " emoji that was prepended to the name). Reserves its width from the
     * message text so the preview ellipsizes before reaching the icon.
     */
    public void setPinned(boolean pinned, int color) {
        if (this.pinned == pinned && pinPaint.getColor() == color) return;
        boolean layoutChanged = this.pinned != pinned;
        this.pinned = pinned;
        pinPaint.setColor(color);
        if (layoutChanged) applyIconsReserved();
        invalidate();
    }

    /**
     * Shows/hides the mute (bell-with-slash) icon at the right end of the message
     * row, left of the pin if both are present. Reserves its width like the pin.
     */
    public void setMuted(boolean muted, int color) {
        if (this.muted == muted && mutePaint.getColor() == color) return;
        boolean layoutChanged = this.muted != muted;
        this.muted = muted;
        mutePaint.setColor(color);
        muteSlashPaint.setColor(color);
        if (layoutChanged) applyIconsReserved();
        invalidate();
    }

    private void applyIconsReserved() {
        cachedIconsReserved = (pinned ? (pinSizePx + pinGapPx) : 0f)
                            + (muted  ? (muteSizePx + muteGapPx) : 0f);
        availableMsgWidth = Math.max(0, getWidth() - (int) cachedTickReserved - (int) cachedIconsReserved);
        msgDirty = true;
    }

    public void setTicks(int state, int color) {
        boolean unchanged = state == tickState && (state == TICK_NONE || color == tickColor);
        if (unchanged) return;

        tickState = state;
        tickColor = color;
        tickPaint.setColor(color);
        cachedTickReserved = computeTickReservedWidth();
        availableMsgWidth = Math.max(0, getWidth() - (int) cachedTickReserved - (int) cachedIconsReserved);
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
        int desiredH = nameRowHeight + Math.round(rowGapPx) + msgRowHeight;
        setMeasuredDimension(w, resolveSize(desiredH, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // Row 1 baselines, relative to row 1's own top (y=0..nameRowHeight)
        nameBaseline = nameRowHeight / 2f - (fmName.ascent + fmName.descent) / 2f;
        timeBaseline = nameRowHeight / 2f - (fmTime.ascent + fmTime.descent) / 2f;

        // Row 2 sits directly below row 1
        msgRowTop = nameRowHeight + Math.round(rowGapPx);
        msgBaselineAbs = msgRowTop + msgRowHeight / 2f - (fmMsg.ascent + fmMsg.descent) / 2f;

        cachedTickReserved = computeTickReservedWidth();
        availableMsgWidth = Math.max(0, w - (int) cachedTickReserved - (int) cachedIconsReserved);
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
        // ChatListTextPrecompute measures with the NORMAL-weight paint, so its
        // entries would be too wide for bold (unread) text and get clipped at
        // the view edge — for bold rows measure here instead. Runs once per
        // bind/width change (guarded by msgDirty above), never per frame.
        CharSequence cached = msgBold ? null : com.callx.app.chatlist.ChatListTextPrecompute
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
        float badgeReserved = isVerified ? (verifiedBadgeSizePx + verifiedBadgeGapPx) : 0f;
        int nameAvail = (int) (w - timeW - (timeW > 0 ? nameTimeGapPx : 0) - badgeReserved);
        rebuildNameEllipsisIfNeeded(nameAvail);

        canvas.drawText(ellipsizedName, 0, ellipsizedName.length(), 0f, nameBaseline, namePaint);
        if (isVerified && verifiedBadgeBitmap != null) {
            float nameTextWidth = namePaint.measureText(ellipsizedName, 0, ellipsizedName.length());
            float badgeTop = nameRowHeight / 2f - verifiedBadgeSizePx / 2f;
            canvas.drawBitmap(verifiedBadgeBitmap, nameTextWidth + verifiedBadgeGapPx, badgeTop, null);
        }
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

        // ── Mute icon: sits left of the pin (or at the right edge if unpinned) ──
        if (muted) {
            float left = w - (pinned ? (pinSizePx + pinGapPx) : 0f) - muteSizePx;
            float top  = msgRowTop + (msgRowHeight - muteSizePx) / 2f;
            int save = canvas.save();
            canvas.translate(left, top);
            canvas.scale(muteScale, muteScale);
            canvas.drawPath(MUTE_BELL_PATH, mutePaint);
            canvas.drawLine(4.5f, 4.5f, 19.5f, 20f, muteSlashPaint);
            canvas.restoreToCount(save);
        }

        // ── Pin icon (right end of row 2) — tilted 45°, zero-alloc draw ──
        if (pinned) {
            float left = w - pinSizePx;
            float top  = msgRowTop + (msgRowHeight - pinSizePx) / 2f;
            int save = canvas.save();
            canvas.translate(left, top);
            canvas.rotate(45f, pinSizePx / 2f, pinSizePx / 2f);
            canvas.scale(pinScale, pinScale);
            canvas.drawPath(PIN_PATH, pinPaint);
            canvas.restoreToCount(save);
        }
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
