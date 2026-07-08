package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Draws the footer row (timestamp + expiry countdown + delivery tick) and the
 * floating corner-expiry pill used by card-style bubbles (contact, location).
 *
 * Extracted from MessageBubbleCanvasView (feature-based split, no behaviour
 * change). All callers inside the canvas package access this via
 * {@code host.footerRenderer.*}.
 */
final class FooterRenderer {

    private final MessageBubbleCanvasView host;

    FooterRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    /**
     * Draws the footer row: timestamp, optional expiry countdown, and for sent
     * bubbles the delivery/read tick.
     */
    void drawFooter(Canvas canvas, float footerBaselineY, float footerRightX) {
        float timeX = footerRightX
                - host.footerPaint.measureText(host.footerTimeText)
                - (host.sent
                        ? (MessageBubbleCanvasView.TICK_SIZE_DP + MessageBubbleCanvasView.TICK_GAP_DP) * host.density
                        : 0);
        canvas.drawText(host.footerTimeText, timeX, footerBaselineY, host.footerPaint);

        // Record bounding box for "✏️ edited" hit-testing in onTouchEvent.
        Paint.FontMetrics footerFm = host.footerPaint.getFontMetrics();
        host.footerTextRect.set(
                timeX,
                footerBaselineY + footerFm.ascent,
                timeX + host.footerPaint.measureText(host.footerTimeText),
                footerBaselineY + footerFm.descent);

        if (host.hasExpiry) {
            canvas.drawText(host.expiryText,
                    timeX - expiryReserveWidth(), footerBaselineY, host.expiryPaint);
        }

        if (host.sent) {
            drawTick(canvas,
                    footerRightX - MessageBubbleCanvasView.TICK_SIZE_DP * host.density,
                    footerBaselineY);
        }
    }

    /**
     * Floating "⏳ mm:ss" badge for cards (contact, location) that have no
     * regular footer row. No-op when {@code host.hasExpiry} is false, so
     * callers may call this unconditionally.
     */
    void drawCornerExpiryPill(Canvas canvas, RectF anchorRect) {
        if (!host.hasExpiry || host.expiryText == null || host.expiryText.isEmpty()) return;
        float padH  = 6f * host.density;
        float padV  = 3f * host.density;
        float inset = 6f * host.density;
        Paint.FontMetrics efm = host.expiryPaint.getFontMetrics();
        float textW = host.expiryPaint.measureText(host.expiryText);
        float pillH = (efm.descent - efm.ascent) + padV * 2;
        float pillW = textW + padH * 2;
        float right = anchorRect.right - inset;
        float top   = anchorRect.top  + inset;
        host.cornerExpiryPillRect.set(right - pillW, top, right, top + pillH);
        canvas.drawRoundRect(host.cornerExpiryPillRect,
                pillH / 2f, pillH / 2f, host.mediaPillBgPaint);
        float baseline = host.cornerExpiryPillRect.centerY()
                - (efm.ascent + efm.descent) / 2f;
        canvas.drawText(host.expiryText,
                host.cornerExpiryPillRect.left + padH, baseline, host.expiryPaint);
    }

    /**
     * Delivery/read tick — single for sent+undelivered, double for delivered,
     * double-blue for read. Style set once in constructor; only colour varies.
     */
    void drawTick(Canvas canvas, float x, float baselineY) {
        float size = MessageBubbleCanvasView.TICK_SIZE_DP * host.density;
        float y    = baselineY - size * 0.4f;
        drawSingleTick(canvas, x, y, size);
        if (host.delivered || host.read) {
            drawSingleTick(canvas, x + size * 0.35f, y, size);
        }
    }

    private void drawSingleTick(Canvas canvas, float x, float y, float size) {
        canvas.drawLine(x,               y + size * 0.5f,
                        x + size * 0.35f, y + size * 0.8f, host.tickPaint);
        canvas.drawLine(x + size * 0.35f, y + size * 0.8f,
                        x + size,         y + size * 0.1f, host.tickPaint);
    }

    /**
     * Width (px) to reserve for the "⏳ mm:ss" expiry text plus gap. Returns
     * 0 when there is nothing to show, so callers can add it unconditionally.
     */
    float expiryReserveWidth() {
        if (!host.hasExpiry || host.expiryText == null || host.expiryText.isEmpty()) return 0f;
        return host.expiryPaint.measureText(host.expiryText)
                + MessageBubbleCanvasView.EXPIRY_GAP_DP * host.density;
    }
}
