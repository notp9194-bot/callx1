package com.callx.app.utils;

import android.text.Editable;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.graphics.Typeface;

/**
 * TextSpanSerializer — Converts Spanned text (with formatting) to/from HTML string format.
 * Used to preserve AdvancedRichTextController formatting (colors, sizes, bold, etc)
 * through send/storage/display cycle.
 *
 * When a user applies formatting via AdvancedRichTextController:
 *  - ForegroundColorSpan → <font color="#RRGGBB">
 *  - BackgroundColorSpan → <mark style="background:#RRGGBB">
 *  - AbsoluteSizeSpan → <font size="Nsp">
 *  - StyleSpan (bold/italic) → <b>, <i>
 *  - StrikethroughSpan → <s>
 *  - TypefaceSpan → <font face="...">
 */
public class TextSpanSerializer {

    /**
     * Serialize Spanned text to HTML string, preserving all formatting.
     * If input is already a String, passes through as-is.
     * @param text The text to serialize (may contain spans)
     * @return HTML string safe to store in database
     */
    public static String toHtml(CharSequence text) {
        if (text == null) return "";
        if (text instanceof Spanned) {
            return Html.toHtml((Spanned) text);
        }
        return text.toString();
    }

    /**
     * Deserialize HTML string back to Spanned text with formatting preserved.
     * Uses Html.fromHtml() with FROM_HTML_MODE_COMPACT to parse HTML entities.
     * @param html The HTML string from database
     * @return Spanned text with formatting
     */
    public static Spanned fromHtml(String html) {
        if (html == null || html.isEmpty()) {
            return new SpannableString("");
        }
        try {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT);
        } catch (Exception e) {
            // Fallback to plain text if parsing fails
            return new SpannableString(html);
        }
    }

    /**
     * Check if text contains any formatting spans (color, size, bold, etc).
     * @param text The text to check
     * @return true if text has any spans
     */
    public static boolean hasFormatting(CharSequence text) {
        if (!(text instanceof Spanned)) return false;
        Spanned spanned = (Spanned) text;
        return spanned.getSpans(0, spanned.length(), Object.class).length > 0;
    }

    /**
     * Get plain text without any formatting spans.
     * @param text The text to strip
     * @return Plain text string
     */
    public static String toPlainText(CharSequence text) {
        if (text instanceof Spanned) {
            Spanned spanned = (Spanned) text;
            // Use Html.toHtml() then strip tags, or just return toString()
            return spanned.toString();
        }
        return text != null ? text.toString() : "";
    }

    /**
     * Merge spans from source into target text.
     * Used when EditText content needs to be extracted with its spans preserved.
     * @param source The text with spans (typically from EditText.getText())
     * @return A CharSequence ready for sending (may be Spanned or plain String)
     */
    public static CharSequence extractWithSpans(CharSequence source) {
        if (source instanceof Spanned) {
            // Create a copy as SpannableString to ensure spans are retained
            return new SpannableString(source);
        }
        return source != null ? source.toString() : "";
    }

    /**
     * Prepare text for canvas rendering.
     * If text has formatting, serialize to HTML and return.
     * Canvas will deserialize and display with spans preserved.
     * @param text The text to prepare
     * @return HTML-safe string suitable for storage and canvas rendering
     */
    public static String prepareForSend(CharSequence text) {
        if (text == null || text.length() == 0) {
            return "";
        }
        // If text has spans, serialize to HTML
        if (hasFormatting(text)) {
            return toHtml(text);
        }
        // Plain text passes through
        return text.toString();
    }
}
