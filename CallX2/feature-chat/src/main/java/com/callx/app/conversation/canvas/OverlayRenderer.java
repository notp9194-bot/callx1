package com.callx.app.conversation.canvas;

import android.graphics.Canvas;

/**
 * Draws the overlay elements that float around the bubble independently of its
 * content type:
 * <ul>
 *   <li>Reactions emoji strip below the bubble's end corner.</li>
 *   <li>"📌 Pinned" label above the bubble's end corner.</li>
 *   <li>Group-sender name / broadcast badge above the bubble's start corner.</li>
 *   <li>"↪ Forwarded from X" label below the sender-name row.</li>
 *   <li>Quick-forward icon button in the gutter beside the bubble.</li>
 * </ul>
 *
 * Extracted from MessageBubbleCanvasView (feature-based split, no behaviour
 * change). Called from {@code drawBubbleContent()} on the host view.
 */
final class OverlayRenderer {

    private final MessageBubbleCanvasView host;

    OverlayRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    /** Reactions emoji strip floating below the bubble's bottom-end corner. */
    void drawReactionsBadge(Canvas canvas) {
        // PERF: reuse cached FontMetrics — getFontMetrics() allocates a new
        // object on every call, adding GC pressure at 60fps during scroll.
        if (host.reactionsTextFM == null) {
            host.reactionsTextFM = host.reactionsTextPaint.getFontMetrics();
        }
        float baselineY = host.reactionsRect.bottom - host.reactionsTextFM.descent;
        canvas.drawText(host.reactionsText, host.reactionsRect.left,
                baselineY, host.reactionsTextPaint);
    }

    /**
     * "📌 Pinned" label in the reserved strip above the bubble, right-aligned
     * to the bubble's own right edge (works for sent AND received).
     */
    void drawPinnedLabel(Canvas canvas) {
        canvas.drawText(
                MessageBubbleCanvasView.PINNED_LABEL_TEXT,
                host.bubbleRect.right - host.pinnedLabelWidth,
                host.pinnedBaselineY,
                host.pinnedLabelPaint);
    }

    /**
     * Group-sender name (or broadcast badge) left-aligned to the bubble's own
     * left edge — mirrors {@code tv_sender_name} in the legacy layout.
     */
    void drawGroupSenderName(Canvas canvas) {
        canvas.drawText(host.groupSenderName, host.bubbleRect.left,
                host.groupSenderBaselineY, host.groupSenderPaint);
    }

    /**
     * "↪ Forwarded from X" label in its own row below the pinned/sender row,
     * left-aligned — mirrors {@code tv_forwarded} in the legacy layout.
     */
    void drawForwardedLabel(Canvas canvas) {
        canvas.drawText(host.forwardedText, host.bubbleRect.left,
                host.forwardedBaselineY, host.forwardedPaint);
    }

    /**
     * Quick-forward circular icon button in the gutter outside the bubble
     * (left of sent, right of received), vertically centred against the bubble.
     * Position is derived from {@code host.bubbleRect} on every draw so it
     * always tracks the latest layout.
     */
    void drawForwardButton(Canvas canvas) {
        float btnSize   = MessageBubbleCanvasView.FORWARD_BTN_SIZE_DP   * host.density;
        float btnMargin = MessageBubbleCanvasView.FORWARD_BTN_MARGIN_DP * host.density;
        float cy = host.bubbleRect.top + host.bubbleRect.height() / 2f;
        if (host.sent) {
            float right = host.bubbleRect.left - btnMargin;
            host.forwardBtnRect.set(right - btnSize, cy - btnSize / 2f,
                    right, cy + btnSize / 2f);
        } else {
            float left = host.bubbleRect.right + btnMargin;
            host.forwardBtnRect.set(left, cy - btnSize / 2f,
                    left + btnSize, cy + btnSize / 2f);
        }
        float cx = host.forwardBtnRect.centerX();
        float r  = host.forwardBtnRect.width() / 2f;
        canvas.drawCircle(cx, cy, r, host.forwardBtnBgPaint);

        // Double-chevron "»" glyph — direction invariant to sent/received side.
        float w = r * 0.9f, h = r * 0.95f;
        host.forwardIconPath.reset();
        host.forwardIconPath.moveTo(cx - w * 0.6f, cy - h * 0.5f);
        host.forwardIconPath.lineTo(cx - w * 0.1f, cy);
        host.forwardIconPath.lineTo(cx - w * 0.6f, cy + h * 0.5f);
        host.forwardIconPath.moveTo(cx,             cy - h * 0.5f);
        host.forwardIconPath.lineTo(cx + w * 0.5f, cy);
        host.forwardIconPath.lineTo(cx,             cy + h * 0.5f);
        canvas.drawPath(host.forwardIconPath, host.forwardBtnIconPaint);
    }
}
