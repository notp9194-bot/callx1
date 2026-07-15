package com.callx.app.community.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;

import com.callx.app.community.CommunityPoll;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a poll card: question text, then one row per option — a rounded
 * track with a filled bar proportional to that option's vote percentage,
 * the option label, and the percentage. The option the current user voted
 * for (if any) gets a highlighted border, matching CommunityPollView's
 * "selected option" treatment but as one drawn card instead of inflated
 * per-option child views.
 */
final class PostPollRenderer {

    private final CommunityPostCanvasView host;
    final List<RectF> optionRects = new ArrayList<>();

    PostPollRenderer(CommunityPostCanvasView host) {
        this.host = host;
    }

    /** Lays out the question + option rows within [left,right] starting at top. Returns bottom y. */
    float layout(float left, float top, float right, CommunityPoll poll) {
        optionRects.clear();
        if (poll == null) return top;

        float y = top;
        if (poll.question != null && !poll.question.isEmpty()) {
            Paint.FontMetrics fm = host.pollQuestionPaint.getFontMetrics();
            y += (fm.descent - fm.ascent) + host.pollQuestionGap;
        }
        int count = poll.options != null ? poll.options.size() : 0;
        for (int i = 0; i < count; i++) {
            if (i > 0) y += host.pollOptionGap;
            optionRects.add(new RectF(left, y, right, y + host.pollOptionHeight));
            y += host.pollOptionHeight;
        }
        return y;
    }

    private final RectF fillRect = new RectF();

    void draw(Canvas canvas, float left, float top, float right, CommunityPoll poll, Integer myVotedIndex) {
        if (poll == null) return;
        float y = top;

        if (poll.question != null && !poll.question.isEmpty()) {
            Paint.FontMetrics fm = host.pollQuestionPaint.getFontMetrics();
            String q = TextUtils.ellipsize(poll.question, host.pollQuestionPaint, right - left, TextUtils.TruncateAt.END).toString();
            canvas.drawText(q, left, y - fm.ascent, host.pollQuestionPaint);
            y += (fm.descent - fm.ascent) + host.pollQuestionGap;
        }

        int total = poll.totalVotes();
        for (int i = 0; i < optionRects.size() && i < poll.options.size(); i++) {
            RectF row = optionRects.get(i);
            CommunityPoll.Option opt = poll.options.get(i);
            int pct = poll.percentFor(i);
            boolean selected = myVotedIndex != null && myVotedIndex == i;

            float r = row.height() / 2f;
            canvas.drawRoundRect(row, r, r, host.pollTrackPaint);

            if (total > 0 && pct > 0) {
                fillRect.set(row.left, row.top, row.left + row.width() * (pct / 100f), row.bottom);
                if (fillRect.width() < row.height()) fillRect.right = row.left + row.height();
                canvas.drawRoundRect(fillRect, r, r, selected ? host.pollFillSelectedPaint : host.pollFillPaint);
            }

            if (selected) {
                canvas.drawRoundRect(row, r, r, host.pollSelectedBorderPaint);
            }

            float textPadding = host.pollOptionTextPadding;
            String pctLabel = total > 0 ? pct + "%" : "";
            float pctW = host.pollPercentPaint.measureText(pctLabel);
            float labelMaxW = Math.max(1f, row.width() - textPadding * 2 - pctW - textPadding);
            String label = opt.text != null ? opt.text : "";
            String ellipsized = TextUtils.ellipsize(label, host.pollOptionPaint, labelMaxW, TextUtils.TruncateAt.END).toString();

            Paint.FontMetrics ofm = host.pollOptionPaint.getFontMetrics();
            canvas.drawText(ellipsized, row.left + textPadding, row.centerY() - (ofm.ascent + ofm.descent) / 2f, host.pollOptionPaint);

            if (!pctLabel.isEmpty()) {
                Paint.FontMetrics pfm = host.pollPercentPaint.getFontMetrics();
                canvas.drawText(pctLabel, row.right - textPadding - pctW,
                        row.centerY() - (pfm.ascent + pfm.descent) / 2f, host.pollPercentPaint);
            }
        }
    }

    /** Returns the tapped option index, or -1. */
    int hitTestOption(float x, float y) {
        for (int i = 0; i < optionRects.size(); i++) {
            if (optionRects.get(i).contains(x, y)) return i;
        }
        return -1;
    }
}
