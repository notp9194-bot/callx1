package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Draws the call-entry pill — mirrors {@code item_call_entry_bubble.xml}: a
 * small rounded pill containing a call-type icon, label, centre-dot separator,
 * and time string. Bubbleless (no chat-bubble background).
 *
 * Extracted from MessageBubbleCanvasView (feature-based split, no behaviour
 * change). Called from {@code drawBubbleContent()} when {@code host.isCallEntry}.
 */
final class CallEntryRenderer {

    private final MessageBubbleCanvasView host;

    CallEntryRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas) {
        float r = MessageBubbleCanvasView.SEEN_CARD_CORNER_DP * host.density;
        canvas.drawRoundRect(host.callEntryPillRect, r, r, host.callEntryBgPaint);

        float padH = MessageBubbleCanvasView.CALL_ENTRY_PAD_H_DP          * host.density;
        float gap  = MessageBubbleCanvasView.CALL_ENTRY_ICON_LABEL_GAP_DP * host.density;
        float left = host.callEntryPillRect.left + padH;

        Paint.FontMetrics cifm = host.callEntryIconPaint.getFontMetrics();
        Paint.FontMetrics clfm = host.callEntryLabelPaint.getFontMetrics();
        Paint.FontMetrics cdfm = host.callEntryDotPaint.getFontMetrics();
        Paint.FontMetrics ctfm = host.callEntryTimePaint.getFontMetrics();
        float rowCenterY = host.callEntryPillRect.centerY();

        float x = left;
        canvas.drawText(host.callEntryIcon, x,
                rowCenterY - (cifm.ascent + cifm.descent) / 2f, host.callEntryIconPaint);
        x += host.callEntryIconPaint.measureText(host.callEntryIcon) + gap;
        canvas.drawText(host.callEntryLabel, x,
                rowCenterY - (clfm.ascent + clfm.descent) / 2f, host.callEntryLabelPaint);
        x += host.callEntryLabelPaint.measureText(host.callEntryLabel);
        canvas.drawText(MessageBubbleCanvasView.CALL_ENTRY_DOT_TEXT, x,
                rowCenterY - (cdfm.ascent + cdfm.descent) / 2f, host.callEntryDotPaint);
        x += host.callEntryDotPaint.measureText(MessageBubbleCanvasView.CALL_ENTRY_DOT_TEXT);
        canvas.drawText(host.callEntryTime, x,
                rowCenterY - (ctfm.ascent + ctfm.descent) / 2f, host.callEntryTimePaint);
    }
}
