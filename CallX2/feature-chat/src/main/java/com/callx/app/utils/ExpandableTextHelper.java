package com.callx.app.utils;

import android.graphics.Color;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.util.Linkify;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.models.Message;

/**
 * ExpandableTextHelper — WhatsApp-style "Read more / Read less" for chat messages.
 *
 * Design principles:
 *   • No libraries — pure Android Spans + ClickableSpan
 *   • RecyclerView-safe: expand/collapse state stored on Message model (isExpanded)
 *   • Staleness guard on post() via View.setTag — prevents recycled VH from applying
 *     the wrong collapse to a different message
 *   • Handles: long text (>MAX_CHARS), many newlines (>MAX_LINES), short wrapping text
 *   • "Read more" / "Read less" colored in light blue — visible on both bubbles
 *
 * KEY DESIGN DECISION — PREVIEW_CHARS vs MAX_CHARS:
 *   MAX_CHARS  = threshold for "does this message NEED expand/collapse?" (350 chars)
 *   PREVIEW_CHARS = how many chars to show in collapsed state (130 chars ≈ 3 lines)
 *
 *   These are deliberately different. MAX_CHARS triggers the feature; PREVIEW_CHARS
 *   controls what's visible. The collapsed preview must fit in MAX_LINES - 1 lines so
 *   "… Read more" is guaranteed to appear on the last visible line, not pushed off-screen.
 *   At 14sp in a typical 260dp-wide bubble, one line ≈ 30–35 chars → 3 lines ≈ 90–105 chars.
 *   PREVIEW_CHARS = 130 is a safe upper bound that works on all normal phone sizes.
 */
public final class ExpandableTextHelper {

    /** Maximum lines shown in collapsed state. */
    public static final int MAX_LINES = 5;

    /**
     * Character threshold — texts longer than this ALWAYS get "Read more".
     * Kept at 350 so only genuinely long messages trigger the feature.
     */
    public static final int MAX_CHARS = 350;

    /**
     * Characters shown in the collapsed preview.
     * Must be small enough that PREVIEW_CHARS chars wrap to at most MAX_LINES-1 lines
     * on the narrowest reasonable device (~260dp wide bubble, 14sp text ≈ 30 chars/line).
     * 130 chars ≈ 3–4 lines on a narrow phone; definitely fits within 4 lines everywhere.
     */
    private static final int PREVIEW_CHARS = 130;

    /** "Read more" / "Read less" label color — light blue, visible on all bubble colors. */
    private static final int LABEL_COLOR = 0xFF64B5F6;

    private ExpandableTextHelper() {}

    /**
     * Bind a text message to a TextView with expand/collapse support.
     *
     * Call this from onBindViewHolder INSTEAD of calling tv.setText() directly.
     * Handles Linkify internally so callers don't need to.
     *
     * @param tv              The message TextView
     * @param msg             The Message model (isExpanded / isExpandable fields store state)
     * @param adapter         The RecyclerView adapter (for notifyItemChanged)
     * @param adapterPosition Current adapter position
     * @param sent            True if this is a sent message (affects link color)
     */
    public static void bind(
            @NonNull final TextView tv,
            @NonNull final Message  msg,
            @NonNull final RecyclerView.Adapter<?> adapter,
            final int adapterPosition,
            final boolean sent) {

        final String fullText = msg.text != null ? msg.text : "";

        // ── Path 1: long text — always needs expand/collapse ─────────────────
        if (fullText.length() > MAX_CHARS) {
            msg.isExpandable = true;
            if (msg.isExpanded) {
                applyExpanded(tv, msg, fullText, adapter, adapterPosition, sent);
            } else {
                applyCollapsed(tv, msg, fullText, adapter, adapterPosition, sent);
            }
            return;
        }

        // ── Path 2: explicit newlines ≥ MAX_LINES — collapse without post() ──
        // A message with 5+ explicit newlines is definitively multi-paragraph.
        // Count them inline so we never need an async post() for this common case.
        int newlineCount = 0;
        for (int i = 0; i < fullText.length(); i++) {
            if (fullText.charAt(i) == '\n') newlineCount++;
        }
        if (newlineCount >= MAX_LINES) {
            msg.isExpandable = true;
            if (msg.isExpanded) {
                applyExpanded(tv, msg, fullText, adapter, adapterPosition, sent);
            } else {
                applyCollapsed(tv, msg, fullText, adapter, adapterPosition, sent);
            }
            return;
        }

        // ── Path 3: state already measured from a previous bind ───────────────
        // Skip post() on rebinds — apply the known state directly.
        if (msg.isExpandable) {
            if (msg.isExpanded) {
                applyExpanded(tv, msg, fullText, adapter, adapterPosition, sent);
            } else {
                applyCollapsed(tv, msg, fullText, adapter, adapterPosition, sent);
            }
            return;
        }

        // ── Path 4: first bind of short text — measure via post() ────────────
        // Show the full text first, then check the line count after layout.
        // This is the ONLY path that uses post(), and runs only once per message.
        tv.setMaxLines(Integer.MAX_VALUE);
        setTextWithLinks(tv, fullText, sent);

        // Staleness guard: tag the view with a unique key for this message.
        // If the VH is recycled before post() fires, the tag won't match and
        // we skip the callback — preventing the wrong message from being collapsed.
        final String msgKey = buildMsgKey(msg, fullText);
        tv.setTag(msgKey);

        tv.post(() -> {
            // Guard 1: recycling check
            if (!msgKey.equals(tv.getTag())) return;
            // Guard 2: layout not ready yet — try one more frame
            Layout layout = tv.getLayout();
            if (layout == null) {
                tv.post(() -> {
                    if (!msgKey.equals(tv.getTag())) return;
                    Layout l2 = tv.getLayout();
                    if (l2 == null) return;
                    applyMeasuredCollapse(tv, msg, fullText, adapter, adapterPosition, sent, l2);
                });
                return;
            }
            applyMeasuredCollapse(tv, msg, fullText, adapter, adapterPosition, sent, layout);
        });
    }

    /** Apply the measured line count after layout pass — only for path 4. */
    private static void applyMeasuredCollapse(
            @NonNull TextView tv,
            @NonNull Message msg,
            @NonNull String fullText,
            @NonNull RecyclerView.Adapter<?> adapter,
            int adapterPosition,
            boolean sent,
            @NonNull Layout layout) {

        if (layout.getLineCount() > MAX_LINES) {
            msg.isExpandable = true;
            if (!msg.isExpanded) {
                applyCollapsed(tv, msg, fullText, adapter, adapterPosition, sent);
            }
        } else {
            msg.isExpandable = false;
            // Nothing to do — full text is already showing
        }
    }

    // ── Collapsed state ───────────────────────────────────────────────────────

    private static void applyCollapsed(
            @NonNull final TextView tv,
            @NonNull final Message msg,
            @NonNull final String fullText,
            @NonNull final RecyclerView.Adapter<?> adapter,
            final int position,
            final boolean sent) {

        tv.setMaxLines(MAX_LINES);

        // CRITICAL: truncate at PREVIEW_CHARS, NOT MAX_CHARS.
        // MAX_CHARS (350) wraps to 7+ lines in a normal bubble, pushing "Read more"
        // completely off-screen (beyond setMaxLines(5)).
        // PREVIEW_CHARS (130) wraps to ≤ 3–4 lines on any phone, leaving room for
        // "… Read more" on the last visible line.
        int previewLen = Math.min(fullText.length(), PREVIEW_CHARS);
        // Trim to a word boundary so we don't cut mid-word
        while (previewLen > 0 && previewLen < fullText.length()
                && fullText.charAt(previewLen) != ' '
                && fullText.charAt(previewLen) != '\n') {
            previewLen--;
        }
        if (previewLen == 0) previewLen = Math.min(fullText.length(), PREVIEW_CHARS);
        String base = fullText.substring(0, previewLen);

        SpannableStringBuilder ssb = buildWithLinks(base, sent);
        ssb.append("… ");
        int start = ssb.length();
        ssb.append("Read more");

        ssb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View v) {
                msg.isExpanded = true;
                adapter.notifyItemChanged(position);
            }
            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                ds.setColor(LABEL_COLOR);
                ds.setFakeBoldText(true);
                ds.setUnderlineText(false);
            }
        }, start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        tv.setText(ssb);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setHighlightColor(Color.TRANSPARENT);
    }

    // ── Expanded state ────────────────────────────────────────────────────────

    private static void applyExpanded(
            @NonNull final TextView tv,
            @NonNull final Message msg,
            @NonNull final String fullText,
            @NonNull final RecyclerView.Adapter<?> adapter,
            final int position,
            final boolean sent) {

        tv.setMaxLines(Integer.MAX_VALUE);

        SpannableStringBuilder ssb = buildWithLinks(fullText, sent);
        ssb.append("  ");
        int start = ssb.length();
        ssb.append("Read less");

        ssb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View v) {
                msg.isExpanded = false;
                adapter.notifyItemChanged(position);
            }
            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                ds.setColor(LABEL_COLOR);
                ds.setFakeBoldText(true);
                ds.setUnderlineText(false);
            }
        }, start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        tv.setText(ssb);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setHighlightColor(Color.TRANSPARENT);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Set plain text with Linkify applied if needed. */
    private static void setTextWithLinks(
            @NonNull TextView tv,
            @NonNull String text,
            boolean sent) {

        if (mightHaveLink(text)) {
            SpannableStringBuilder ssb = new SpannableStringBuilder(text);
            Linkify.addLinks(ssb, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
            tv.setLinkTextColor(sent ? 0xFFB3E5FC : 0xFF90CAF9);
            tv.setMovementMethod(LinkMovementMethod.getInstance());
            tv.setHighlightColor(Color.TRANSPARENT);
            tv.setText(ssb);
        } else {
            tv.setMovementMethod(null);
            tv.setText(text);
        }
    }

    /** Build a SpannableStringBuilder with Linkify spans applied. */
    @NonNull
    private static SpannableStringBuilder buildWithLinks(@NonNull String text, boolean sent) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(text);
        if (mightHaveLink(text)) {
            Linkify.addLinks(ssb, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
        }
        return ssb;
    }

    /** Fast pre-check — avoids running Linkify regex on plain messages. */
    private static boolean mightHaveLink(@NonNull String text) {
        return text.contains("http://")
                || text.contains("https://")
                || text.contains("www.")
                || text.contains("@")
                || (text.length() >= 7 && text.contains("+"));
    }

    /** Build a unique key for a message — used as the staleness guard tag on the TextView. */
    private static String buildMsgKey(@NonNull Message msg, @NonNull String fullText) {
        String id = msg.messageId != null ? msg.messageId
                  : msg.id        != null ? msg.id
                  : null;
        return id != null ? id : "hash_" + fullText.hashCode();
    }
}
