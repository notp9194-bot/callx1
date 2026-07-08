package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Draws the manual download-gate controls that appear on media / media-group
 * bubbles before content has been fetched:
 * <ul>
 *   <li>{@link #drawGateIcon} — idle "tap to download" arrow glyph.</li>
 *   <li>{@link #drawProgressRing} — determinate or indeterminate progress arc
 *       once a download is in flight.</li>
 * </ul>
 * Also owns the per-instance indeterminate-spinner throttle state so the
 * ~30fps cap works independently for each bubble.
 *
 * Extracted from MessageBubbleCanvasView (feature-based split, no behaviour
 * change). Callers access via {@code host.downloadGateRenderer.*}.
 */
final class DownloadGateRenderer {

    private final MessageBubbleCanvasView host;

    // Scratch RectF for the arc — avoids a per-draw() allocation.
    private final RectF arcRect = new RectF();

    // Per-instance throttle: limits postInvalidateOnAnimation() to ~30fps
    // so we don't re-draw the full bubble at 60fps for an indeterminate spinner.
    private long lastIndeterminateInvalidateUptimeMs = 0L;
    private static final long INDETERMINATE_INVALIDATE_MIN_INTERVAL_MS = 32L; // ~30 fps

    DownloadGateRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    /**
     * Idle download-gate glyph: a download arrow (vertical stroke + arrowhead
     * + tray). Used before any download is in flight.
     */
    void drawGateIcon(Canvas canvas, float cx, float cy, float size, Paint paint) {
        float r           = size / 2f;
        float shaftTop    = cy - r;
        float shaftBottom = cy + r * 0.25f;
        canvas.drawLine(cx, shaftTop, cx, shaftBottom, paint);
        canvas.drawLine(cx - r * 0.5f, shaftBottom - r * 0.5f, cx, shaftBottom, paint);
        canvas.drawLine(cx + r * 0.5f, shaftBottom - r * 0.5f, cx, shaftBottom, paint);
        canvas.drawLine(cx - r, cy + r, cx + r, cy + r, paint);
    }

    /**
     * Live download-progress ring.
     * {@code percent >= 0} → determinate arc; {@code percent < 0} →
     * indeterminate spinning arc throttled to ~30fps.
     */
    void drawProgressRing(Canvas canvas, float cx, float cy,
                          float size, Paint paint, int percent) {
        float r = size / 2f;
        arcRect.set(cx - r, cy - r, cx + r, cy + r);
        if (percent >= 0) {
            canvas.drawArc(arcRect, -90,
                    360f * (Math.min(percent, 100) / 100f), false, paint);
        } else {
            long now = android.os.SystemClock.uptimeMillis();
            float rotation = (now % MessageBubbleCanvasView.INDETERMINATE_PERIOD_MS)
                    / (float) MessageBubbleCanvasView.INDETERMINATE_PERIOD_MS * 360f;
            canvas.drawArc(arcRect, rotation - 90,
                    MessageBubbleCanvasView.INDETERMINATE_SWEEP_DEG, false, paint);
            long elapsed = now - lastIndeterminateInvalidateUptimeMs;
            if (elapsed >= INDETERMINATE_INVALIDATE_MIN_INTERVAL_MS) {
                lastIndeterminateInvalidateUptimeMs = now;
                host.postInvalidateOnAnimation();
            } else {
                host.postInvalidateDelayed(INDETERMINATE_INVALIDATE_MIN_INTERVAL_MS - elapsed);
            }
        }
    }
}
