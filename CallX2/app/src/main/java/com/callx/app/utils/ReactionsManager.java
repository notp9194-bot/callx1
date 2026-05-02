package com.callx.app.utils;

import com.callx.app.models.Message;
import com.google.firebase.database.DatabaseReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature 13: Multiple Reactions per User
 *
 * Firebase schema:
 *   messages/{chatId}/{msgId}/reactions/{uid}/{emoji} = true
 *
 * (Changed from uid→String to uid→Map<emoji,true> to allow multiple reactions.)
 *
 * Helper provides:
 *  - Toggle / add / remove individual emoji for a uid
 *  - Aggregate counts: emoji → count
 *  - Check if a uid has reacted with a specific emoji
 */
public class ReactionsManager {

    // ── Toggle ─────────────────────────────────────────────────────────────

    /**
     * Toggle an emoji reaction for the current user.
     * If the user already reacted with this emoji, remove it.
     * Otherwise add it (users can have multiple different emojis on same message).
     */
    public static void toggleReaction(DatabaseReference msgRef,
                                      String currentUid,
                                      String emoji,
                                      Message message) {
        DatabaseReference reactionRef = msgRef
                .child("reactions")
                .child(currentUid)
                .child(emoji);

        boolean alreadyReacted = hasReaction(message, currentUid, emoji);
        if (alreadyReacted) {
            reactionRef.removeValue();
        } else {
            reactionRef.setValue(true);
        }
    }

    // ── Aggregate ──────────────────────────────────────────────────────────

    /**
     * Compute emoji → count from a message's reactions map.
     * Returns ordered by count descending.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Integer> aggregateCounts(Message message) {
        Map<String, Integer> counts = new HashMap<>();
        if (message.reactions == null) return counts;
        for (Map.Entry<String, Object> userEntry : message.reactions.entrySet()) {
            Object val = userEntry.getValue();
            if (val instanceof Map) {
                for (String emoji : ((Map<String, Object>) val).keySet()) {
                    counts.put(emoji, counts.getOrDefault(emoji, 0) + 1);
                }
            } else if (val instanceof String) {
                // Legacy single-emoji format
                String emoji = (String) val;
                counts.put(emoji, counts.getOrDefault(emoji, 0) + 1);
            }
        }
        // Sort by count descending
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : list) ordered.put(e.getKey(), e.getValue());
        return ordered;
    }

    /** Summary string for display, e.g. "👍3 ❤️2" */
    public static String summaryString(Message message) {
        Map<String, Integer> counts = aggregateCounts(message);
        if (counts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append(e.getKey()).append(e.getValue()).append(" ");
        }
        return sb.toString().trim();
    }

    /** All emojis the currentUser has used on this message. */
    @SuppressWarnings("unchecked")
    public static List<String> myReactions(Message message, String uid) {
        List<String> result = new ArrayList<>();
        if (message.reactions == null) return result;
        Object val = message.reactions.get(uid);
        if (val instanceof Map) {
            result.addAll(((Map<String, Object>) val).keySet());
        } else if (val instanceof String) {
            result.add((String) val);
        }
        return result;
    }

    public static boolean hasReaction(Message message, String uid, String emoji) {
        return myReactions(message, uid).contains(emoji);
    }

    // ── Who reacted (for SeenBy-style sheet) ──────────────────────────────

    @SuppressWarnings("unchecked")
    public static Map<String, List<String>> whoReacted(Message message) {
        // emoji → list of UIDs
        Map<String, List<String>> result = new HashMap<>();
        if (message.reactions == null) return result;
        for (Map.Entry<String, Object> userEntry : message.reactions.entrySet()) {
            String uid  = userEntry.getKey();
            Object val  = userEntry.getValue();
            List<String> emojis = new ArrayList<>();
            if (val instanceof Map) {
                emojis.addAll(((Map<String, Object>) val).keySet());
            } else if (val instanceof String) {
                emojis.add((String) val);
            }
            for (String emoji : emojis) {
                result.computeIfAbsent(emoji, k -> new ArrayList<>()).add(uid);
            }
        }
        return result;
    }
}
