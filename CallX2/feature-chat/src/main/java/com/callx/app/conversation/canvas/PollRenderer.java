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

    // PERF (ultra-opt pass): reused every draw() instead of `new RectF()` —
    // was one allocation per poll bubble, every single frame during scroll.
    private final RectF chipRect = new RectF();

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
        // PERF: was host.pollHeaderLabelPaint.getFontMetrics() (no-arg) —
        // allocates a fresh FontMetrics every draw() call, i.e. every scroll
        // frame a poll bubble is on screen. Reuse the same scratch instance
        // onMeasure() already populates for this Paint.
        host.pollHeaderLabelPaint.getFontMetrics(host.pollHeaderFmScratch);
        Paint.FontMetrics hfm = host.pollHeaderFmScratch;
        float labelBaseline = top + host.pollHeaderRowH / 2f - (hfm.ascent + hfm.descent) / 2f;
        canvas.drawText("POLL", left + iconSz + iconMar, labelBaseline, host.pollHeaderLabelPaint);

        // Status chip (CLOSED or LIVE)
        String chipText = host.pollClosed ? "CLOSED" : "LIVE";
        float chipTxtW  = host.pollChipPaint.measureText(chipText);
        float chipPadH  = MessageBubbleCanvasView.POLL_CHIP_PAD_H_DP * host.density;
        float chipPadV  = MessageBubbleCanvasView.POLL_CHIP_PAD_V_DP * host.density;
        float chipW     = chipTxtW + chipPadH * 2;
        // PERF: was two separate host.pollChipPaint.getFontMetrics() calls
        // (one for chipH, one below for chipBaseline) — 2 fresh allocations
        // per frame for a single unchanging Paint. Fetched once into a
        // scratch instance and read twice.
        host.pollChipPaint.getFontMetrics(host.pollChipFmScratch);
        Paint.FontMetrics cfm = host.pollChipFmScratch;
        float chipH     = cfm.descent - cfm.ascent + chipPadV * 2;
        float chipLeft  = right - chipW;
        float chipTop   = top + (host.pollHeaderRowH - chipH) / 2f;
        host.pollOptionBgPaint.setColor(host.pollClosed ? MessageBubbleCanvasView.POLL_CHIP_CLOSED_BG : MessageBubbleCanvasView.POLL_CHIP_NEUTRAL_BG);
        RectF chipRect = this.chipRect;
        chipRect.set(chipLeft, chipTop, right, chipTop + chipH);
        float chipR = MessageBubbleCanvasView.POLL_CHIP_CORNER_DP * host.density;
        canvas.drawRoundRect(chipRect, chipR, chipR, host.pollOptionBgPaint);
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
        // PERF: was host.pollSubtitlePaint.getFontMetrics() (no-arg) —
        // reuse the same scratch instance onMeasure() populates.
        host.pollSubtitlePaint.getFontMetrics(host.pollSubtitleFmScratch);
        Paint.FontMetrics sfm = host.pollSubtitleFmScratch;
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

        // ── PERF: reuse RectF objects instead of clear()+new every draw() ──
        // draw() runs on every onDraw() (i.e. every scroll frame a poll
        // bubble is on-screen), not just on measure/bind. The old
        // clear()+add(new RectF(...)) allocated n fresh RectF objects per
        // frame per visible poll — real GC churn during a fling with
        // several polls on screen. host.pollOptionRects now keeps one RectF
        // per option and this just mutates them in place; the list is only
        // grown/shrunk (never per-frame-reallocated) when the option count
        // itself changes, which bindPoll() already gates behind a
        // requestLayoutIfSizeChanged() check.
        while (host.pollOptionRects.size() < n) host.pollOptionRects.add(new RectF());
        while (host.pollOptionRects.size() > n) host.pollOptionRects.remove(host.pollOptionRects.size() - 1);

        // PERF: pollOptionPctPaint's metrics are the same for every option
        // row (same text size/typeface) — fetch once here instead of once
        // per option inside the loop below (was n redundant calls, now 1).
        host.pollOptionPctPaint.getFontMetrics(host.pollOptionPctFmScratch);
        Paint.FontMetrics pfm = host.pollOptionPctFmScratch;

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
                // COLOR: leading option now always gets the accent fill (was
                // only shown when it was also *your* vote — most votes were
                // rendered in the same flat indigo as everything else, so
                // the leader was invisible at a glance).
                int fillColor = isLeader ? MessageBubbleCanvasView.POLL_OPTION_FILL_LEADER
                        : (isMyVote ? MessageBubbleCanvasView.POLL_OPTION_FILL_COLOR : MessageBubbleCanvasView.POLL_OPTION_FILL_NEUTRAL);
                host.pollFillPaint.setColor(fillColor);
                // PERF: reuse host's scratch RectF/Path instead of `new` per
                // option per frame (was 2 allocations × option-count × every
                // draw() call while any poll bubble was on screen/fling).
                host.pollFillRectScratch.set(left, rowTop, left + fillW, rowTop + rowH);
                canvas.save();
                host.pollFillClipPath.reset();
                host.pollFillClipPath.addRoundRect(rowRect, optCorner, optCorner, android.graphics.Path.Direction.CW);
                canvas.clipPath(host.pollFillClipPath);
                canvas.drawRect(host.pollFillRectScratch, host.pollFillPaint);
                canvas.restore();
            }

            // Stroke — COLOR: leading option gets its own green accent
            // border (still overridden by the voted-green when it's also
            // your vote, so a mine+leading option isn't drawn twice-green
            // in two slightly different shades).
            if (isMyVote) {
                host.pollStrokePaint.setColor(MessageBubbleCanvasView.POLL_VOTED_STROKE_COLOR);
            } else if (isLeader) {
                host.pollStrokePaint.setColor(MessageBubbleCanvasView.POLL_LEADER_STROKE_COLOR);
            } else {
                host.pollStrokePaint.setColor(MessageBubbleCanvasView.POLL_STROKE_COLOR);
            }
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
            // PERF: was host.pollOptionPctPaint.getFontMetrics() (no-arg)
            // called fresh *inside* this loop — n allocations per draw()
            // call for an n-option poll, every scroll frame. Now reuses
            // `pfm`, fetched once above the loop.
            float pctBaseline = rowTop + rowH / 2f - (pfm.ascent + pfm.descent) / 2f;
            canvas.drawText(pctStr, right - optPadH, pctBaseline, host.pollOptionPctPaint);

            rowTop += rowH + optGap;
        }

        // ── Votes footer ─────────────────────────────────────────────────────
        rowTop -= optGap; // remove last gap
        float footerGap = MessageBubbleCanvasView.POLL_FOOTER_GAP_DP * host.density;
        String footerStr = host.pollTotal == 1 ? "1 vote" : host.pollTotal + " votes";
        // PERF: was host.pollFooterPaint.getFontMetrics() (no-arg) — reuse
        // the same scratch instance onMeasure() populates.
        host.pollFooterPaint.getFontMetrics(host.pollFooterFmScratch);
        Paint.FontMetrics ffm = host.pollFooterFmScratch;
        float footerBaseline = rowTop + footerGap + (-ffm.ascent);
        canvas.drawText(footerStr, left, footerBaseline, host.pollFooterPaint);
        // Voter avatars (group, non-anonymous) — right end of the same footer row.
        host.drawPollVoters(canvas, right, footerBaseline + (ffm.ascent + ffm.descent) / 2f);

        // ── Timestamp / tick footer ─────────────────────────────────────────
        int    fvPad = Math.round(MessageBubbleCanvasView.V_PADDING_DP * host.density);
        host.drawFooter(canvas, host.bubbleRect.bottom - fvPad * 0.4f, host.bubbleRect.right - hPad);
    }
}
