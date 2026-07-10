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
 * ChatListNameTimeView — v82 canvas optimisation.
 *
 * WHY THIS EXISTS
 * ───────────────
 * The chat-list row previously paid for TWO separate TextView layout passes on
 * every bind: tv_name (name, bold 16 sp) on the left and tv_time (time, muted
 * 11 sp) on the right, both inside a horizontal LinearLayout. On large lists or
 * fast scrolling that extra measure / layout overhead adds up noticeably.
 *
 * This view collapses both into a single plain View:
 *  • name  — drawn left-aligned in bold 16 sp, ellipsized when needed
 *  • time  — drawn right-aligned in regular 11 sp, never ellipsized
 * Both live on the SAME baseline row, so the total height equals max(name, time)
 * font metrics — identical to what the two TextViews produced, but with one
 * measure pass and one draw call.
 *
 * The same class is reused for the group-list row where the right text shows
 * "N members" instead of a timestamp — the semantics of left/right are generic.
 *
 * PERF: both setters are no-ops (skip invalidate) when the value is unchanged,
 * so selection-mode payload rebinds that don't touch name/time do zero work here.
 */
public class ChatListNameTimeView extends View {

    private static final float NAME_SIZE_SP = 16f;
    private static final float TIME_SIZE_SP = 11f;

    private final TextPaint namePaint  = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint  = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private String rawName = "";
    private String rawTime = "";

    private CharSequence ellipsizedName = "";
    private int lastNameWidth = -1;
    private boolean nameDirty = true;

    public ChatListNameTimeView(Context ctx) {
        this(ctx, null);
    }

    public ChatListNameTimeView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float sp = ctx.getResources().getDisplayMetrics().scaledDensity;

        namePaint.setTextSize(NAME_SIZE_SP * sp);
        namePaint.setTypeface(Typeface.DEFAULT_BOLD);
        namePaint.setColor(0xFF0F172A); // text_primary

        timePaint.setTextSize(TIME_SIZE_SP * sp);
        timePaint.setTypeface(Typeface.DEFAULT);
        timePaint.setColor(0xFF94A3B8); // text_muted
    }

    /**
     * Sets the left (name) text. No-op if unchanged.
     */
    public void setName(String name) {
        String safe = name == null ? "" : name;
        if (safe.equals(rawName)) return;
        rawName = safe;
        nameDirty = true;
        invalidate();
    }

    /**
     * Sets the name text color (used for unread highlight).  No-op if unchanged.
     */
    public void setNameColor(int color) {
        if (namePaint.getColor() == color) return;
        namePaint.setColor(color);
        invalidate();
    }

    /**
     * Sets the right (time / members) text. No-op if unchanged.
     */
    public void setTime(String time) {
        String safe = time == null ? "" : time;
        if (safe.equals(rawTime)) return;
        rawTime = safe;
        invalidate();
    }

    /**
     * Sets the time text color (used for unread highlight).  No-op if unchanged.
     */
    public void setTimeColor(int color) {
        if (timePaint.getColor() == color) return;
        timePaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        Paint.FontMetrics fmName = namePaint.getFontMetrics();
        Paint.FontMetrics fmTime = timePaint.getFontMetrics();
        int nameH = (int) Math.ceil(fmName.descent - fmName.ascent);
        int timeH = (int) Math.ceil(fmTime.descent - fmTime.ascent);
        int desiredH = Math.max(nameH, timeH);
        setMeasuredDimension(w, resolveSize(desiredH, heightMeasureSpec));
    }

    private void rebuildEllipsisIfNeeded(int nameWidth) {
        if (!nameDirty && nameWidth == lastNameWidth) return;
        ellipsizedName = TextUtils.ellipsize(rawName, namePaint,
                Math.max(0, nameWidth), TextUtils.TruncateAt.END);
        lastNameWidth = nameWidth;
        nameDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float timeW = rawTime.isEmpty() ? 0f : timePaint.measureText(rawTime);
        int nameAvail = (int) (w - timeW - (timeW > 0 ? 8 * getResources().getDisplayMetrics().density : 0));
        rebuildEllipsisIfNeeded(nameAvail);

        Paint.FontMetrics fmName = namePaint.getFontMetrics();
        float nameBaseline = h / 2f - (fmName.ascent + fmName.descent) / 2f;

        canvas.drawText(ellipsizedName, 0, ellipsizedName.length(), 0f, nameBaseline, namePaint);

        if (!rawTime.isEmpty()) {
            Paint.FontMetrics fmTime = timePaint.getFontMetrics();
            float timeBaseline = h / 2f - (fmTime.ascent + fmTime.descent) / 2f;
            canvas.drawText(rawTime, w - timeW, timeBaseline, timePaint);
        }
    }
}
