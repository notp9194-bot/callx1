package com.callx.app.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Poll feature — small helper to serialize/deserialize poll options and
 * votes between the in-memory model (List / Map) and the JSON string
 * representation used for Room storage (pollOptionsJson / pollVotesJson)
 * and Firebase compatibility.
 *
 * Kept dependency-free (org.json, already used elsewhere in the codebase)
 * so we don't need a Room TypeConverter registration.
 */
public final class PollJsonUtil {

    private PollJsonUtil() {}

    // ── Options: List<String> ↔ JSON array string ──────────────────────────

    public static String optionsToJson(List<String> options) {
        if (options == null) return null;
        JSONArray arr = new JSONArray();
        for (String o : options) arr.put(o != null ? o : "");
        return arr.toString();
    }

    public static List<String> optionsFromJson(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.optString(i, ""));
            }
        } catch (JSONException ignored) {}
        return result;
    }

    // ── Votes: Map<uid, optionIndex> ↔ JSON object string ──────────────────

    public static String votesToJson(Map<String, Integer> votes) {
        if (votes == null) return null;
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, Integer> e : votes.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                obj.put(e.getKey(), e.getValue());
            }
        } catch (JSONException ignored) {}
        return obj.toString();
    }

    public static Map<String, Integer> votesFromJson(String json) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONObject obj = new JSONObject(json);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                result.put(k, obj.optInt(k, 0));
            }
        } catch (JSONException ignored) {}
        return result;
    }

    // ── Vote counting helpers ───────────────────────────────────────────────

    /** Returns vote count per option index, sized to optionCount. */
    public static int[] countVotes(Map<String, Integer> votes, int optionCount) {
        int[] counts = new int[Math.max(optionCount, 0)];
        if (votes == null) return counts;
        for (Integer idx : votes.values()) {
            if (idx != null && idx >= 0 && idx < counts.length) counts[idx]++;
        }
        return counts;
    }

    public static int totalVotes(Map<String, Integer> votes) {
        return votes == null ? 0 : votes.size();
    }
}
