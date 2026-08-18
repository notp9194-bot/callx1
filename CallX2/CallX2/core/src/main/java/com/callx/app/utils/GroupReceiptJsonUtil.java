package com.callx.app.utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Group tick system — small helper to serialize/deserialize the per-member
 * delivered/read receipt maps (uid → epoch-ms timestamp) between the
 * in-memory model (Message#deliveredBy / Message#readBy) and the JSON
 * string representation used for Room storage
 * (MessageEntity#groupDeliveredByJson / #groupReadByJson).
 *
 * Firebase already stores this shape natively at
 * groupMessages/{groupId}/{msgId}/deliveredBy|readBy/{uid} (a plain object,
 * one write per uid) — no conversion needed on that side. This util only
 * exists to carry the same map through Room, which only stores flat
 * columns, so the group Message Info dialog keeps showing per-member
 * receipts after the live listener round-trip and across app restarts.
 *
 * Kept dependency-free (org.json, same convention as ReactionJsonUtil /
 * EditHistoryJsonUtil / PollJsonUtil) so no Room TypeConverter registration
 * is needed.
 */
public final class GroupReceiptJsonUtil {

    private GroupReceiptJsonUtil() {}

    // ── Map<uid, epochMs> ↔ JSON object string ──────────────────────────────
    // e.g. {"uid1":1751234567890,"uid2":1751234599999}

    public static String receiptsToJson(Map<String, Long> receipts) {
        if (receipts == null || receipts.isEmpty()) return null;
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, Long> e : receipts.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                obj.put(e.getKey(), e.getValue());
            }
        } catch (JSONException ignored) {}
        return obj.length() > 0 ? obj.toString() : null;
    }

    public static Map<String, Long> receiptsFromJson(String json) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                long v = obj.optLong(k, -1);
                if (v >= 0) result.put(k, v);
            }
        } catch (JSONException ignored) {}
        return result;
    }
}
