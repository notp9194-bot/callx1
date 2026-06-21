package com.callx.app.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Message Edit History — small helper to serialize/deserialize the list of
 * previous text versions of an edited message between the in-memory model
 * (List<Map<String,Object>>, each map holding "text" + "editedAt") and the
 * JSON string representation used for Room storage (editHistoryJson) and
 * Firebase compatibility.
 *
 * Each entry represents what the message text WAS *before* a given edit:
 *   [{"text":"original text","editedAt":1718000000000},
 *    {"text":"second version","editedAt":1718000050000}]
 *
 * The CURRENT text lives in Message#text as always — this list only ever
 * holds prior versions, oldest first. A message edited twice has 2 entries
 * here; a never-edited message has a null/empty list.
 *
 * Kept dependency-free (org.json, already used elsewhere in the codebase —
 * see PollJsonUtil) so no Room TypeConverter registration is needed.
 */
public final class EditHistoryJsonUtil {

    private EditHistoryJsonUtil() {}

    private static final String KEY_TEXT      = "text";
    private static final String KEY_EDITED_AT = "editedAt";

    // ── List<Map<String,Object>> ↔ JSON array string ────────────────────────

    public static String historyToJson(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return null;
        JSONArray arr = new JSONArray();
        try {
            for (Map<String, Object> entry : history) {
                if (entry == null) continue;
                JSONObject obj = new JSONObject();
                Object text = entry.get(KEY_TEXT);
                Object at   = entry.get(KEY_EDITED_AT);
                obj.put(KEY_TEXT, text != null ? text.toString() : "");
                obj.put(KEY_EDITED_AT, at instanceof Number ? ((Number) at).longValue() : 0L);
                arr.put(obj);
            }
        } catch (JSONException ignored) {}
        return arr.length() > 0 ? arr.toString() : null;
    }

    public static List<Map<String, Object>> historyFromJson(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put(KEY_TEXT, obj.optString(KEY_TEXT, ""));
                entry.put(KEY_EDITED_AT, obj.optLong(KEY_EDITED_AT, 0L));
                result.add(entry);
            }
        } catch (JSONException ignored) {}
        return result;
    }

    // ── Convenience: append the text that's about to be overwritten ────────
    // Call BEFORE overwriting Message#text with the new value — pass the
    // OLD (pre-edit) text and the existing history list (may be null).
    // Returns a new list with the old version appended at the end (newest
    // prior-version last, original-text first).

    public static List<Map<String, Object>> appendVersion(
            List<Map<String, Object>> existing, String oldText, long editedAt) {
        List<Map<String, Object>> updated = existing != null
                ? new ArrayList<>(existing) : new ArrayList<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(KEY_TEXT, oldText != null ? oldText : "");
        entry.put(KEY_EDITED_AT, editedAt);
        updated.add(entry);
        return updated;
    }

    public static String textOf(Map<String, Object> entry) {
        Object t = entry.get(KEY_TEXT);
        return t != null ? t.toString() : "";
    }

    public static long editedAtOf(Map<String, Object> entry) {
        Object a = entry.get(KEY_EDITED_AT);
        return a instanceof Number ? ((Number) a).longValue() : 0L;
    }
}
