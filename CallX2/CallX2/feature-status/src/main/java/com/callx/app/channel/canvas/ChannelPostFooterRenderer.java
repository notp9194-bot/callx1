package com.callx.app.channel.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws the post card footer:
 *   • Reaction emoji chips row (emoji + count, tappable → detail)
 *   • Views count  ("1.2K views")
 *   • Forwards count ("45 forwards")
 *   • Replies/comments count ("12 comments")
 *   • React button (heart icon — filled if I reacted)
 *   • Forward button (share arrow icon)
 *
 * All geometry is laid out from the bottom of the content section upward
 * (reactions first, then stats row, then action buttons row).
 * Touch hit-testing is exposed via hitTest(x,y).
 */
final class ChannelPostFooterRenderer {

    static final int REGION_NONE    = 0;
    static final int REGION_REACT   = 1;
    static final int REGION_FORWARD = 2;
    static final int REGION_REPLY   = 3;
    static final int REGION_REACTIONS_DETAIL = 4;

    private final ChannelPostCanvasView host;

    ChannelPostFooterRenderer(ChannelPostCanvasView host) {
        this.host = host;
    }

    // Geometry
    private float rowY;
    private float footerBottom;

    // Reaction chip geometry — each chip: (left, right, y-center)
    private final java.util.List<float[]> reactionChipRects = new java.util.ArrayList<>();
    private final java.util.List<String>  reactionChipTexts = new java.util.ArrayList<>();
    private final RectF reactionsGroupRect = new RectF(); // bounding box for all chips

    private final RectF reactBtnRect   = new RectF();
    private final RectF forwardBtnRect = new RectF();
    private final RectF replyBtnRect   = new RectF();

    private float viewsBaselineY;
    private float fwdBaselineY;
    private float replyBaselineY;

    // Heart path cache
    private final Path heartPath = new Path();
    private float lastHeartSize = -1f;

    // Share arrow path cache
    private final Path arrowPath = new Path();
    private float lastArrowSize = -1f;

    /**
     * Layout from top. Returns footer bottom y.
     */
    float layout(float left, float top, float right) {
        float y = top;

        // ── Reaction chips row ──────────────────────────────────────────────
        reactionChipRects.clear();
        reactionChipTexts.clear();
        reactionsGroupRect.setEmpty();

        Map<String, Integer> counts = host.getReactionCountsMap();
        if (!counts.isEmpty()) {
            float x = left;
            float chipH = host.reactionChipHeight;
            float chipY = y;
            reactionsGroupRect.set(left, y, left, y + chipH);

            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                String label = e.getValue() > 1
                        ? e.getKey() + " " + e.getValue()
                        : e.getKey();
                float chipW = host.reactionChipPadH * 2f
                        + host.reactionChipTextPaint.measureText(label);
                reactionChipRects.add(new float[]{x, x + chipW, chipY + chipH / 2f});
                reactionChipTexts.add(label);
                x += chipW + host.reactionChipGap;
                reactionsGroupRect.right = x - host.reactionChipGap;
            }
            y += chipH + host.reactionsMarginBottom;
        }

        // ── Stats row (views · forwards · replies) ──────────────────────────
        Paint.FontMetrics sfm = host.statsPaint.getFontMetrics();
        float statsH = sfm.descent - sfm.ascent;
        viewsBaselineY  = y - sfm.ascent;
        fwdBaselineY    = y - sfm.ascent;
        replyBaselineY  = y - sfm.ascent;
        y += statsH + host.statsMarginBottom;

        // ── Action buttons row (react · forward · reply) ────────────────────
        float btnSize = host.footerBtnSize;
        float btnY    = y;
        reactBtnRect  .set(left,                 btnY, left + btnSize,                 btnY + btnSize);
        forwardBtnRect.set(left + btnSize + host.footerBtnGap, btnY,
                           left + btnSize * 2f + host.footerBtnGap, btnY + btnSize);
        replyBtnRect  .set(right - btnSize,      btnY, right,                          btnY + btnSize);

        y += btnSize;
        footerBottom = y;
        return footerBottom;
    }

    void draw(Canvas canvas) {
        // ── Reaction chips ───────────────────────────────────────────────────
        for (int i = 0; i < reactionChipRects.size(); i++) {
            float[] chip = reactionChipRects.get(i);
            float chipLeft = chip[0], chipRight = chip[1];
            float chipMidY = chip[2];
            float h = host.reactionChipHeight;
            float r = h / 2f;
            RectF chipRect = new RectF(chipLeft, chipMidY - h / 2f, chipRight, chipMidY + h / 2f);
            canvas.drawRoundRect(chipRect, r, r, host.reactionChipBgPaint);
            float textX = chipLeft + host.reactionChipPadH;
            float textY = chipMidY - (host.reactionChipTextPaint.ascent()
                    + host.reactionChipTextPaint.descent()) / 2f;
            canvas.drawText(reactionChipTexts.get(i), textX, textY, host.reactionChipTextPaint);
        }

        // ── Stats ────────────────────────────────────────────────────────────
        float x = reactionsGroupRect.isEmpty()
                ? reactBtnRect.left
                : forwardBtnRect.right + host.footerBtnGap;

        // Views
        if (host.viewCount > 0) {
            String vText = formatCompact(host.viewCount) + " views";
            canvas.drawText(vText, reactBtnRect.right + host.footerBtnGap,
                    viewsBaselineY, host.statsPaint);
        }

        // ── Action buttons ───────────────────────────────────────────────────
        drawHeartIcon(canvas, reactBtnRect, host.myReacted);
        drawForwardIcon(canvas, forwardBtnRect);
        drawReplyIcon(canvas, replyBtnRect);
    }

    int hitTest(float px, float py) {
        if (!reactionsGroupRect.isEmpty() && reactionsGroupRect.contains(px, py)) {
            return REGION_REACTIONS_DETAIL;
        }
        if (reactBtnRect.contains(px, py))   return REGION_REACT;
        if (forwardBtnRect.contains(px, py)) return REGION_FORWARD;
        if (replyBtnRect.contains(px, py))   return REGION_REPLY;
        return REGION_NONE;
    }

    private void drawHeartIcon(Canvas canvas, RectF r, boolean filled) {
        float s = r.width() * 0.7f;
        if (lastHeartSize != s) {
            // Simple heart via bezier approximation
            heartPath.reset();
            float cx = 0f, cy = s * 0.12f;
            float w2 = s * 0.5f;
            heartPath.moveTo(cx, cy + s * 0.45f);
            heartPath.cubicTo(cx - w2 * 2f, cy, cx - w2 * 2f, cy - s * 0.6f,
                    cx, cy - s * 0.25f);
            heartPath.cubicTo(cx + w2 * 2f, cy - s * 0.6f, cx + w2 * 2f, cy,
                    cx, cy + s * 0.45f);
            heartPath.close();
            lastHeartSize = s;
        }
        canvas.save();
        canvas.translate(r.centerX(), r.centerY());
        Paint p = filled ? host.reactFilledPaint : host.reactOutlinePaint;
        canvas.drawPath(heartPath, p);
        canvas.restore();
    }

    private void drawForwardIcon(Canvas canvas, RectF r) {
        float s = r.width() * 0.55f;
        if (lastArrowSize != s) {
            arrowPath.reset();
            // Arrow stem
            arrowPath.moveTo(-s, 0f);
            arrowPath.lineTo(s * 0.4f, 0f);
            // Arrow head
            arrowPath.moveTo(0f, -s * 0.6f);
            arrowPath.lineTo(s * 0.7f, 0f);
            arrowPath.lineTo(0f, s * 0.6f);
            lastArrowSize = s;
        }
        canvas.save();
        canvas.translate(r.centerX(), r.centerY());
        canvas.drawPath(arrowPath, host.forwardIconPaint);
        canvas.restore();
    }

    private void drawReplyIcon(Canvas canvas, RectF r) {
        // Simple chat-bubble glyph
        float s = r.width() * 0.6f;
        float cx = r.centerX(), cy = r.centerY();
        RectF bubble = new RectF(cx - s, cy - s * 0.7f, cx + s, cy + s * 0.5f);
        canvas.drawRoundRect(bubble, s * 0.35f, s * 0.35f, host.replyIconPaint);
        // Tail
        Path tail = new Path();
        tail.moveTo(cx - s * 0.5f, bubble.bottom);
        tail.lineTo(cx - s * 0.8f, cy + s * 0.9f);
        tail.lineTo(cx - s * 0.1f, bubble.bottom);
        tail.close();
        canvas.drawPath(tail, host.replyIconPaint);
    }

    private static String formatCompact(long n) {
        if (n >= 1_000_000L) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000L)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
