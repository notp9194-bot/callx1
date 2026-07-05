package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.StaticLayout;

/**
 * Draws the poll card inside the normal bubble background.
 * Layout (top-to-bottom inside bubble's vPad):
 *   POLL icon + "POLL" label + chip  — header row
 *   question StaticLayout
 *   subtitle ("Select one/more answer")
 *   N option rows (fill bar + icon + text + pct%)
 *   total-votes footer
 *   timestamp/tick footer (drawn by host.drawFooter below)
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file split,
 * no behavior change) — bind/measure/touch logic for the poll card stays
 * on the host view; this class only owns the draw() call.
 */
final class PollRenderer {

    private final MessageBubbleCanvasView host;

    PollRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas) {
        float padH   = MessageBubbleCanvasView.POLL_PADDING_H_DP * host.density;
        float padTop = MessageBubbleCanvasView.POLL_PADDING_TOP_DP * host.density;
        float vPad   = MessageBubbleCanvasView.V_PADDING_DP * host.density;
        int   hPad   = Math.round(MessageBubbleCanvasView.H_PADDING_DP * host.density);

        float left  = host.bubbleLeft + hPad + padH;
        float right = host.bubbleLeft + host.bubbleRect.width() - hPad - padH;
        float top   = host.bubbleTop + vPad + padTop;

        // ── Header row: poll icon dot + "POLL" label + status chip ──────────
        float iconSz  = MessageBubbleCanvasView.POLL_HEADER_ICON_SIZE_DP * host.density;
        float iconMar = MessageBubbleCanvasView.POLL_HEADER_ICON_MARGIN_DP * host.density;

        // Draw a small filled circle as the poll icon
        host.pollOptionBgPaint.setColor(MessageBubbleCanvasView.POLL_HEADER_ICON_COLOR);
        canvas.drawCircle(left + iconSz / 2f, top + host.pollHeaderRowH / 2f, iconSz / 2f * 0.7f, host.pollOptionBgPaint);

        // "POLL" label
        Paint.FontMetrics hfm = host.pollHeaderLabelPaint.getFontMetrics();
        float labelBaseline = top + host.pollHeaderRowH / 2f - (hfm.ascent + hfm.descent) / 2f;
        canvas.drawText("POLL", left + iconSz + iconMar, labelBaseline, host.pollHeaderLabelPaint);

        // Status chip (CLOSED or LIVE)
        String chipText = host.pollClosed ? "CLOSED" : "LIVE";
        float chipTxtW  = host.pollChipPaint.measureText(chipText);
        float chipPadH  = MessageBubbleCanvasView.POLL_CHIP_PAD_H_DP * host.density;
        float chipPadV  = MessageBubbleCanvasView.POLL_CHIP_PAD_V_DP * host.density;
        float chipW     = chipTxtW + chipPadH * 2;
        float chipH     = host.pollChipPaint.getFontMetrics().descent - host.pollChipPaint.getFontMetrics().ascent + chipPadV * 2;
        float chipLeft  = right - chipW;
        float chipTop   = top + (host.pollHeaderRowH - chipH) / 2f;
        host.pollOptionBgPaint.setColor(host.pollClosed ? MessageBubbleCanvasView.POLL_CHIP_CLOSED_BG : MessageBubbleCanvasView.POLL_CHIP_NEUTRAL_BG);
        RectF chipRect = host.pollChipRect;
        chipRect.set(chipLeft, chipTop, right, chipTop + chipH);
        float chipR = MessageBubbleCanvasView.POLL_CHIP_CORNER_DP * host.density;
        canvas.drawRoundRect(chipRect, chipR, chipR, host.pollOptionBgPaint);
        Paint.FontMetrics cfm = host.pollChipPaint.getFontMetrics();
        float chipBaseline = chipTop + chipH / 2f - (cfm.ascent + cfm.descent) / 2f;
        canvas.drawText(chipText, chipLeft + chipPadH, chipBaseline, host.pollChipPaint);

        // ── Question ────────────────────────────────────────────────────────
        float qTop = top + host.pollHeaderRowH + MessageBubbleCanvasView.POLL_QUESTION_GAP_DP * host.density;
        if (host.pollQuestionLayout != null) {
            canvas.save();
            canvas.translate(left, qTop);
            host.pollQuestionLayout.draw(canvas);
            canvas.restore();
        }

        // ── Subtitle ─────────────────────────────────────────────────────────
        float qHeight = host.pollQuestionLayout != null ? host.pollQuestionLayout.getHeight() : 0f;
        float subTop  = qTop + qHeight + MessageBubbleCanvasView.POLL_SUBTITLE_GAP_DP * host.density;
        String subtitle = host.pollClosed ? "Poll closed" : (host.pollMultiChoice ? "Select one or more answers" : "Select one answer");
        Paint.FontMetrics sfm = host.pollSubtitlePaint.getFontMetrics();
        float subBaseline = subTop - sfm.ascent;
        canvas.drawText(subtitle, left, subBaseline, host.pollSubtitlePaint);

        // ── Option rows ──────────────────────────────────────────────────────
        float optPadH  = MessageBubbleCanvasView.POLL_OPTION_PAD_H_DP * host.density;
        float optPadV  = MessageBubbleCanvasView.POLL_OPTION_PAD_V_DP * host.density;
        float optCorner = MessageBubbleCanvasView.POLL_OPTION_CORNER_DP * host.density;
        float optIconSz = MessageBubbleCanvasView.POLL_OPTION_ICON_SIZE_DP * host.density;
        float optIconMar = MessageBubbleCanvasView.POLL_OPTION_ICON_MARGIN_DP * host.density;
        float optGap   = MessageBubbleCanvasView.POLL_OPTION_GAP_DP * host.density;
        float innerW   = right - left;

        float rowTop = subTop + host.pollSubtitleH + MessageBubbleCanvasView.POLL_OPTIONS_GAP_DP * host.density;
        int n = host.pollOptions.length;
        // Keep pollOptionRects sized to the option count, reusing the same
        // RectF objects across frames (touch-hit-testing reads this list
        // too) instead of clear()+`new RectF()` per option per draw() —
        // only grows/shrinks the list when the option count itself changes.
        while (host.pollOptionRects.size() < n) host.pollOptionRects.add(new RectF());
        while (host.pollOptionRects.size() > n) host.pollOptionRects.remove(host.pollOptionRects.size() - 1);
        // Compute fill widths
        for (int i = 0; i < n; i++) {
            float pct = (host.pollTotal > 0) ? (host.pollCounts[i] * 1f / host.pollTotal) : 0f;
            host.pollFillWidths[i] = innerW * pct;
        }

        for (int i = 0; i < n; i++) {
            StaticLayout optLayout = (i < host.pollOptionLayouts.length) ? host.pollOptionLayouts[i] : null;
            float txtH = optLayout != null ? optLayout.getHeight() : 0f;
            float rowH = Math.max(txtH, optIconSz) + optPadV * 2;
            RectF rowRect = host.pollOptionRects.get(i);
            rowRect.set(left, rowTop, right, rowTop + rowH);

            boolean isMyVote = (i < host.pollMyVote.length) && host.pollMyVote[i];
            boolean isLeader = (i < host.pollIsLeader.length) && host.pollIsLeader[i];

            // Option background (stroke)
            host.pollOptionBgPaint.setColor(isMyVote ? MessageBubbleCanvasView.POLL_OPTION_VOTED_BG : MessageBubbleCanvasView.POLL_OPTION_BG);
            canvas.drawRoundRect(rowRect, optCorner, optCorner, host.pollOptionBgPaint);

            // Fill bar (progress)
            float fillW = (i < host.pollFillWidths.length) ? host.pollFillWidths[i] : 0f;
            if (fillW > 0f) {
                int fillColor = isMyVote ? (isLeader ? MessageBubbleCanvasView.POLL_OPTION_FILL_LEADER : MessageBubbleCanvasView.POLL_OPTION_FILL_COLOR) : MessageBubbleCanvasView.POLL_OPTION_FILL_NEUTRAL;
                host.pollFillPaint.setColor(fillColor);
                RectF fillRect = host.pollFillRect;
                fillRect.set(left, rowTop, left + fillW, rowTop + rowH);
                // Clip fill to row's rounded rect path
                canvas.save();
                android.graphics.Path fp = host.pollFillClipPath;
                fp.reset();
                fp.addRoundRect(rowRect, optCorner, optCorner, android.graphics.Path.Direction.CW);
                canvas.clipPath(fp);
                canvas.drawRect(fillRect, host.pollFillPaint);
                canvas.restore();
            }

            // Stroke
            host.pollStrokePaint.setColor(isMyVote ? MessageBubbleCanvasView.POLL_VOTED_STROKE_COLOR : MessageBubbleCanvasView.POLL_STROKE_COLOR);
            canvas.drawRoundRect(rowRect, optCorner, optCorner, host.pollStrokePaint);

            // Radio / check icon (simple circle outline for radio, filled dot for selected)
            float iconCx = left + optPadH + optIconSz / 2f;
            float iconCy = rowTop + rowH / 2f;
            host.pollOptionBgPaint.setColor(isMyVote ? MessageBubbleCanvasView.POLL_VOTED_STROKE_COLOR : MessageBubbleCanvasView.POLL_STROKE_COLOR);
            if (isMyVote) {
                canvas.drawCircle(iconCx, iconCy, optIconSz / 2f * 0.85f, host.pollOptionBgPaint);
                host.pollOptionBgPaint.setColor(MessageBubbleCanvasView.POLL_OPTION_VOTED_BG);
                canvas.drawCircle(iconCx, iconCy, optIconSz / 2f * 0.45f, host.pollOptionBgPaint);
            } else {
                host.pollStrokePaint.setColor(MessageBubbleCanvasView.POLL_STROKE_COLOR);
                canvas.drawCircle(iconCx, iconCy, optIconSz / 2f * 0.85f, host.pollStrokePaint);
            }

            // Option text
            if (optLayout != null) {
                float txtTop = rowTop + (rowH - txtH) / 2f;
                canvas.save();
                canvas.translate(left + optPadH + optIconSz + optIconMar, txtTop);
                optLayout.draw(canvas);
                canvas.restore();
            }

            // Percentage label
            int pct = host.pollTotal > 0 ? Math.round(host.pollCounts[i] * 100f / host.pollTotal) : 0;
            String pctStr = pct + "%";
            Paint.FontMetrics pfm = host.pollOptionPctPaint.getFontMetrics();
            float pctBaseline = rowTop + rowH / 2f - (pfm.ascent + pfm.descent) / 2f;
            canvas.drawText(pctStr, right - optPadH, pctBaseline, host.pollOptionPctPaint);

            rowTop += rowH + optGap;
        }

        // ── Votes footer ─────────────────────────────────────────────────────
        rowTop -= optGap; // remove last gap
        float footerGap = MessageBubbleCanvasView.POLL_FOOTER_GAP_DP * host.density;
        String footerStr = host.pollTotal == 1 ? "1 vote" : host.pollTotal + " votes";
        Paint.FontMetrics ffm = host.pollFooterPaint.getFontMetrics();
        float footerBaseline = rowTop + footerGap + (-ffm.ascent);
        canvas.drawText(footerStr, left, footerBaseline, host.pollFooterPaint);

        // ── Timestamp / tick footer ─────────────────────────────────────────
        int    fvPad = Math.round(MessageBubbleCanvasView.V_PADDING_DP * host.density);
        host.drawFooter(canvas, host.bubbleRect.bottom - fvPad * 0.4f, host.bubbleRect.right - hPad);
    }
}
