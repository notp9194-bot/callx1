package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * ChatListStoryRingView — v82 canvas optimisation.
 *
 * WHY THIS EXISTS
 * ───────────────
 * The old iv_story_ring was an ImageView whose background was toggled between
 * two GradientDrawable XML files (circle_status_unseen — brand_primary ring,
 * circle_status_seen — grey ring) or set to GONE. Each toggle forced:
 *   • a setBackgroundResource() call which inflates a new Drawable
 *   • a full Drawable invalidate + draw pass (full bitmap-backed drawable path)
 *
 * This view replaces that with a single plain View that draws the ring arc
 * directly with Paint.Style.STROKE + canvas.drawOval():
 *   • UNSEEN → brand_primary stroke (#4CAF50)
 *   • SEEN   → muted grey stroke (#CBD5E1)
 *   • NONE   → nothing drawn (the View itself is kept visible but transparent,
 *              so its hit-test/click-listener always works for the avatar area)
 *
 * The stroke geometry matches the old drawables: a ring around the avatar
 * (the view is sized to 58 dp, avatar is 50 dp centred inside, ring sits 1 dp
 * inside the view edge = at ~3 dp gap from the avatar border — same visual).
 *
 * PERF: setState() is a no-op when the state is unchanged (skip invalidate).
 */
public class ChatListStoryRingView extends View {

    public static final int STATE_NONE   = 0;
    public static final int STATE_UNSEEN = 1;
    public static final int STATE_SEEN   = 2;

    private static final float STROKE_DP = 3f;
    private static final float INSET_DP  = 2f;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval      = new RectF();

    private final float strokePx;
    private final float insetPx;

    private int state = STATE_NONE;

    // unseen = brand_primary; seen = muted/grey
    private static final int COLOR_UNSEEN = 0xFF4CAF50;
    private static final int COLOR_SEEN   = 0xFFCBD5E1;

    public ChatListStoryRingView(Context ctx) {
        this(ctx, null);
    }

    public ChatListStoryRingView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float dp  = ctx.getResources().getDisplayMetrics().density;
        strokePx  = STROKE_DP * dp;
        insetPx   = INSET_DP  * dp;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(strokePx);
        ringPaint.setColor(COLOR_UNSEEN);
    }

    /**
     * Sets the ring state. No-op if unchanged.
     * @param newState STATE_NONE / STATE_UNSEEN / STATE_SEEN
     */
    public void setState(int newState) {
        if (newState == state) return;
        state = newState;
        if (state == STATE_UNSEEN) {
            ringPaint.setColor(COLOR_UNSEEN);
        } else {
            ringPaint.setColor(COLOR_SEEN);
        }
        invalidate();
    }

    public int getState() { return state; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (state == STATE_NONE) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float half = strokePx / 2f;
        oval.set(insetPx + half, insetPx + half,
                 w - insetPx - half, h - insetPx - half);
        canvas.drawOval(oval, ringPaint);
    }
}
