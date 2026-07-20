package com.callx.app.community.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.callx.app.community.CommunityReaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws the row of emoji reaction chips beneath a post ("👍 5", "❤️ 2", ...),
 * sorted by count descending — mirrors CommunityPostAdapter.bindReactionRow's
 * dynamically-added TextView chips, but as one drawn row instead of inflated
 * child views.
 */
final class ReactionRowRenderer {

    private final CommunityPostCanvasView host;
    private final List<RectF> chipRects  = new ArrayList<>();
    private final List<String> chipLabels = new ArrayList<>();
    /** Pre-allocated RectF pool — grown lazily, never shrunk. Avoids per-chip allocation. */
    private final List<RectF> chipPool   = new ArrayList<>();

    ReactionRowRenderer(CommunityPostCanvasView host) {
        this.host = host;
    }

    /** Lays out chips for the given counts within [left,right] starting at top. Returns bottom y. */
    float layout(float left, float top, float right, Map<String, Long> counts) {
        chipRects.clear();
        chipLabels.clear();
        if (counts == null || counts.isEmpty()) return top;

        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        float x = left;
        float y = top;
        float chipH = host.reactionChipHeight;
        float padH = host.reactionChipPaddingH;
        float chipGap = host.reactionChipGap;

        for (Map.Entry<String, Long> entry : entries) {
            if (entry.getValue() <= 0) continue;
            String label = CommunityReaction.getEmoji(entry.getKey()) + " " + entry.getValue();
            float w = host.reactionChipTextPaint.measureText(label) + padH * 2;
            if (x + w > right && x > left) {
                x = left;
                y += chipH + host.reactionChipRowGap;
            }
            // Reuse a pooled RectF rather than allocating on every layout pass
            RectF r;
            if (chipRects.size() < chipPool.size()) {
                r = chipPool.get(chipRects.size());
            } else {
                r = new RectF();
                chipPool.add(r);
            }
            r.set(x, y, x + w, y + chipH);
            chipRects.add(r);
            chipLabels.add(label);
            x += w + chipGap;
        }
        return chipRects.isEmpty() ? top : y + chipH;
    }

    void draw(Canvas canvas) {
        for (int i = 0; i < chipRects.size(); i++) {
            RectF chip = chipRects.get(i);
            float r = chip.height() / 2f;
            canvas.drawRoundRect(chip, r, r, host.reactionChipBgPaint);
            Paint.FontMetrics fm = host.reactionChipTextPaint.getFontMetrics();
            canvas.drawText(chipLabels.get(i), chip.left + host.reactionChipPaddingH,
                    chip.centerY() - (fm.ascent + fm.descent) / 2f, host.reactionChipTextPaint);
        }
    }

    /** Whole-row hit test — any chip tap opens the reaction-details sheet. */
    boolean hitTest(float x, float y) {
        for (RectF chip : chipRects) {
            if (chip.contains(x, y)) return true;
        }
        return false;
    }

    boolean isEmpty() {
        return chipRects.isEmpty();
    }
}
