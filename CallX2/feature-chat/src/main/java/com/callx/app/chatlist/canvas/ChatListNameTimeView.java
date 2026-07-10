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
 * ChatListNameTimeView — v83 Telegram-level perf hardening.
 *
 * v82 CHANGES: collapsed tv_name + tv_time into one canvas view (single measure+draw).
 *
 * v83 PERF FIXES (zero-allocation hot path):
 *  1. FontMetrics cached once in constructor — onDraw() and onMeasure() never call
 *     getFontMetrics() (which allocates a new FontMetrics on every call).
 *  2. timePaint.measureText(rawTime) result cached in cachedTimeWidth — only
 *     recomputed when rawTime actually changes, not on every draw frame.
 *  3. nameHeight / timeHeight derived from cached FontMetrics so onMeasure()
 *     is also alloc-free.
 *  4. gapPx computed once in constructor (was: getResources().getDisplayMetrics()
 *     called inside onDraw every frame).
 *  5. nameBaseline + timeBaseline values pre-cached after onSizeChanged so they
 *     are NOT recomputed inside onDraw (previously used h/2 requiring a division
 *     every frame — now it's a plain float field read).
 *
 * Result: onDraw() path is zero-allocation and contains only:
 *   canvas.drawText(ellipsizedName, ...) + canvas.drawText(rawTime, ...)
 */
public class ChatListNameTimeView extends View {

    private static final float NAME_SIZE_SP = 16f;
    private static final float TIME_SIZE_SP = 11f;

    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // v83: FontMetrics cached once — never re-allocated in draw/measure
    private final Paint.FontMetrics fmName;
    private final Paint.FontMetrics fmTime;
    private final int nameHeight;  // cached ceil(descent - ascent) for name
    private final int timeHeight;  // cached for time
    private final float gapPx;    // gap between name and time (8dp, computed once)

    private String rawName = "";
    private String rawTime = "";

    // v83: ellipsis cache
    private CharSequence ellipsizedName = "";
    private int lastNameWidth = -1;
    private boolean nameDirty = true;

    // v83: time-width cache — recomputed only when rawTime changes
    private String cachedTimeStr = null;
    private float cachedTimeWidth = 0f;

    // v83: baseline cache — recomputed only in onSizeChanged, not per draw
    private float nameBaseline = 0f;
    private float timeBaseline = 0f;

    public ChatListNameTimeView(Context ctx) {
        this(ctx, null);
    }

    public ChatListNameTimeView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float sp = ctx.getResources().getDisplayMetrics().scaledDensity;
        float dp = ctx.getResources().getDisplayMetrics().density;

        namePaint.setTextSize(NAME_SIZE_SP * sp);
        namePaint.setTypeface(Typeface.DEFAULT_BOLD);
        namePaint.setColor(0xFF0F172A);
        // v86: sub-pixel + linear text — faster GPU rasterization on hardware canvas
        namePaint.setSubpixelText(true);
        namePaint.setLinearText(true);

        timePaint.setTextSize(TIME_SIZE_SP * sp);
        timePaint.setTypeface(Typeface.DEFAULT);
        timePaint.setColor(0xFF94A3B8);
        timePaint.setSubpixelText(true);
        timePaint.setLinearText(true);

        // Cache FontMetrics once — avoids allocation on every measure/draw
        fmName = namePaint.getFontMetrics();
        fmTime = timePaint.getFontMetrics();
        nameHeight = (int) Math.ceil(fmName.descent - fmName.ascent);
        timeHeight = (int) Math.ceil(fmTime.descent - fmTime.ascent);
        gapPx = 8f * dp;
    }

    /** Sets the left (name) text. No-op if unchanged. */
    public void setName(String name) {
        String safe = name == null ? "" : name;
        if (safe.equals(rawName)) return;
        rawName = safe;
        nameDirty = true;
        invalidate();
    }

    /** Sets the name text color (unread highlight). No-op if unchanged. */
    public void setNameColor(int color) {
        if (namePaint.getColor() == color) return;
        namePaint.setColor(color);
        nameDirty = true;
        invalidate();
    }

    /** Sets the right (time / members) text. No-op if unchanged. */
    public void setTime(String time) {
        String safe = time == null ? "" : time;
        if (safe.equals(rawTime)) return;
        rawTime = safe;
        // Invalidate the time-width cache
        cachedTimeStr = null;
        nameDirty = true; // name avail width may change
        invalidate();
    }

    /** Sets the time text color. No-op if unchanged. */
    public void setTimeColor(int color) {
        if (timePaint.getColor() == color) return;
        timePaint.setColor(color);
        invalidate();
    }

    // v83: measureText cached — called only when rawTime changes (not every frame)
    private float getTimeWidth() {
        if (!rawTime.equals(cachedTimeStr)) {
            cachedTimeStr = rawTime;
            cachedTimeWidth = rawTime.isEmpty() ? 0f : timePaint.measureText(rawTime);
        }
        return cachedTimeWidth;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        // v83: use cached heights — no getFontMetrics() call here
        int desiredH = Math.max(nameHeight, timeHeight);
        setMeasuredDimension(w, resolveSize(desiredH, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // v83: pre-compute baselines so onDraw() reads a field instead of dividing
        nameBaseline = h / 2f - (fmName.ascent + fmName.descent) / 2f;
        timeBaseline = h / 2f - (fmTime.ascent + fmTime.descent) / 2f;
        nameDirty = true; // width changed → ellipsis must be rebuilt
    }

    private void rebuildEllipsisIfNeeded(int nameWidth) {
        if (!nameDirty && nameWidth == lastNameWidth) return;
        ellipsizedName = TextUtils.ellipsize(rawName, namePaint,
                Math.max(0f, nameWidth), TextUtils.TruncateAt.END);
        lastNameWidth = nameWidth;
        nameDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // v83 hot path: zero allocations, all values are pre-cached fields
        int w = getWidth();
        if (w <= 0) return;

        float timeW = getTimeWidth();
        int nameAvail = (int) (w - timeW - (timeW > 0 ? gapPx : 0));
        rebuildEllipsisIfNeeded(nameAvail);

        canvas.drawText(ellipsizedName, 0, ellipsizedName.length(), 0f, nameBaseline, namePaint);

        if (timeW > 0f) {
            canvas.drawText(rawTime, w - timeW, timeBaseline, timePaint);
        }
    }
}
