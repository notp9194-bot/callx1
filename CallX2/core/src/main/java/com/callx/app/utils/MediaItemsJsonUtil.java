package com.callx.app.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-media group feature — serializes/deserializes the
 * {@code List<Map<String,Object>>} mediaItems field between the in-memory
 * Message model and the JSON string representation used for Room storage
 * (MessageEntity#mediaItemsJson).
 *
 * Each item map typically has: "url", "thumbUrl" (optional),
 * "mediaType" ("image"|"video"), "duration" (optional display string),
 * "durationMs" (optional long), "fileSize" (optional long).
 *
 * Kept dependency-free (org.json) — no Room TypeConverter registration
 * needed, same pattern as PollJsonUtil / ReactionJsonUtil.
 */
public final class MediaItemsJsonUtil {

    private MediaItemsJsonUtil() {}

    public static String mediaItemsToJson(List<Map<String, Object>> items) {
        if (items == null) return null;
        JSONArray arr = new JSONArray();
        try {
            for (Map<String, Object> item : items) {
                if (item == null) continue;
                JSONObject obj = new JSONObject();
                for (Map.Entry<String, Object> e : item.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    obj.put(e.getKey(), e.getValue());
                }
                arr.put(obj);
            }
        } catch (JSONException ignored) {}
        return arr.toString();
    }

    public static List<Map<String, Object>> mediaItemsFromJson(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                java.util.Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    item.put(k, obj.opt(k));
                }
                result.add(item);
            }
        } catch (JSONException ignored) {}
        return result;
    }
}
