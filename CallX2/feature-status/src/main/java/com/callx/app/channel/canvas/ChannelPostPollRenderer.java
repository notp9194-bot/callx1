package com.callx.app.channel.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the poll card for a channel post:
 *   • Question text (StaticLayout, bold)
 *   • N option rows — each a rounded-rect track with a fill-bar (% done),
 *     option label, and percentage label
 *   • Leading-option highlighted with a gold accent stroke
 *   • Vote count footer + expiry label
 *   • Touch hit-testing via hitTestOption(x, y)
 *
 * All StaticLayouts and RectFs are allocated in layout() and reused by draw().
 */
final class ChannelPostPollRenderer {

    private final ChannelPostCanvasView host;

    ChannelPostPollRenderer(ChannelPostCanvasView host) {
        this.host = host;
    }

    // Geometry
    private float cardLeft, cardRight;
    private float questionTop;
    private StaticLayout questionLayout;
    private String lastQuestion;
    private int lastQuestionW = -1;

    private final List<RectF>  optionTrackRects = new ArrayList<>();
    private final List<RectF>  optionFillRects  = new ArrayList<>();
    private final List<String> optionLabels     = new ArrayList<>();
    private final List<String> pctLabels        = new ArrayList<>();
    private float voteCountBaselineY;
    private String voteCountText;
    private float expiryBaselineY   = -1f;
    private String expiryText;
    private float pollCardBottom;
    private int leadingOptionIdx = -1;
    private boolean isPollClosed;

    /**
     * Compute all poll geometry. Returns the bottom y of the poll card.
     */
    float layout(float left, float top, float right,
                 List<String> options, int myVotedOption,
                 int totalVotes, long pollExpiresAt, boolean multiSelect) {
        cardLeft  = left;
        cardRight = right;
        float contentW = right - left;
        float y = top;

        // Question
        String q = host.pollQuestion != null ? host.pollQuestion : "";
        int qW = (int) Math.max(1f, contentW);
        if (questionLayout == null || !q.equals(lastQuestion) || qW != lastQuestionW) {
            TextPaint qp = host.pollQuestionPaint;
            questionLayout = StaticLayout.Builder
                    .obtain(q, 0, q.length(), qp, qW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build();
            lastQuestion  = q;
            lastQuestionW = qW;
        }
        questionTop = y;
        y += questionLayout.getHeight() + host.pollQuestionGap;

        // Options
        optionTrackRects.clear();
        optionFillRects.clear();
        optionLabels.clear();
        pctLabels.clear();

        int maxVotes = 0;
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                int v = totalVotes > 0 ? countVotesForOption(i) : 0;
                if (v > maxVotes) { maxVotes = v; leadingOptionIdx = i; }
            }
        }
        isPollClosed = pollExpiresAt > 0 && System.currentTimeMillis() > pollExpiresAt;

        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                RectF track = new RectF(left, y, right, y + host.pollOptionHeight);
                optionTrackRects.add(track);

                int votes = totalVotes > 0 ? countVotesForOption(i) : 0;
                float pct = totalVotes > 0 ? (votes * 100f / totalVotes) : 0f;
                float fillW = Math.max(0f, (right - left) * pct / 100f);
                optionFillRects.add(new RectF(left, y, left + fillW, y + host.pollOptionHeight));

                optionLabels.add(options.get(i));
                pctLabels.add(totalVotes > 0 ? Math.round(pct) + "%" : "");
                y += host.pollOptionHeight + host.pollOptionGap;
            }
        }

        y += host.pollQuestionGap;

        // Vote count
        voteCountText = totalVotes == 0 ? "Be the first to vote"
                : totalVotes + " vote" + (totalVotes == 1 ? "" : "s");
        Paint.FontMetrics vcfm = host.pollVoteCountPaint.getFontMetrics();
        voteCountBaselineY = y - vcfm.ascent;
        y += vcfm.descent - vcfm.ascent;

        // Expiry
        expiryBaselineY = -1f;
        if (pollExpiresAt > 0) {
            y += host.pollOptionGap;
            Paint.FontMetrics efm = host.pollExpiryPaint.getFontMetrics();
            long remaining = pollExpiresAt - System.currentTimeMillis();
            expiryText = remaining > 0
                    ? "Closes in " + formatRemaining(remaining)
                    : "Poll closed";
            expiryBaselineY = y - efm.ascent;
            y += efm.descent - efm.ascent;
        }

        pollCardBottom = y + host.pollOptionGap;
        return pollCardBottom;
    }

    void draw(Canvas canvas, int myVotedOption) {
        if (questionLayout == null) return;

        // Question
        canvas.save();
        canvas.translate(cardLeft, questionTop);
        questionLayout.draw(canvas);
        canvas.restore();

        // Option rows
        float optR = host.pollOptionCornerRadius;
        for (int i = 0; i < optionTrackRects.size(); i++) {
            RectF track = optionTrackRects.get(i);
            RectF fill  = optionFillRects.get(i);
            boolean isVoted   = (i == myVotedOption);
            boolean isLeading = (i == leadingOptionIdx) && optionFillRects.size() > 1;

            // Track background
            Paint trackPaint = isVoted ? host.pollTrackVotedPaint : host.pollTrackPaint;
            canvas.drawRoundRect(track, optR, optR, trackPaint);

            // Fill bar
            if (fill.width() > 0) {
                Paint fillPaint = isLeading ? host.pollFillLeadingPaint : host.pollFillPaint;
                canvas.save();
                canvas.clipRect(track);
                canvas.drawRoundRect(fill, optR, optR, fillPaint);
                canvas.restore();
            }

            // Leading option stroke
            if (isLeading) {
                canvas.drawRoundRect(track, optR, optR, host.pollLeaderStrokePaint);
            }

            // Option text
            float textX = track.left + host.pollOptionTextPad;
            float textY = track.centerY() - (host.pollOptionPaint.ascent()
                    + host.pollOptionPaint.descent()) / 2f;
            canvas.drawText(optionLabels.get(i), textX, textY, host.pollOptionPaint);

            // Pct text (right-aligned)
            if (!pctLabels.get(i).isEmpty()) {
                float pw = host.pollPctPaint.measureText(pctLabels.get(i));
                canvas.drawText(pctLabels.get(i),
                        track.right - host.pollOptionTextPad - pw, textY,
                        host.pollPctPaint);
            }
        }

        // Vote count
        canvas.drawText(voteCountText, cardLeft, voteCountBaselineY, host.pollVoteCountPaint);

        // Expiry
        if (expiryBaselineY >= 0 && expiryText != null) {
            Paint p = isPollClosed ? host.pollClosedPaint : host.pollExpiryPaint;
            canvas.drawText(expiryText, cardLeft, expiryBaselineY, p);
        }
    }

    /** Returns the option index at (x,y) or -1 if none. */
    int hitTestOption(float x, float y) {
        for (int i = 0; i < optionTrackRects.size(); i++) {
            if (optionTrackRects.get(i).contains(x, y)) return i;
        }
        return -1;
    }

    private int countVotesForOption(int idx) {
        if (host.pollVotes == null) return 0;
        int count = 0;
        for (Long v : host.pollVotes.values()) {
            if (v != null && v == idx) count++;
        }
        return count;
    }

    private String formatRemaining(long ms) {
        long secs = ms / 1000;
        if (secs < 60)   return secs + "s";
        long mins = secs / 60;
        if (mins < 60)   return mins + "m";
        long hours = mins / 60;
        if (hours < 24)  return hours + "h";
        return (hours / 24) + "d";
    }
}
