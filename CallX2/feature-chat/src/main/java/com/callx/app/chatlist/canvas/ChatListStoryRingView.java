package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.callx.app.utils.StoryRingBitmapCache;

/**
 * ChatListStoryRingView — v84 brand gradient ring.
 *
 * UNSEEN → brand gradient sweep (Pink-Purple 0-15% → Pink-Red 15-55% →
 *           Orange 55-75% → Yellow 75-100%, measured clockwise from the
 *           top — see StoryRingShaderCache for the exact hex stops).
 * SEEN   → muted grey stroke (#CBD5E1)
 * NONE   → nothing drawn
 *
 * PERF: setState() is a no-op when the state is unchanged.
 *
 * v40 PERF PASS: shared SweepGradient via StoryRingShaderCache instead of
 * allocating a new one per bind.
 *
 * v41 PERF PASS (bitmap blit): UNSEEN no longer strokes a live shader at
 * all. {@link StoryRingBitmapCache} pre-rasterizes the gradient ring to a
 * shared bitmap once per size; onDraw() now does a single
 * {@code drawBitmap} texture blit for the gradient case — zero per-pixel
 * shader evaluation on every frame while the chat list scrolls/flings, no
 * matter how many rings are on screen. SEEN state stays a plain flat-color
 * stroke (already trivially cheap, no shader involved either way).
 */
public class ChatListStoryRingView extends View {

    public static final int STATE_NONE   = 0;
    public static final int STATE_UNSEEN = 1;
    public static final int STATE_SEEN   = 2;

    private static final float STROKE_DP = 3f;
    private static final float INSET_DP  = 2f;

    private final Paint ringPaint   = new Paint(Paint.ANTI_ALIAS_FLAG); // SEEN flat stroke
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); // UNSEEN blit
    private final RectF oval        = new RectF();

    private final float strokePx;
    private final float insetPx;

    private int state = STATE_NONE;

    private static final int COLOR_SEEN = 0xFFCBD5E1;
    private static final int COLOR_UNSEEN_FALLBACK = 0xFF8A2BE2; // purple (ring start color), used only if bitmap blit isn't safe

    // Whether the cached ring bitmap needs to be re-fetched (on size/state change)
    private boolean ringDirty = true;
    private int lastW = 0, lastH = 0;
    private Bitmap ringBitmap;

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
        ringPaint.setColor(COLOR_SEEN); // overwritten to gradient-purple fallback when UNSEEN, see refreshRing()
    }

    /**
     * Sets the ring state. No-op if unchanged.
     * @param newState STATE_NONE / STATE_UNSEEN / STATE_SEEN
     */
    public void setState(int newState) {
        if (newState == state) return;
        state = newState;
        ringDirty = true;
        invalidate();
    }

    public int getState() { return state; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != lastW || h != lastH) {
            ringDirty = true;
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

        if (ringDirty) {
            refreshRing(w, h);
            ringDirty = false;
        }

        if (state == STATE_UNSEEN) {
            boolean safeToBlit = ringBitmap != null && !ringBitmap.isRecycled()
                    && (canvas.isHardwareAccelerated()
                        || ringBitmap.getConfig() != Bitmap.Config.HARDWARE);
            if (safeToBlit) {
                // Fast path: plain texture blit, no shader math per pixel.
                canvas.drawBitmap(ringBitmap, insetPx, insetPx, bitmapPaint);
            } else {
                // Rare fallback: HARDWARE bitmap on a non-accelerated canvas
                // (would throw), or bitmap cache OOM'd.
                float half = strokePx / 2f;
                oval.set(insetPx + half, insetPx + half,
                         w - insetPx - half, h - insetPx - half);
                canvas.drawOval(oval, ringPaint);
            }
        } else {
            // SEEN → flat grey stroke, already cheap, no bitmap needed.
            float half = strokePx / 2f;
            oval.set(insetPx + half, insetPx + half,
                     w - insetPx - half, h - insetPx - half);
            canvas.drawOval(oval, ringPaint);
        }
    }

    private void refreshRing(int w, int h) {
        if (state == STATE_UNSEEN) {
            int ringW = Math.round(w - 2 * insetPx);
            int ringH = Math.round(h - 2 * insetPx);
            // Shared, pre-rasterized bitmap — built once per distinct size
            // across the whole app, reused on every bind/recycle from here
            // on (cache lookup only, zero draw-time shader work on scroll).
            ringBitmap = StoryRingBitmapCache.get(ringW, ringH, strokePx);
            ringPaint.setColor(COLOR_UNSEEN_FALLBACK); // only used if blit isn't safe
        } else {
            ringBitmap = null;
            ringPaint.setColor(COLOR_SEEN);
        }
    }
}
