package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Draws one of the 3 view-once bubble variants — mirrors
 * item_view_once_bubble.xml / item_view_once_sent_waiting.xml /
 * item_view_once_expired.xml: a rounded solid-colour card (own colour per
 * state, drawn directly instead of via bubbleDrawable), an icon glyph
 * beside a label(+sublabel) column, and a small bottom-end timestamp.
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file split,
 * no behavior change) — bind/measure/touch logic for the view-once bubble
 * stays on the host view; this class only owns the draw() call.
 */
final class ViewOnceRenderer {

    private final MessageBubbleCanvasView host;

    ViewOnceRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas) {
        float r = MessageBubbleCanvasView.VO_CORNER_RADIUS_DP * host.density;
        int bg;
        float pad;
        switch (host.viewOnceVariant) {
            case MessageBubbleCanvasView.VIEW_ONCE_WAITING:
                bg = MessageBubbleCanvasView.VO_COLOR_WAITING;
                pad = MessageBubbleCanvasView.VO_PAD_WAITING_DP * host.density;
                break;
            case MessageBubbleCanvasView.VIEW_ONCE_EXPIRED:
                bg = MessageBubbleCanvasView.VO_COLOR_EXPIRED;
                pad = MessageBubbleCanvasView.VO_PAD_EXPIRED_DP * host.density;
                break;
            default:
                bg = MessageBubbleCanvasView.VO_COLOR_RECEIVED;
                pad = MessageBubbleCanvasView.VO_PAD_RECEIVED_DP * host.density;
                break;
        }
        host.viewOnceBgPaint.setColor(bg);
        canvas.drawRoundRect(host.viewOnceCardRect, r, r, host.viewOnceBgPaint);

        float left = host.viewOnceCardRect.left + pad;
        float right = host.viewOnceCardRect.right - pad;
        float top = host.viewOnceCardRect.top + pad;

        Paint.FontMetrics iconFm = host.viewOnceIconPaint.getFontMetrics();
        float iconH = iconFm.descent - iconFm.ascent;

        if (host.viewOnceVariant == MessageBubbleCanvasView.VIEW_ONCE_RECEIVED) {
            Paint.FontMetrics lfm = host.viewOnceLabelPaint.getFontMetrics();
            Paint.FontMetrics sfm = host.viewOnceSublabelPaint.getFontMetrics();
            float labelH = lfm.descent - lfm.ascent;
            float sublabelH = sfm.descent - sfm.ascent;
            float textColH = labelH + MessageBubbleCanvasView.VO_LABEL_SUBLABEL_GAP_DP * host.density + sublabelH;
            float rowH = Math.max(iconH, textColH);
            float rowCenterY = top + rowH / 2f;

            canvas.drawText(MessageBubbleCanvasView.VO_LOCK_GLYPH, left, rowCenterY - (iconFm.ascent + iconFm.descent) / 2f, host.viewOnceIconPaint);
            float textX = left + host.viewOnceIconPaint.measureText(MessageBubbleCanvasView.VO_LOCK_GLYPH) + MessageBubbleCanvasView.VO_ICON_TEXT_GAP_DP * host.density;
            float blockTop = rowCenterY - textColH / 2f;
            canvas.drawText(MessageBubbleCanvasView.VO_LABEL_TEXT, textX, blockTop - lfm.ascent, host.viewOnceLabelPaint);
            if (!host.viewOnceSublabel.isEmpty()) {
                canvas.drawText(host.viewOnceSublabel, textX, blockTop + labelH + MessageBubbleCanvasView.VO_LABEL_SUBLABEL_GAP_DP * host.density - sfm.ascent,
                        host.viewOnceSublabelPaint);
            }
        } else if (host.viewOnceVariant == MessageBubbleCanvasView.VIEW_ONCE_WAITING) {
            Paint.FontMetrics lfm = host.viewOnceLabelPaint.getFontMetrics();
            float labelH = lfm.descent - lfm.ascent;
            float rowH = Math.max(iconH, labelH);
            float rowCenterY = top + rowH / 2f;
            canvas.drawText(MessageBubbleCanvasView.VO_LOCK_GLYPH, left, rowCenterY - (iconFm.ascent + iconFm.descent) / 2f, host.viewOnceIconPaint);
            float textX = left + host.viewOnceIconPaint.measureText(MessageBubbleCanvasView.VO_LOCK_GLYPH) + MessageBubbleCanvasView.VO_ICON_TEXT_GAP_DP * host.density;
            canvas.drawText(MessageBubbleCanvasView.VO_WAITING_LABEL_TEXT, textX, rowCenterY - (lfm.ascent + lfm.descent) / 2f, host.viewOnceLabelPaint);
        } else { // VIEW_ONCE_EXPIRED
            Paint.FontMetrics lfm = host.viewOnceLabelPaint.getFontMetrics();
            float labelH = lfm.descent - lfm.ascent;
            float rowH = Math.max(iconH, labelH);
            float rowCenterY = top + rowH / 2f;
            canvas.drawText(MessageBubbleCanvasView.VO_EYE_GLYPH, left, rowCenterY - (iconFm.ascent + iconFm.descent) / 2f, host.viewOnceIconPaint);
            float textX = left + host.viewOnceIconPaint.measureText(MessageBubbleCanvasView.VO_EYE_GLYPH) + MessageBubbleCanvasView.VO_ICON_TEXT_GAP_DP * host.density;
            canvas.drawText(host.viewOnceExpiredLabel, textX, rowCenterY - (lfm.ascent + lfm.descent) / 2f, host.viewOnceLabelPaint);

            if (host.viewOnceShowOpenedAt && !host.viewOnceOpenedAtText.isEmpty()) {
                Paint openedAtPaint = host.viewOnceSublabelPaint;
                float savedSize = openedAtPaint.getTextSize();
                int savedColor = openedAtPaint.getColor();
                openedAtPaint.setTextSize(MessageBubbleCanvasView.VO_OPENED_AT_SP * host.density);
                openedAtPaint.setColor(MessageBubbleCanvasView.VO_OPENED_AT_COLOR);
                Paint.FontMetrics ofm = openedAtPaint.getFontMetrics();
                float openedAtTop = top + rowH + MessageBubbleCanvasView.VO_OPENED_AT_GAP_DP * host.density;
                canvas.drawText(host.viewOnceOpenedAtText, textX, openedAtTop - ofm.ascent, openedAtPaint);
                openedAtPaint.setTextSize(savedSize);
                openedAtPaint.setColor(savedColor);
            }
        }

        Paint.FontMetrics tfm = host.viewOnceTimePaint.getFontMetrics();
        float timeBaselineY = host.viewOnceCardRect.bottom - pad - tfm.descent;
        canvas.drawText(host.footerTimeText, right, timeBaselineY, host.viewOnceTimePaint);
    }
}
