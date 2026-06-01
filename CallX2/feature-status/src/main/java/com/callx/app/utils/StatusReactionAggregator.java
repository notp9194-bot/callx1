package com.callx.app.utils;

import androidx.annotation.NonNull;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * StatusReactionAggregator — Aggregates, summarizes, and notifies about status reactions.
 *
 * Features:
 *   aggregate(item)       — count emoji frequencies from a StatusItem's reactions map
 *   loadReactions(...)    — fetch live reactions from Firebase for a specific status
 *   getTopReaction(item)  — most-used emoji (for compact display in viewer bottom bar)
 *   formatSummary(item)   — "❤️ 3  😂 2  👍 1"  or "❤️  😂  👍  +3"
 *   notifyOwner(...)      — fire reaction notification to status owner (once per reactor)
 *
 * Usage:
 *   // In StatusViewerActivity — reaction summary in "Seen by" row:
 *   String summary = StatusReactionAggregator.formatSummary(currentItem);
 *   tvReactionSummary.setText(summary);
 *
 *   // After user picks an emoji in the reaction sheet:
 *   StatusSeenTracker.reactTo(ownerUid, statusId, emoji);
 *   StatusReactionAggregator.notifyOwner(ctx, ownerUid, statusId, myUid, myName, myPhoto, emoji);
 */
public final class StatusReactionAggregator {

    private static final int MAX_DISPLAYED = 3; // show top 3 emoji in summary

    private StatusReactionAggregator() {}

    // ── Models ────────────────────────────────────────────────────────────

    public static class ReactionCount {
        public final String emoji;
        public final int    count;
        public ReactionCount(String emoji, int count) {
            this.emoji = emoji;
            this.count = count;
        }
    }

    public static class AggregatedReactions {
        /** Top N emoji sorted by frequency (descending) */
        public final List<ReactionCount> top;
        /** Total number of reactions */
        public final int total;
        /** Most frequent single emoji, or null if no reactions */
        public final String topEmoji;

        public AggregatedReactions(List<ReactionCount> top, int total) {
            this.top      = top;
            this.total    = total;
            this.topEmoji = top.isEmpty() ? null : top.get(0).emoji;
        }

        public boolean isEmpty() { return total == 0; }

        /**
         * Format a compact reaction bar string.
         * e.g.: "❤️ 3  😂 2  👍 1"
         * If more than MAX_DISPLAYED unique emoji, appends "+N more".
         */
        public String format() {
            if (top.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(top.size(), MAX_DISPLAYED);
            for (int i = 0; i < shown; i++) {
                if (sb.length() > 0) sb.append("  ");
                ReactionCount rc = top.get(i);
                sb.append(rc.emoji);
                if (rc.count > 1) sb.append(" ").append(rc.count);
            }
            int rest = top.size() - shown;
            if (rest > 0) sb.append("  +").append(rest);
            return sb.toString();
        }
    }

    // ── Aggregate from StatusItem.reactions map ────────────────────────────

    /**
     * Aggregate reactions from a StatusItem that has already been loaded from Firebase.
     * Returns an AggregatedReactions object suitable for display.
     */
    public static AggregatedReactions aggregate(StatusItem item) {
        if (item == null || item.reactions == null || item.reactions.isEmpty()) {
            return new AggregatedReactions(Collections.emptyList(), 0);
        }
        // Count emoji frequencies
        Map<String, Integer> counts = new HashMap<>();
        for (String emoji : item.reactions.values()) {
            if (emoji != null) counts.merge(emoji, 1, Integer::sum);
        }
        return buildResult(counts);
    }

    /**
     * Aggregate from a raw uid→emoji map (e.g., from a DataSnapshot).
     */
    public static AggregatedReactions aggregate(Map<String, String> reactions) {
        if (reactions == null || reactions.isEmpty()) {
            return new AggregatedReactions(Collections.emptyList(), 0);
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String emoji : reactions.values()) {
            if (emoji != null) counts.merge(emoji, 1, Integer::sum);
        }
        return buildResult(counts);
    }

    private static AggregatedReactions buildResult(Map<String, Integer> counts) {
        List<ReactionCount> list = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            list.add(new ReactionCount(e.getKey(), e.getValue()));
            total += e.getValue();
        }
        list.sort((a, b) -> b.count - a.count);
        return new AggregatedReactions(list, total);
    }

    // ── Live reaction fetch from Firebase ────────────────────────────────

    public interface ReactionsCallback {
        void onReactions(AggregatedReactions result);
    }

    /**
     * Fetch live reactions for a specific status from Firebase.
     */
    public static void loadReactions(String ownerUid, String statusId, ReactionsCallback cb) {
        if (ownerUid == null || statusId == null || cb == null) return;
        FirebaseUtils.getStatusRef()
            .child(ownerUid)
            .child(statusId)
            .child("reactions")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    Map<String, String> raw = new HashMap<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        String uid   = child.getKey();
                        String emoji = child.getValue(String.class);
                        if (uid != null && emoji != null) raw.put(uid, emoji);
                    }
                    cb.onReactions(aggregate(raw));
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    cb.onReactions(new AggregatedReactions(Collections.emptyList(), 0));
                }
            });
    }

    // ── Convenience helpers ───────────────────────────────────────────────

    /**
     * Return the most-frequent emoji for a status item, or null if no reactions.
     */
    public static String getTopReaction(StatusItem item) {
        return aggregate(item).topEmoji;
    }

    /**
     * Format a compact reaction summary string for display in viewer.
     * Returns "" if no reactions.
     */
    public static String formatSummary(StatusItem item) {
        return aggregate(item).format();
    }

    // ── Notify owner of new reaction ──────────────────────────────────────

    /**
     * Fire a reaction notification to the status owner.
     * Should be called after the reactor submits their emoji.
     * Dedup: checks "reactionNotified" flag to avoid repeat notifications
     * for the same viewer within 1 hour.
     */
    public static void notifyOwner(android.content.Context ctx,
                                    String ownerUid, String statusId,
                                    String reactorUid, String reactorName,
                                    String reactorPhoto, String emoji) {
        if (ownerUid == null || reactorUid == null || ownerUid.equals(reactorUid)) return;

        String dedupKey = "reactions/" + reactorUid + "/notifiedAt";
        FirebaseUtils.getStatusRef()
            .child(ownerUid)
            .child(statusId)
            .child(dedupKey)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    Long last = snap.getValue(Long.class);
                    long now  = System.currentTimeMillis();
                    if (last != null && (now - last) < 3_600_000L) return; // within 1h — skip

                    // Mark notified
                    snap.getRef().setValue(now);

                    // Post notification
                    StatusNotificationHelper.postStatusReactionNotification(
                        ctx, reactorUid, reactorName, reactorPhoto, emoji, ownerUid);

                    // Also push FCM via PushNotify if available
                    try {
                        com.callx.app.utils.PushNotify.notifyStatusReaction(
                            ownerUid, reactorUid,
                            reactorName != null ? reactorName : "Someone",
                            reactorPhoto != null ? reactorPhoto : "",
                            emoji,
                            ownerUid);
                    } catch (Exception ignored) {}
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {}
            });
    }
}
