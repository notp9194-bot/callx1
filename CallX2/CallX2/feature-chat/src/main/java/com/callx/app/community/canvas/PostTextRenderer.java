package com.callx.app.community.canvas;

import android.graphics.Canvas;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;

/**
 * Draws the post caption/body text with @mention spans highlighted, using a
 * plain StaticLayout (no child TextView) — same "one custom View owns the
 * text block" approach as the chat message bubble's text rendering.
 *
 * measure() builds/caches the StaticLayout for the current width and returns
 * its height; draw() just paints the cached layout. Both are only recomputed
 * when the raw text or the available width actually changes since the last
 * bind, mirroring the ellipsize-cache pattern used elsewhere in this canvas
 * system to avoid repeated text shaping on every scroll-driven draw().
 */
final class PostTextRenderer {

    private final CommunityPostCanvasView host;

    PostTextRenderer(CommunityPostCanvasView host) {
        this.host = host;
    }

    private String lastRawText;
    private int lastWidth = -1;
    private StaticLayout cachedLayout;

    /** Rebuilds the cached StaticLayout if needed and returns its height in px. */
    float measure(int availableWidthPx) {
        String text = host.postText != null ? host.postText : "";
        if (cachedLayout != null && text.equals(lastRawText) && availableWidthPx == lastWidth) {
            return cachedLayout.getHeight();
        }
        CharSequence spanned = buildMentionSpans(text);
        TextPaint paint = host.postTextPaint;
        int safeWidth = Math.max(1, availableWidthPx);

        StaticLayout.Builder builder = StaticLayout.Builder
                .obtain(spanned, 0, spanned.length(), paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false);
        cachedLayout = builder.build();
        lastRawText = text;
        lastWidth = safeWidth;
        return cachedLayout.getHeight();
    }

    void draw(Canvas canvas, float top, float left) {
        if (cachedLayout == null) return;
        canvas.save();
        canvas.translate(left, top);
        cachedLayout.draw(canvas);
        canvas.restore();
    }

    /** Returns the @mention token under (x,y) relative to the text block's top-left, or null. */
    String mentionAt(float xInText, float yInText) {
        if (cachedLayout == null) return null;
        int line = cachedLayout.getLineForVertical((int) yInText);
        int offset = cachedLayout.getOffsetForHorizontal(line, xInText);
        CharSequence text = cachedLayout.getText();
        if (!(text instanceof Spanned)) return null;
        Spanned spanned = (Spanned) text;
        ForegroundColorSpan[] spans = spanned.getSpans(offset, offset, ForegroundColorSpan.class);
        if (spans.length == 0) return null;
        int start = spanned.getSpanStart(spans[0]);
        int end = spanned.getSpanEnd(spans[0]);
        if (start < 0 || end > text.length() || start >= end) return null;
        return text.subSequence(start, end).toString();
    }

    private CharSequence buildMentionSpans(String text) {
        if (text.isEmpty() || !text.contains("@")) return text;
        SpannableString ss = new SpannableString(text);
        int mentionColor = host.mentionColor;
        int start = 0;
        while (true) {
            int at = text.indexOf('@', start);
            if (at < 0) break;
            int end = at + 1;
            while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
            if (end > at + 1) {
                ss.setSpan(new ForegroundColorSpan(mentionColor), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end;
        }
        return ss;
    }
}
