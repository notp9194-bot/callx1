package com.callx.app.chat.util;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;

/**
 * MarkdownFormatter — WhatsApp-style *bold*, _italic_, ~strikethrough~ parsing.
 *
 * The composer side (see GifAwareEditText's selection toolbar) inserts the
 * literal marker characters around the selected text, exactly like WhatsApp
 * does — the raw string that gets stored in Room / sent to Firebase still
 * contains "*bold*", "_italic_", "~strike~" as plain characters. This class
 * is the render-side counterpart: it strips those markers back out of the
 * raw text and applies real StyleSpan/StrikethroughSpan so the message
 * bubble actually shows bold/italic/struck-through text instead of the
 * literal asterisks/underscores/tildes.
 *
 * Used by MessageBubbleCanvasView, which draws message bodies via a plain
 * StaticLayout — StaticLayout (like all android.text.Layout subclasses)
 * renders CharSequence spans natively, so handing it a SpannableStringBuilder
 * instead of a String is all that's needed on the drawing side.
 */
public final class MarkdownFormatter {

    private MarkdownFormatter() {}

    /**
     * @param raw the stored/sent message text, markers and all
     * @return a CharSequence ready to hand to StaticLayout.Builder.obtain() —
     *         either the original String unchanged (fast path, no markers
     *         present) or a SpannableStringBuilder with markers stripped and
     *         the matching style spans applied.
     */
    public static CharSequence format(String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;

        // Fast path: this runs on every bubble bind/measure, and the
        // overwhelming majority of messages carry no markers at all — skip
        // building a SpannableStringBuilder entirely for those. Single pass
        // over the string checking for all three marker chars at once,
        // instead of three separate indexOf() scans.
        if (!containsAnyMarker(raw)) {
            return raw;
        }

        SpannableStringBuilder sb = new SpannableStringBuilder(raw);
        stripAndSpan(sb, '*', () -> new StyleSpan(Typeface.BOLD));
        stripAndSpan(sb, '_', () -> new StyleSpan(Typeface.ITALIC));
        stripAndSpan(sb, '~', StrikethroughSpan::new);
        return sb;
    }

    private static boolean containsAnyMarker(String text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '_' || c == '~') return true;
        }
        return false;
    }

    private interface SpanFactory {
        Object create();
    }

    /**
     * Finds the next marker..marker pair for the given character, removes
     * both marker characters, and applies the span over the text that was
     * between them. Operates in a single left-to-right pass, re-reading the
     * builder's current contents each iteration since deletes shift indices.
     */
    private static void stripAndSpan(SpannableStringBuilder sb, char marker, SpanFactory factory) {
        int searchFrom = 0;
        while (true) {
            String s = sb.toString();
            int start = s.indexOf(marker, searchFrom);
            if (start < 0) return;
            int end = s.indexOf(marker, start + 1);
            if (end < 0) return; // unmatched opening marker — leave the rest as-is

            if (end == start + 1) {
                // Empty pair, e.g. "**" — nothing to style, skip past both.
                searchFrom = end + 1;
                continue;
            }

            char firstInner = s.charAt(start + 1);
            char lastInner = s.charAt(end - 1);
            if (firstInner == ' ' || lastInner == ' ') {
                // WhatsApp rule: the marker must hug the text with no space
                // right inside it ("*bold*" yes, "* bold*" no) — otherwise
                // treat it as literal punctuation, not a formatting marker.
                searchFrom = start + 1;
                continue;
            }

            sb.delete(end, end + 1);
            sb.delete(start, start + 1);
            int innerStart = start;
            int innerEnd = end - 1;
            sb.setSpan(factory.create(), innerStart, innerEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            searchFrom = innerEnd;
        }
    }
}
