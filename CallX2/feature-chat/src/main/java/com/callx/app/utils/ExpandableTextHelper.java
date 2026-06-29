package com.callx.app.utils;

import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.util.Linkify;
import android.text.method.LinkMovementMethod;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ExpandableTextHelper — WhatsApp-style "Read more / Read less" for chat messages.
 *
 * ARCHITECTURE (v3 — fully reliable):
 *
 *   ┌─────────────────────────────────────────────────────────────────────────┐
 *   │  Problem 1: ClickableSpan inside RecyclerView is unreliable because    │
 *   │  itemView.OnClickListener and the RecyclerView's scroll machinery can  │
 *   │  intercept touch events before LinkMovementMethod sees them.           │
 *   │                                                                        │
 *   │  Solution: separate tv_read_more TextView button — it is a plain      │
 *   │  view with setOnClickListener, 100% reliable regardless of parent.    │
 *   │                                                                        │
 *   │  Problem 2: msg.isExpanded is a transient field on the Message model.  │
 *   │  PagingDataAdapter may fetch a fresh Message from Room after a DB      │
 *   │  change (new incoming message, status update…).  The fresh object has  │
 *   │  isExpanded = false, so the message collapses instantly — looks like   │
 *   │  "click does nothing".                                                 │
 *   │                                                                        │
 *   │  Solution: caller passes isExpanded + Runnable callbacks.  The adapter │
 *   │  stores expansion state in its own HashSet<String> expandedMessageIds, │
 *   │  keyed by messageId.  That set is NOT re-created when new PagingData  │
 *   │  is submitted, so expansion survives all Room refreshes.              │
 *   └─────────────────────────────────────────────────────────────────────────┘
 *
 * Layout contract:
 *   • tv_message  — the main message TextView (inside FrameLayout)
 *   • tv_read_more — a dedicated Button-like TextView BELOW the FrameLayout,
 *     inside ll_bubble.  Styled "#64B5F6 bold 13sp". visibility="gone" by default.
 *
 * Usage in bindMessage():
 *   String msgId = ...;
 *   boolean expanded = expandedMessageIds.contains(msgId);
 *   ExpandableTextHelper.bind(
 *       h.tvMessage, h.tvReadMore,
 *       m.text, m, msgId, expanded,
 *       () -> { expandedMessageIds.add(msgId);    notifyItemChanged(pos); },
 *       () -> { expandedMessageIds.remove(msgId); notifyItemChanged(pos); },
 *       isSentMsg);
 */
public final class ExpandableTextHelper {

    /** Lines shown before "Read more" appears. */
    public static final int MAX_LINES = 5;

    /** Char count above which we ALWAYS collapse without needing a layout pass. */
    public static final int MAX_CHARS = 350;

    /** Explicit newline count above which we ALWAYS collapse without a layout pass. */
    private static final int MAX_NEWLINES = MAX_LINES;

    private ExpandableTextHelper() {}

    /**
     * Bind a text message with expand/collapse support.
     *
     * @param tvMessage   Main message TextView
     * @param tvReadMore  Dedicated "Read more / Read less" button (may be null — gracefully skipped)
     * @param rawText     Raw message text (may be null — treated as "")
     * @param msgRef      Message object — only used to cache isExpandable hint (transient field is ok)
     * @param msgId       Stable unique ID used for staleness guard on post()
     * @param isExpanded  Current expansion state (from adapter's expandedMessageIds)
     * @param onExpand    Runnable: called when user taps "Read more" — add msgId to set + notify
     * @param onCollapse  Runnable: called when user taps "Read less"  — remove from set + notify
     * @param sent        True = sent bubble (affects link color)
     */
    public static void bind(
            @NonNull  TextView tvMessage,
            @Nullable TextView tvReadMore,
            @Nullable String rawText,
            @NonNull  com.callx.app.models.Message msgRef,
            @Nullable String msgId,
            boolean isExpanded,
            @NonNull  Runnable onExpand,
            @NonNull  Runnable onCollapse,
            boolean sent) {

        final String text = rawText != null ? rawText : "";

        // ── Determine collapse requirement ────────────────────────────────
        int newlines = countNewlines(text);
        boolean definitelyNeeds =
                text.length() > MAX_CHARS || newlines >= MAX_NEWLINES;

        // ── Already-known short expandable (isExpandable hint) ────────────
        // isExpandable is transient; if the Message object was freshly created
        // from Room it resets to false — but then we fall to the post() path
        // which re-measures and sets it again (one extra frame, harmless).
        boolean knownNeedsFromPrior = msgRef.isExpandable;

        if (definitelyNeeds || knownNeedsFromPrior) {
            msgRef.isExpandable = true;
            applyState(tvMessage, tvReadMore, text, isExpanded, onExpand, onCollapse, sent);
            return;
        }

        // ── Short text: show in full, then measure after layout ───────────
        tvMessage.setMaxLines(Integer.MAX_VALUE);
        tvMessage.setEllipsize(null);
        applyTextWithLinks(tvMessage, text, sent);
        if (tvReadMore != null) tvReadMore.setVisibility(View.GONE);

        // Staleness guard: tag the view with msgId (or hash fallback).
        // If the ViewHolder is recycled before post() fires, getTag() won't
        // match and we safely skip — no wrong-message collapse.
        final String guardKey = msgId != null ? msgId : "h_" + text.hashCode();
        tvMessage.setTag(guardKey);

        tvMessage.post(() -> {
            if (!guardKey.equals(tvMessage.getTag())) return; // recycled — skip
            Layout layout = tvMessage.getLayout();
            if (layout == null) {
                // One more frame — view not yet drawn (rare; retry once)
                tvMessage.post(() -> {
                    if (!guardKey.equals(tvMessage.getTag())) return;
                    Layout l2 = tvMessage.getLayout();
                    if (l2 == null) return;
                    if (l2.getLineCount() > MAX_LINES) {
                        msgRef.isExpandable = true;
                        applyState(tvMessage, tvReadMore, text, isExpanded,
                                   onExpand, onCollapse, sent);
                    }
                });
                return;
            }
            if (layout.getLineCount() > MAX_LINES) {
                msgRef.isExpandable = true;
                applyState(tvMessage, tvReadMore, text, isExpanded,
                           onExpand, onCollapse, sent);
            }
            // else: text fits — nothing to do, tvReadMore already GONE
        });
    }

    // ── Apply collapsed or expanded state ─────────────────────────────────────

    private static void applyState(
            @NonNull  TextView tvMessage,
            @Nullable TextView tvReadMore,
            @NonNull  String text,
            boolean isExpanded,
            @NonNull  Runnable onExpand,
            @NonNull  Runnable onCollapse,
            boolean sent) {

        if (isExpanded) {
            // ── Expanded — show full text, button says "Read less" ────────
            tvMessage.setMaxLines(Integer.MAX_VALUE);
            tvMessage.setEllipsize(null);
            applyTextWithLinks(tvMessage, text, sent);

            if (tvReadMore != null) {
                tvReadMore.setVisibility(View.VISIBLE);
                tvReadMore.setText("Read less ▲");
                tvReadMore.setOnClickListener(v -> onCollapse.run());
            }
        } else {
            // ── Collapsed — clip to MAX_LINES, button says "Read more" ────
            tvMessage.setMaxLines(MAX_LINES);
            tvMessage.setEllipsize(android.text.TextUtils.TruncateAt.END);
            applyTextWithLinks(tvMessage, text, sent);

            if (tvReadMore != null) {
                tvReadMore.setVisibility(View.VISIBLE);
                tvReadMore.setText("Read more ▼");
                tvReadMore.setOnClickListener(v -> onExpand.run());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Set text with Linkify where needed. */
    public static void applyTextWithLinks(
            @NonNull TextView tv, @NonNull String text, boolean sent) {
        if (mightHaveLink(text)) {
            SpannableStringBuilder ssb = new SpannableStringBuilder(text);
            Linkify.addLinks(ssb,
                    Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES | Linkify.PHONE_NUMBERS);
            tv.setLinkTextColor(sent ? 0xFFB3E5FC : 0xFF90CAF9);
            tv.setMovementMethod(LinkMovementMethod.getInstance());
            tv.setHighlightColor(Color.TRANSPARENT);
            tv.setText(ssb);
        } else {
            tv.setMovementMethod(null);
            tv.setText(text);
        }
    }

    private static boolean mightHaveLink(@NonNull String text) {
        return text.contains("http://")
                || text.contains("https://")
                || text.contains("www.")
                || text.contains("@")
                || (text.length() >= 7 && text.contains("+"));
    }

    private static int countNewlines(@NonNull String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') n++;
        }
        return n;
    }
}
