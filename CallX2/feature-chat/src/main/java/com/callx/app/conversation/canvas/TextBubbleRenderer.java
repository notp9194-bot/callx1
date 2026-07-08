package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Path;

/**
 * Draws a plain-text bubble's content area: the {@link android.text.StaticLayout}
 * text block, optional search-highlight rectangles behind matched text, an
 * optional link-preview card below the text, and the footer row (delegated to
 * {@link FooterRenderer}).
 *
 * Extracted from MessageBubbleCanvasView (feature-based split, no behaviour
 * change). Called from {@code drawBubbleContent()} for the plain-text branch.
 */
final class TextBubbleRenderer {

    private final MessageBubbleCanvasView host;

    // Scratch Path reused across drawSearchHighlight() calls — avoids a
    // per-match allocation inside the per-frame draw path.
    private final Path highlightPathScratch = new Path();

    TextBubbleRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    /**
     * Draws the plain-text content. Must only be called when
     * {@code host.textLayout != null}.
     *
     * @param hPad     horizontal inner padding (px)
     * @param vPad     vertical inner padding (px)
     * @param replyGap gap between reply strip and text (0 if no reply)
     */
    void draw(Canvas canvas, int hPad, int vPad, int replyGap) {
        canvas.save();
        canvas.translate(
                host.bubbleLeft + hPad,
                host.bubbleTop + host.replyBoxHeight + replyGap + vPad);
        drawSearchHighlight(canvas);
        host.textLayout.draw(canvas);
        canvas.restore();

        if (host.hasLinkPreview) {
            host.linkPreviewRenderer.draw(canvas);
        }

        if (host.footerInlineWithText) {
            float lastLineBaselineY = host.bubbleTop + host.replyBoxHeight + replyGap + vPad
                    + host.textLayout.getLineBaseline(host.textLayout.getLineCount() - 1);
            host.footerRenderer.drawFooter(canvas, lastLineBaselineY,
                    host.bubbleRect.right - hPad);
        } else {
            host.footerRenderer.drawFooter(canvas,
                    host.bubbleRect.bottom - vPad * 0.4f,
                    host.bubbleRect.right - hPad);
        }
    }

    /**
     * Paints yellow rectangles behind every occurrence of the active search
     * query. Uses {@link android.text.StaticLayout#getSelectionPath} so
     * wrapped matches get correctly-split bands automatically. Must be called
     * inside the same {@code canvas.translate()} the text draws in, and BEFORE
     * {@code textLayout.draw(canvas)}.
     */
    private void drawSearchHighlight(Canvas canvas) {
        if (host.searchHighlightQuery == null
                || host.textLayout == null
                || host.messageText.isEmpty()) return;
        String lq = host.searchHighlightQuery.toLowerCase(java.util.Locale.getDefault());
        String lt = host.messageText.toLowerCase(java.util.Locale.getDefault());
        int idx = 0;
        while ((idx = lt.indexOf(lq, idx)) != -1) {
            int end = idx + lq.length();
            highlightPathScratch.reset();
            host.textLayout.getSelectionPath(idx, end, highlightPathScratch);
            canvas.drawPath(highlightPathScratch, host.searchHighlightPaint);
            idx = end;
        }
    }
}
