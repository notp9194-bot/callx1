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
 * ── Advanced polls: multi-select voting ─────────────────────────────────
 * A vote is now a *list* of option indices per voter (uid -> [idx, idx...])
 * instead of a single index, so a poll can let people tick more than one
 * option ("multiple choice" polls, gated by Message#pollMultiChoice).
 * Single-choice polls simply keep a one-element list per voter.
 *
 * Backward compatible: old data stored a plain int per uid
 * (e.g. {"uid1":0,"uid2":1}) — that's still read correctly and normalized
 * into a singleton list on load.
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

    // ── Votes: Map<uid, List<optionIndex>> ↔ JSON object string ────────────
    // Each voter maps to a JSON array of the option indices they ticked, e.g.
    // {"uid1":[0],"uid2":[0,2]}. A voter is dropped from the map entirely
    // once their list becomes empty (i.e. they untick their only vote).

    public static String votesToJson(Map<String, List<Integer>> votes) {
        if (votes == null) return null;
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, List<Integer>> e : votes.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                JSONArray arr = new JSONArray();
                for (Integer idx : e.getValue()) {
                    if (idx != null) arr.put(idx);
                }
                if (arr.length() > 0) obj.put(e.getKey(), arr);
            }
        } catch (JSONException ignored) {}
        return obj.toString();
    }

    public static Map<String, List<Integer>> votesFromJson(String json) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONObject obj = new JSONObject(json);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object raw = obj.opt(k);
                List<Integer> list = new ArrayList<>();
                if (raw instanceof JSONArray) {
                    JSONArray arr = (JSONArray) raw;
                    for (int i = 0; i < arr.length(); i++) {
                        list.add(arr.optInt(i, -1));
                    }
                } else {
                    // Legacy format: plain int per uid (single-choice only).
                    int legacy = obj.optInt(k, -1);
                    if (legacy >= 0) list.add(legacy);
                }
                if (!list.isEmpty()) result.put(k, list);
            }
        } catch (JSONException ignored) {}
        return result;
    }

    // ── Vote counting helpers ───────────────────────────────────────────────

    /** Returns tick count per option index, sized to optionCount. A single
     *  voter who ticked 2 options contributes 1 to each of those 2 counts. */
    public static int[] countVotes(Map<String, List<Integer>> votes, int optionCount) {
        int[] counts = new int[Math.max(optionCount, 0)];
        if (votes == null) return counts;
        for (List<Integer> indices : votes.values()) {
            if (indices == null) continue;
            for (Integer idx : indices) {
                if (idx != null && idx >= 0 && idx < counts.length) counts[idx]++;
            }
        }
        return counts;
    }

    /** Number of distinct people who voted (not the number of ticks). */
    public static int totalVotes(Map<String, List<Integer>> votes) {
        return votes == null ? 0 : votes.size();
    }

    /** Convenience: did this uid tick the given option index? */
    public static boolean hasTicked(Map<String, List<Integer>> votes, String uid, int optionIndex) {
        if (votes == null || uid == null) return false;
        List<Integer> mine = votes.get(uid);
        return mine != null && mine.contains(optionIndex);
    }
}
