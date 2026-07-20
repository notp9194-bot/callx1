package com.callx.app.utils;

import android.text.SpannableString;
import android.text.Spanned;

import com.callx.app.chat.ui.SpoilerSpan;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SpoilerTextHelper — parses ||spoiler text|| syntax and applies SpoilerSpan.
 *
 * Telegram uses the same delimiter style. The helper strips the || markers
 * from the visible text and wraps the inner content with a SpoilerSpan.
 *
 * Multiple spoilers in one message are supported:
 *   "Normal text ||hidden part|| more text ||another secret||"
 *
 * Usage:
 *   SpannableString result = SpoilerTextHelper.apply(text, revealedStartIndices, () -> holder.itemView.post(() -> adapter.notifyItemChanged(pos)));
 *   if (result != null) {
 *       tvMessage.setText(result);
 *       tvMessage.setMovementMethod(LinkMovementMethod.getInstance());
 *   }
 */
public class SpoilerTextHelper {

    /** Matches ||non-empty content|| with non-greedy inner match. */
    private static final Pattern SPOILER_PATTERN =
            Pattern.compile("\\|\\|(.+?)\\|\\|", Pattern.DOTALL);

    /**
     * Applies SpoilerSpan instances to all ||...|| occurrences in {@code text}.
     *
     * @param text            Raw message text (may contain || markers).
     * @param revealedIndices Set of span-start-indices (in the STRIPPED string)
     *                        that should start in the revealed state. Pass an
     *                        empty Set if nothing is revealed yet.
     * @param onReveal        Called when any span is tapped to reveal. The
     *                        caller should invalidate/rebind the ViewHolder.
     * @return A SpannableString with spans applied, or {@code null} if the text
     *         contains no spoiler markers (fast path).
     */
    public static SpannableString apply(
            String text,
            Set<Integer> revealedIndices,
            Runnable onReveal) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = SPOILER_PATTERN.matcher(text);
        if (!m.find()) return null; // No spoilers — caller uses plain text

        // Rebuild display string (without || markers) and track span ranges
        StringBuilder sb = new StringBuilder();
        List<int[]> ranges = new ArrayList<>(); // [startInStripped, endInStripped]
        int lastEnd = 0;
        m.reset();
        while (m.find()) {
            sb.append(text, lastEnd, m.start());
            int spanStart = sb.length();
            String inner  = m.group(1);
            sb.append(inner);
            int spanEnd   = sb.length();
            ranges.add(new int[]{spanStart, spanEnd});
            lastEnd = m.end();
        }
        sb.append(text, lastEnd, text.length());

        SpannableString spannable = new SpannableString(sb.toString());
        for (int[] range : ranges) {
            boolean alreadyRevealed = revealedIndices != null
                    && revealedIndices.contains(range[0]);
            SpoilerSpan span = new SpoilerSpan(onReveal);
            if (alreadyRevealed) span.setRevealed(true);
            spannable.setSpan(span, range[0], range[1],
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return spannable;
    }

    /**
     * Quick check — returns true if the text contains at least one ||...||.
     * Use this before calling apply() to skip the Pattern match for plain text.
     */
    public static boolean hasSpoiler(String text) {
        return text != null && text.contains("||");
    }

    /**
     * Returns the display text with || markers stripped but no spans —
     * useful for generating a chat-list preview snippet without showing
     * the raw markers to the user.
     */
    public static String stripMarkers(String text) {
        if (!hasSpoiler(text)) return text;
        return SPOILER_PATTERN.matcher(text).replaceAll("$1");
    }
}
