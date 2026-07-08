package com.callx.app.conversation.canvas;

import android.graphics.Canvas;

/**
 * Draws the reply-preview strip (quoted sender name + quoted text + optional
 * thumbnail) above the bubble content — mirrors {@code ll_reply_preview} in
 * {@code item_message_sent/received.xml}.
 *
 * Extracted from MessageBubbleCanvasView (feature-based split, no behaviour
 * change). Called from {@code drawBubbleContent()} when {@code host.hasReply}.
 */
final class ReplyPreviewRenderer {

    private final MessageBubbleCanvasView host;

    ReplyPreviewRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas) {
        float r = MessageBubbleCanvasView.REPLY_CORNER_RADIUS_DP * host.density;
        canvas.drawRoundRect(host.replyBoxRect, r, r, host.replyBgPaint);

        int replyBar = Math.round(MessageBubbleCanvasView.REPLY_BAR_WIDTH_DP * host.density);
        canvas.drawRect(host.replyBoxRect.left, host.replyBoxRect.top,
                host.replyBoxRect.left + replyBar, host.replyBoxRect.bottom,
                host.replyBarPaint);

        int replyPadH   = Math.round(MessageBubbleCanvasView.REPLY_PADDING_H_DP * host.density);
        int replyPadV   = Math.round(MessageBubbleCanvasView.REPLY_PADDING_V_DP * host.density);
        float textColLeft = host.replyBoxRect.left + replyBar + replyPadH;
        float totalTextH  = (host.replySenderLayout != null ? host.replySenderLayout.getHeight() : 0)
                          + (host.replyTextLayout   != null ? host.replyTextLayout.getHeight()   : 0);
        float textColTop  = host.replyBoxRect.top
                + (host.replyBoxRect.height() - totalTextH) / 2f;

        canvas.save();
        canvas.translate(textColLeft,
                Math.max(textColTop, host.replyBoxRect.top + replyPadV));
        if (host.replySenderLayout != null) {
            host.replySenderLayout.draw(canvas);
            canvas.translate(0, host.replySenderLayout.getHeight());
        }
        if (host.replyTextLayout != null) {
            host.replyTextLayout.draw(canvas);
        }
        canvas.restore();

        if (host.replyThumb != null) {
            int thumbSize   = Math.round(MessageBubbleCanvasView.REPLY_THUMB_SIZE_DP   * host.density);
            int thumbMargin = Math.round(MessageBubbleCanvasView.REPLY_THUMB_MARGIN_DP * host.density);
            float thumbLeft = host.replyBoxRect.right - thumbMargin - thumbSize;
            float thumbTop  = host.replyBoxRect.top
                    + (host.replyBoxRect.height() - thumbSize) / 2f;
            host.replyThumbSrcRect.set(0, 0,
                    host.replyThumb.getWidth(), host.replyThumb.getHeight());
            host.replyThumbDstRect.set(thumbLeft, thumbTop,
                    thumbLeft + thumbSize, thumbTop + thumbSize);
            canvas.drawBitmap(host.replyThumb,
                    host.replyThumbSrcRect, host.replyThumbDstRect, null);
        }
    }
}
