package com.callx.app.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * MusicTickerView — Instagram-style continuous audio-name ticker.
 *
 * WHY THIS EXISTS:
 * The bio song-name row (music note icon + song name under the username)
 * previously used a plain TextView with maxLines=1 + ellipsize="end", so a
 * long song/artist name just got cut off with "…" — no motion at all.
 * Instagram scrolls the name continuously leftward in a loop: text slides
 * out to the left, and after a gap a fresh copy slides in from the right
 * and the cycle repeats — a classic seamless ticker-tape effect, not
 * Android's built-in one-shot marquee (which only scrolls once while
 * focused/selected, then stops).
 *
 * If the text already fits within the view's fixed width, it's drawn once,
 * centered vertically, completely static — matches Instagram (short audio
 * names never scroll).
 *
 * ── PERF (v215 ultra-opt pass) ──────────────────────────────────────────
 * This view lives inside the reels feed, where a fresh instance's setText()
 * fires on every single reel bind (ViewPager2 fragment reuse), and — before
 * this pass — an infinite ValueAnimator kept invalidating + re-running full
 * text shaping/rasterization via canvas.drawText() every ~16ms for as long
 * as the view was attached, REGARDLESS of whether that reel was actually
 * the one on screen and playing. Three changes fix that:
 *
 * 1. GLYPH BITMAP CACHE — the text is shaped + rasterized to an offscreen
 *    Bitmap exactly once per setText()/size/color change (rebuildBitmap()).
 *    Every animation frame after that is a cheap canvas.drawBitmap() blit
 *    (GPU texture composite) instead of re-running text layout/shaping —
 *    by far the most expensive part of drawText() — on every single frame.
 * 2. HARDWARE LAYER WHILE SCROLLING — mirrors the exact pattern already
 *    used for the music-disc rotation in ReelUiController: LAYER_TYPE_
 *    HARDWARE is applied only while the scroll animator is actually
 *    running, and dropped back to NONE the moment it stops, so an idle
 *    ticker never holds onto GPU texture memory for nothing.
 * 3. EXPLICIT pause()/resume() — driven by ReelUiController's existing
 *    startDiscAnimation()/stopDiscAnimation() hooks, which already fire in
 *    lockstep with "this reel just became the active/playing one" and
 *    "this reel just got paused/left the screen". The ticker now freezes
 *    the instant a reel is paused or scrolled away instead of burning
 *    battery animating text nobody can see — the same idea as the disc
 *    icon already stopping its rotation, just applied to the ticker too.
 *    (onAttachedToWindow/onDetachedFromWindow are kept as a safety net for
 *    any other call site that doesn't wire up pause()/resume().)
 */
public class MusicTickerView extends View {

    private static final float SPEED_DP_PER_SEC = 28f;   // scroll speed
    private static final float GAP_DP           = 28f;   // gap between the looping copies

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final float gapPx;

    private String text = "";
    private Bitmap glyphBitmap;      // cached rasterized text — rebuilt only on content/size/color change
    private boolean needsScroll = false;
    private boolean isAttached = false;
    private boolean externallyPaused = false;

    private float scrollOffset = 0f;
    private ValueAnimator scrollAnimator;

    public MusicTickerView(Context context) {
        this(context, null);
    }

    public MusicTickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        gapPx = GAP_DP * density;
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(11 * context.getResources().getDisplayMetrics().scaledDensity);
    }

    public void setTextColor(int color) {
        if (textPaint.getColor() == color) return;
        textPaint.setColor(color);
        rebuildBitmap();
        updateAnimationState(true);
        invalidate();
    }

    public void setTextSizePx(float px) {
        if (textPaint.getTextSize() == px) return;
        textPaint.setTextSize(px);
        recompute();
    }

    /** Drop-in replacement for TextView#setText — same call site pattern. */
    public void setText(CharSequence newText) {
        String s = newText == null ? "" : newText.toString();
        if (s.equals(text)) return;
        text = s;
        recompute();
    }

    /**
     * Freezes the ticker on its current frame without discarding the cached
     * glyph bitmap or scroll position. Call when the reel this ticker
     * belongs to is paused or leaves the screen — matches
     * ReelUiController#stopDiscAnimation().
     */
    public void pause() {
        if (externallyPaused) return;
        externallyPaused = true;
        updateAnimationState(false);
    }

    /**
     * Resumes scrolling (if the text actually overflows) after pause().
     * Call when the reel becomes the active/playing one again — matches
     * ReelUiController#startDiscAnimation().
     */
    public void resume() {
        if (!externallyPaused) return;
        externallyPaused = false;
        updateAnimationState(true);
    }

    /** Full teardown — cancels any animation and frees the cached bitmap. */
    public void release() {
        stopScrollingInternal();
        if (glyphBitmap != null) {
            glyphBitmap.recycle();
            glyphBitmap = null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recompute();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        isAttached = true;
        updateAnimationState(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        isAttached = false;
        updateAnimationState(false);
    }

    private void recompute() {
        rebuildBitmap();
        int viewWidth = getWidth();
        int bitmapWidth = glyphBitmap != null ? glyphBitmap.getWidth() : 0;
        needsScroll = viewWidth > 0 && bitmapWidth > viewWidth;
        scrollOffset = 0f;
        // Any content/size change invalidates the currently-running
        // animator's duration (it was tuned to the OLD text width) — always
        // restart rather than only on a needsScroll toggle, otherwise a
        // reel swap that lands on another overflowing song name would keep
        // scrolling at the wrong speed for the new text.
        updateAnimationState(true);
        invalidate();
        requestLayout();
    }

    /** Rebuilds the cached glyph bitmap. Cheap: single-line, small, done once per change — not per frame. */
    private void rebuildBitmap() {
        if (glyphBitmap != null) {
            glyphBitmap.recycle();
            glyphBitmap = null;
        }
        if (TextUtils.isEmpty(text)) return;

        int h = getHeight();
        if (h <= 0) {
            h = Math.round((-textPaint.ascent() + textPaint.descent()) + 0.5f);
        }
        int w = Math.max(1, Math.round(textPaint.measureText(text)));
        if (h <= 0) return;

        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            float baseline = (h - (textPaint.descent() + textPaint.ascent())) / 2f;
            c.drawText(text, 0, baseline, textPaint);
            glyphBitmap = bmp;
        } catch (OutOfMemoryError oom) {
            // Extremely unlikely for a single line of UI text, but never
            // crash the reels feed over a decorative ticker — just fall
            // back to no rendering for this bind.
            glyphBitmap = null;
        }
    }

    /** Single source of truth for "should the animator be running right now". */
    private void updateAnimationState(boolean forceRestart) {
        boolean shouldRun = needsScroll && isAttached && !externallyPaused && glyphBitmap != null;
        if (shouldRun) {
            if (scrollAnimator == null || forceRestart) {
                startScrollingInternal();
            }
        } else if (scrollAnimator != null) {
            stopScrollingInternal();
        }
    }

    private void startScrollingInternal() {
        stopScrollingInternal();
        if (glyphBitmap == null) return;
        float cyclePx = glyphBitmap.getWidth() + gapPx;
        float density = getContext().getResources().getDisplayMetrics().density;
        long durationMs = Math.round((cyclePx / (SPEED_DP_PER_SEC * density)) * 1000);
        if (durationMs <= 0) return;

        // GPU-cache the view's texture while it's animating — same idea as
        // ReelUiController's disc-rotation hardware layer.
        setLayerType(LAYER_TYPE_HARDWARE, null);

        scrollOffset = 0f;
        scrollAnimator = ValueAnimator.ofFloat(0f, cyclePx);
        scrollAnimator.setDuration(durationMs);
        scrollAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scrollAnimator.setInterpolator(new LinearInterpolator());
        scrollAnimator.addUpdateListener(a -> {
            scrollOffset = (float) a.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.start();
    }

    private void stopScrollingInternal() {
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
            scrollAnimator = null;
        }
        // Idle ticker shouldn't keep holding a GPU-cached layer around.
        setLayerType(LAYER_TYPE_NONE, null);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightPx = Math.round(
                (-textPaint.ascent() + textPaint.descent()) + 0.5f);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec) : heightPx;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (TextUtils.isEmpty(text)) return;

        if (glyphBitmap == null) {
            // Fallback (OOM or not-yet-laid-out) — draw text directly this
            // one time rather than showing nothing.
            float baseline = (getHeight() - (textPaint.descent() + textPaint.ascent())) / 2f;
            canvas.drawText(text, 0, baseline, textPaint);
            return;
        }

        if (!needsScroll) {
            // Fits within the fixed width — static, matches Instagram's
            // behavior for short audio names (no motion at all).
            canvas.drawBitmap(glyphBitmap, 0, 0, bitmapPaint);
            return;
        }

        float cyclePx = glyphBitmap.getWidth() + gapPx;
        float x = -scrollOffset;
        int bw = glyphBitmap.getWidth();
        // Draw enough repeated copies to cover the visible width at any
        // scroll position — normally 2 is enough (current exiting copy +
        // the one following it in), a couple extra guard very short view
        // widths. Each iteration is now a cheap bitmap blit, not a
        // text-shaping pass, so the extra guard copies cost effectively
        // nothing.
        int maxCopies = (int) (getWidth() / Math.max(cyclePx, 1f)) + 2;
        for (int i = -1; i <= maxCopies; i++) {
            float drawX = x + i * cyclePx;
            if (drawX < getWidth() && drawX + bw > 0) {
                canvas.drawBitmap(glyphBitmap, drawX, 0, bitmapPaint);
            }
        }
    }
}
