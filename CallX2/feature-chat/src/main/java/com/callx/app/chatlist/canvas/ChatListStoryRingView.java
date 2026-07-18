package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

/**
 * ChatListStoryRingView — v83 Instagram-exact gradient ring.
 *
 * UNSEEN → Instagram gradient sweep (purple → red-pink → orange/yellow)
 *           exactly matching Instagram's story ring appearance.
 * SEEN   → muted grey stroke (#CBD5E1)
 * NONE   → nothing drawn
 *
 * Uses SweepGradient (rotated -90° so gradient starts at top) for the
 * Instagram story ring appearance. The gradient colors match:
 *   #833AB4 (purple) → #FD1D1D (red-pink) → #FCAF45 (orange/yellow)
 *
 * PERF: setState() is a no-op when the state is unchanged.
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

    // Instagram gradient colors (purple → red-pink → orange/yellow)
    private static final int[] INSTA_GRADIENT_COLORS = {
        0xFF833AB4, // purple (top)
        0xFFc13584, // magenta
        0xFFe1306c, // hot pink
        0xFFfd1d1d, // red-pink
        0xFFf77737, // orange
        0xFFfcaf45, // orange-yellow (bottom-right)
        0xFFffdc80, // light yellow
        0xFFfcaf45, // back orange-yellow
        0xFFf77737, // orange
        0xFFfd1d1d, // red-pink
        0xFFe1306c, // hot pink
        0xFFc13584, // magenta
        0xFF833AB4  // purple (close loop)
    };

    private static final int COLOR_SEEN = 0xFFCBD5E1;

    // Whether we need to rebuild the gradient shader (on size change)
    private boolean gradientDirty = true;
    private int lastW = 0, lastH = 0;

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
        ringPaint.setColor(0xFF833AB4); // initial color before shader applied
    }

    /**
     * Sets the ring state. No-op if unchanged.
     * @param newState STATE_NONE / STATE_UNSEEN / STATE_SEEN
     */
    public void setState(int newState) {
        if (newState == state) return;
        state = newState;
        gradientDirty = true;
        invalidate();
    }

    public int getState() { return state; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != lastW || h != lastH) {
            gradientDirty = true;
            lastW = w;
            lastH = h;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (state == STATE_NONE) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (gradientDirty) {
            rebuildShader(w, h);
            gradientDirty = false;
        }

        float half = strokePx / 2f;
        oval.set(insetPx + half, insetPx + half,
                 w - insetPx - half, h - insetPx - half);
        canvas.drawOval(oval, ringPaint);
    }

    private void rebuildShader(int w, int h) {
        if (state == STATE_UNSEEN) {
            float cx = w / 2f;
            float cy = h / 2f;
            // SweepGradient centered on the view — rotated so it starts at top
            // canvas.save/rotate is needed for rotation; we handle it via matrix
            SweepGradient sg = new SweepGradient(cx, cy, INSTA_GRADIENT_COLORS, null);
            // Rotate -90° so gradient starts at top (12 o'clock position) like Instagram
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(-90, cx, cy);
            sg.setLocalMatrix(matrix);
            ringPaint.setShader(sg);
        } else {
            // STATE_SEEN → plain grey, no shader
            ringPaint.setShader(null);
            ringPaint.setColor(COLOR_SEEN);
        }
    }
}
