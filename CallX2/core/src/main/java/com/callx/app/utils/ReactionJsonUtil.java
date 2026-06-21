package com.callx.app.utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emoji reactions — small helper to serialize/deserialize the per-message
 * reaction map (uid → emoji) between the in-memory model
 * (Message#reactions / Map<String,String>) and the JSON string
 * representation used for Room storage (MessageEntity#reactionsJson).
 *
 * Firebase already stores this shape natively at messages/{id}/reactions/{uid}
 * (a plain object, one write per uid) — no conversion needed on that side.
 * This util only exists to carry the same map through Room, which only
 * stores flat columns, so the paging adapter (which reads from Room, not
 * straight from Firebase) keeps showing reactions after the live listener
 * round-trip and across app restarts.
 *
 * Kept dependency-free (org.json, same convention as EditHistoryJsonUtil /
 * PollJsonUtil) so no Room TypeConverter registration is needed.
 */
public final class ReactionJsonUtil {

    private ReactionJsonUtil() {}

    // ── Map<uid, emoji> ↔ JSON object string ────────────────────────────────
    // e.g. {"uid1":"❤️","uid2":"👍"}

    public static String reactionsToJson(Map<String, String> reactions) {
        if (reactions == null || reactions.isEmpty()) return null;
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, String> e : reactions.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                obj.put(e.getKey(), e.getValue());
            }
        } catch (JSONException ignored) {}
        return obj.length() > 0 ? obj.toString() : null;
    }

    public static Map<String, String> reactionsFromJson(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = obj.optString(k, null);
                if (v != null) result.put(k, v);
            }
        } catch (JSONException ignored) {}
        return result;
    }
}
