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
 *   • No object creation in hot path (adapter position captured once in closure)
 *   • Handles: long text (>MAX_CHARS), many lines (>MAX_LINES), short text (no-op)
 *   • "Read more" / "Read less" colored in light blue — visible on both green & dark bubbles
 */
public final class ExpandableTextHelper {

    /** Maximum lines shown in collapsed state. */
    public static final int MAX_LINES = 4;

    /** Character threshold — texts longer than this always get "Read more". */
    public static final int MAX_CHARS = 350;

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
     * @param msg             The Message model (isExpanded field stores state)
     * @param adapter         The RecyclerView adapter (for notifyItemChanged)
     * @param adapterPosition Current adapter position (captured in closure)
     * @param sent            True if this is a sent message (affects link color)
     */
    public static void bind(
            @NonNull final TextView tv,
            @NonNull final Message  msg,
            @NonNull final RecyclerView.Adapter<?> adapter,
            final int adapterPosition,
            final boolean sent) {

        final String fullText = msg.text != null ? msg.text : "";

        // ── Long text: always needs expand/collapse ──
        if (fullText.length() > MAX_CHARS) {
            msg.isExpandable = true;
            if (msg.isExpanded) {
                applyExpanded(tv, msg, fullText, adapter, adapterPosition, sent);
            } else {
                applyCollapsed(tv, msg, fullText, adapter, adapterPosition, sent);
            }
            return;
        }

        // ── Short text: state already known from a previous bind ─────────────
        // Skip tv.post() entirely — apply directly to avoid RecyclerView
        // recycling races and the one-frame flicker of showing full text then
        // collapsing it. isExpandable is set to true permanently once discovered.
        if (msg.isExpandable) {
            if (msg.isExpanded) {
                applyExpanded(tv, msg, fullText, adapter, adapterPosition, sent);
            } else {
                applyCollapsed(tv, msg, fullText, adapter, adapterPosition, sent);
            }
            return;
        }

        // ── Short text: first bind — measure line count after layout pass ─────
        // Show full text first, then post to check whether it exceeds MAX_LINES.
        // This is the ONLY path that uses post(), and only runs once per message.
        tv.setMaxLines(Integer.MAX_VALUE);
        setTextWithLinks(tv, fullText, sent);

        tv.post(() -> {
            Layout layout = tv.getLayout();
            if (layout == null) return;
            if (layout.getLineCount() > MAX_LINES) {
                msg.isExpandable = true;
                if (!msg.isExpanded) {
                    applyCollapsed(tv, msg, fullText, adapter, adapterPosition, sent);
                }
            } else {
                // Short text that fits — mark as non-expandable so future
                // binds skip the post() and don't re-measure unnecessarily.
                // We use a negative sentinel: isExpandable stays false (default).
                // Nothing further to do.
            }
        });
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

        // Truncate at MAX_CHARS to ensure text fits in 4 lines visually
        String base = fullText.length() > MAX_CHARS
                ? fullText.substring(0, MAX_CHARS) : fullText;

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
}
