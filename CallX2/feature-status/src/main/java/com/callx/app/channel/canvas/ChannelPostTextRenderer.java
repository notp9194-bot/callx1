package com.callx.app.channel.canvas;

import android.graphics.Canvas;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;

/**
 * Renders the post body/caption text using a cached StaticLayout — no child
 * TextView, no view inflation, no measure-and-layout overhead. The same
 * "measure-once, draw-always" pattern as PostTextRenderer / MessageBubbleCanvasView.
 *
 * StaticLayout is only rebuilt when the raw text or available width changes
 * since the last bind — handles scroll-driven remeasures at zero cost.
 * @mention tokens are highlighted with the brand primary colour via a
 * ForegroundColorSpan, resolved once inside buildMentionSpans().
 */
final class ChannelPostTextRenderer {

    private final ChannelPostCanvasView host;

    ChannelPostTextRenderer(ChannelPostCanvasView host) {
        this.host = host;
    }

    // Cache — rebuild only when text or width actually changes.
    private String lastRawText;
    private int    lastWidth = -1;
    private StaticLayout cachedLayout;

    /**
     * Inject a StaticLayout pre-built by ChannelPostLayoutPrewarmer.
     * The next measure() call returns its height without re-building.
     */
    void setPrebuiltLayout(StaticLayout layout) {
        if (layout == null) return;
        cachedLayout = layout;
        lastRawText  = null;           // force text-equality re-check on next call
        lastWidth    = layout.getWidth();
    }

    /** Rebuilds the StaticLayout if needed and returns its height in px. */
    float measure(int availableWidthPx) {
        String text = host.postText != null ? host.postText : "";
        if (cachedLayout != null && text.equals(lastRawText) && availableWidthPx == lastWidth) {
            return cachedLayout.getHeight();
        }
        int safeWidth = Math.max(1, availableWidthPx);
        CharSequence spanned = buildMentionSpans(text);
        TextPaint paint = host.postTextPaint;

        cachedLayout = StaticLayout.Builder
                .obtain(spanned, 0, spanned.length(), paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build();
        lastRawText = text;
        lastWidth   = safeWidth;
        return cachedLayout.getHeight();
    }

    void draw(Canvas canvas, float top, float left) {
        if (cachedLayout == null) return;
        canvas.save();
        canvas.translate(left, top);
        cachedLayout.draw(canvas);
        canvas.restore();
    }

    /** Returns the @mention token under the given point (relative to text block), or null. */
    String mentionAt(float xInText, float yInText) {
        if (cachedLayout == null) return null;
        int line   = cachedLayout.getLineForVertical((int) yInText);
        int offset = cachedLayout.getOffsetForHorizontal(line, xInText);
        CharSequence text = cachedLayout.getText();
        if (!(text instanceof Spanned)) return null;
        Spanned spanned = (Spanned) text;
        ForegroundColorSpan[] spans = spanned.getSpans(offset, offset, ForegroundColorSpan.class);
        if (spans.length == 0) return null;
        int start = spanned.getSpanStart(spans[0]);
        int end   = spanned.getSpanEnd(spans[0]);
        if (start < 0 || end > text.length() || start >= end) return null;
        return text.subSequence(start, end).toString();
    }

    private CharSequence buildMentionSpans(String text) {
        if (text.isEmpty() || !text.contains("@")) return text;
        SpannableString ss = new SpannableString(text);
        int color = host.mentionColor;
        int start = 0;
        while (true) {
            int at = text.indexOf('@', start);
            if (at < 0) break;
            int end = at + 1;
            while (end < text.length()
                    && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                end++;
            }
            if (end > at + 1) {
                ss.setSpan(new ForegroundColorSpan(color), at, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end;
        }
        return ss;
    }
}
