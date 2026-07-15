package com.callx.app.community;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * v31: Multi-emoji reaction type constants and JSON serialization helpers.
 *
 * Stored in community_posts.reactionCountsJson as:
 *   {"LIKE":5,"LOVE":2,"HAHA":1}
 *
 * Firebase path:
 *   communities/{communityId}/posts/{postId}/reactionCounts/{REACTION_TYPE} -> Long count
 *   communities/{communityId}/posts/{postId}/reactions/{uid}               -> String reactionType
 */
public class CommunityReaction {

    public static final String LIKE  = "LIKE";
    public static final String LOVE  = "LOVE";
    public static final String HAHA  = "HAHA";
    public static final String WOW   = "WOW";
    public static final String SAD   = "SAD";
    public static final String ANGRY = "ANGRY";

    private static final String[] ALL_TYPES = { LIKE, LOVE, HAHA, WOW, SAD, ANGRY };

    /**
     * Return the display emoji for a reaction type.
     */
    @NonNull
    public static String getEmoji(@Nullable String type) {
        if (type == null) return "";
        switch (type) {
            case LIKE:  return "👍";
            case LOVE:  return "❤️";
            case HAHA:  return "😂";
            case WOW:   return "😮";
            case SAD:   return "😢";
            case ANGRY: return "😡";
            default:    return "👍";
        }
    }

    /**
     * Return a human label for a reaction type.
     */
    @NonNull
    public static String getLabel(@Nullable String type) {
        if (type == null) return "Like";
        switch (type) {
            case LIKE:  return "Like";
            case LOVE:  return "Love";
            case HAHA:  return "Haha";
            case WOW:   return "Wow";
            case SAD:   return "Sad";
            case ANGRY: return "Angry";
            default:    return "Like";
        }
    }

    /**
     * Serialize a reaction counts map to JSON string.
     * e.g. {"LIKE":5,"LOVE":2}
     */
    @NonNull
    public static String toJson(@Nullable Map<String, Long> counts) {
        if (counts == null || counts.isEmpty()) return "{}";
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    obj.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (JSONException ignored) {}
        return obj.toString();
    }

    /**
     * Deserialize a JSON string back to a reaction counts map.
     * Returns null if json is null or empty.
     */
    @Nullable
    public static Map<String, Long> fromJson(@Nullable String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) return null;
        Map<String, Long> result = new HashMap<>();
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long value = obj.getLong(key);
                if (value > 0) result.put(key, value);
            }
        } catch (JSONException e) {
            return null;
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Returns the total reaction count across all types.
     */
    public static long totalCount(@Nullable String json) {
        Map<String, Long> counts = fromJson(json);
        if (counts == null) return 0L;
        long total = 0L;
        for (Long v : counts.values()) if (v != null) total += v;
        return total;
    }

    /**
     * Returns a display string for the top 3 reaction emojis + total count.
     * e.g. "👍❤️😂 8"
     */
    @NonNull
    public static String buildSummary(@Nullable String json) {
        Map<String, Long> counts = fromJson(json);
        if (counts == null || counts.isEmpty()) return "";
        // Sort by count desc
        java.util.List<Map.Entry<String, Long>> entries = new java.util.ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(3, entries.size());
        for (int i = 0; i < limit; i++) sb.append(getEmoji(entries.get(i).getKey()));
        long total = 0L;
        for (Map.Entry<String, Long> e : counts.entrySet()) total += e.getValue();
        if (total > 0) sb.append(" ").append(total);
        return sb.toString();
    }
}
