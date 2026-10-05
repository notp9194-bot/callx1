package com.callx.app.community.canvas;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;

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

    // Lambi post: > COLLAPSE_THRESHOLD_LINES lines ho to COLLAPSED_LINES dikhao + "Read more".
    // (Threshold collapsed se bada hai taaki 7-8 line ki post ke liye "Read more" ka matlab ho.)
    private static final int COLLAPSE_THRESHOLD_LINES = 8;
    private static final int COLLAPSED_LINES = 6;

    private String lastRawText;
    private int lastWidth = -1;
    private boolean lastExpanded;
    private StaticLayout cachedLayout;
    private boolean collapsible;
    private TextPaint readMorePaint;
    private float labelGap, labelHeight, labelBaselineOffset;
    /** Label rect in text-block local coords (0,0 = text block top-left). */
    private final RectF labelRect = new RectF();

    /** Kya post collapse ho sakti hai (Read more / Show less label dikhta hai)? */
    boolean isCollapsible() { return collapsible; }

    private void ensureLabelPaint() {
        if (readMorePaint != null) return;
        readMorePaint = new TextPaint(host.postTextPaint);
        readMorePaint.setColor(host.mentionColor);
        readMorePaint.setTypeface(Typeface.DEFAULT_BOLD);
        readMorePaint.setTextSize(host.postTextPaint.getTextSize() * 0.93f);
        labelGap = 6f * host.density;
        android.graphics.Paint.FontMetrics fm = readMorePaint.getFontMetrics();
        labelHeight = fm.descent - fm.ascent;
        labelBaselineOffset = -fm.ascent;
    }

    /** Rebuilds the cached StaticLayout if needed and returns its height in px (label included). */
    float measure(int availableWidthPx) {
        String text = host.postText != null ? host.postText : "";
        final boolean expanded = host.textExpanded;
        if (cachedLayout != null && text.equals(lastRawText)
                && availableWidthPx == lastWidth && expanded == lastExpanded) {
            return totalHeight();
        }
        CharSequence spanned = buildSpans(text);
        TextPaint paint = host.postTextPaint;
        int safeWidth = Math.max(1, availableWidthPx);

        StaticLayout full = StaticLayout.Builder
                .obtain(spanned, 0, spanned.length(), paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build();

        collapsible = full.getLineCount() > COLLAPSE_THRESHOLD_LINES;
        if (collapsible && !expanded) {
            cachedLayout = StaticLayout.Builder
                    .obtain(spanned, 0, spanned.length(), paint, safeWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .setMaxLines(COLLAPSED_LINES)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(safeWidth)
                    .build();
        } else {
            cachedLayout = full;
        }
        lastRawText = text;
        lastWidth = safeWidth;
        lastExpanded = expanded;
        if (collapsible) ensureLabelPaint();
        return totalHeight();
    }

    private float totalHeight() {
        float h = cachedLayout.getHeight();
        if (collapsible) {
            ensureLabelPaint();
            h += labelGap + labelHeight;
        }
        return h;
    }

    void draw(Canvas canvas, float top, float left) {
        if (cachedLayout == null) return;
        canvas.save();
        canvas.translate(left, top);
        cachedLayout.draw(canvas);
        if (collapsible) {
            ensureLabelPaint();
            float labelTop = cachedLayout.getHeight() + labelGap;
            String label = lastExpanded ? "Show less" : "Read more";
            canvas.drawText(label, 0f, labelTop + labelBaselineOffset, readMorePaint);
            labelRect.set(0f, labelTop, readMorePaint.measureText(label), labelTop + labelHeight);
        }
        canvas.restore();
    }

    /** Tap on "Read more"/"Show less" label? Coords text-block local. Touch target thoda bada. */
    boolean readMoreHit(float xInText, float yInText) {
        if (!collapsible || labelRect.isEmpty()) return false;
        float pad = 10f * host.density;
        return xInText >= labelRect.left - pad && xInText <= labelRect.right + pad
                && yInText >= labelRect.top - pad && yInText <= labelRect.bottom + pad;
    }

    // ── Tappable tokens: @mention, link, #hashtag ───────────────────────────────
    static final int TOKEN_MENTION = 1, TOKEN_LINK = 2, TOKEN_HASHTAG = 3;

    /** Tappable text token (type = TOKEN_*, text = raw token e.g. "@ravi", "https://x.com", "#news"). */
    static final class Token {
        final int type;
        final String text;
        Token(int type, String text) { this.type = type; this.text = text; }
    }

    /** ForegroundColorSpan jo token type bhi yaad rakhta hai (hit-test ke liye). */
    private static final class TokenSpan extends ForegroundColorSpan {
        final int type;
        TokenSpan(int color, int type) { super(color); this.type = type; }
    }

    /**
     * Token under (x,y) relative to the text block's top-left, or null. Pehle sirf @mention tha
     * aur getOffsetForHorizontal() ka nearest-boundary result use hota tha (line ke khali right
     * hisse par bhi tap "hit" ho sakta tha). Ab character ke actual horizontal extent se match hota
     * hai, thode touch slop ke saath.
     */
    Token tokenAt(float xInText, float yInText) {
        if (cachedLayout == null) return null;
        if (yInText < 0 || yInText > cachedLayout.getHeight()) return null; // label zone / bahar
        CharSequence text = cachedLayout.getText();
        if (!(text instanceof Spanned)) return null;
        Spanned spanned = (Spanned) text;
        int line = cachedLayout.getLineForVertical((int) yInText);
        float slop = 6f * host.density;
        float[] probes = {xInText, xInText - slop, xInText + slop};
        for (float px : probes) {
            int pos = charIndexAt(line, px);
            if (pos < 0) continue;
            TokenSpan[] spans = spanned.getSpans(pos, pos + 1, TokenSpan.class);
            if (spans.length == 0) continue;
            int st = spanned.getSpanStart(spans[0]);
            int en = spanned.getSpanEnd(spans[0]);
            if (st < 0 || en > text.length() || st >= en) continue;
            return new Token(spans[0].type, text.subSequence(st, en).toString());
        }
        return null;
    }

    /** Backward-compat: sirf @mention. */
    String mentionAt(float xInText, float yInText) {
        Token t = tokenAt(xInText, yInText);
        return t != null && t.type == TOKEN_MENTION ? t.text : null;
    }

    /** Line `line` me x ke neeche wale character ka index, nahi mila to -1. */
    private int charIndexAt(int line, float x) {
        int ls = cachedLayout.getLineStart(line);
        int le = cachedLayout.getLineEnd(line);
        for (int i = ls; i < le; i++) {
            float a = cachedLayout.getPrimaryHorizontal(i);
            float b = cachedLayout.getPrimaryHorizontal(i + 1);
            if (x >= Math.min(a, b) && x <= Math.max(a, b)) return i;
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        if (Character.isLetterOrDigit(c) || c == '_') return true;
        // Hindi/Indic matras (ा ि ्) letter nahi hote — hashtag beech me na kate.
        int t = Character.getType(c);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK;
    }

    private CharSequence buildSpans(String text) {
        if (text.isEmpty()) return text;
        final boolean mayMention = text.indexOf('@') >= 0;
        final boolean mayHashtag = text.indexOf('#') >= 0;
        final boolean mayLink = text.indexOf('.') >= 0 || text.contains("://");
        if (!mayMention && !mayHashtag && !mayLink) return text;

        SpannableString ss = new SpannableString(text);
        final int color = host.mentionColor;
        // Link ranges pehle — URL ke andar ke '@'/'#' mention/hashtag na ban jayein.
        java.util.List<int[]> linkRanges = new java.util.ArrayList<>();

        if (mayLink) {
            java.util.regex.Matcher m = android.util.Patterns.WEB_URL.matcher(text);
            while (m.find()) {
                int st = m.start(), en = m.end();
                // Trailing punctuation URL ka hissa nahi ("see x.com." / "(x.com)").
                while (en > st && ".,!?:;)]}'\"".indexOf(text.charAt(en - 1)) >= 0) en--;
                if (en - st < 4) continue;
                if (st > 0 && text.charAt(st - 1) == '@') continue; // email / @handle.domain
                ss.setSpan(new TokenSpan(color, TOKEN_LINK), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new UnderlineSpan(), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                linkRanges.add(new int[]{st, en});
            }
        }

        if (mayMention) {
            int start = 0;
            while (true) {
                int at = text.indexOf('@', start);
                if (at < 0) break;
                int end = at + 1;
                while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
                if (end > at + 1 && !insideAny(linkRanges, at)) {
                    ss.setSpan(new TokenSpan(color, TOKEN_MENTION), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                start = end;
            }
        }

        if (mayHashtag) {
            int start = 0;
            while (true) {
                int hash = text.indexOf('#', start);
                if (hash < 0) break;
                int end = hash + 1;
                while (end < text.length() && end - hash <= 50 && isWordChar(text.charAt(end))) end++;
                boolean boundaryOk = hash == 0 || !isWordChar(text.charAt(hash - 1));
                boolean hasLetter = false;
                for (int i = hash + 1; i < end; i++) {
                    if (Character.isLetter(text.charAt(i))) { hasLetter = true; break; }
                }
                if (boundaryOk && hasLetter && !insideAny(linkRanges, hash)) {
                    ss.setSpan(new TokenSpan(color, TOKEN_HASHTAG), hash, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                start = Math.max(end, hash + 1);
            }
        }
        return ss;
    }

    private static boolean insideAny(java.util.List<int[]> ranges, int pos) {
        for (int[] r : ranges) if (pos >= r[0] && pos < r[1]) return true;
        return false;
    }
}
