package com.callx.app.voicerooms;

import com.callx.app.corelite.RenderActionClient;

import java.util.HashMap;
import java.util.Map;

/**
 * VrApi — Render server calls (Firebase ID token ke saath, RenderActionClient se).
 * Server hi permissions decide karta hai: app ke dabane se kuch nahi milta.
 * Callbacks main thread par aate hain.
 */
final class VrApi {
    private VrApi() {}

    interface Done { void onDone(Map<String, Object> data); void onError(String message); }

    private static void post(String endpoint, String action, Map<String, Object> payload, Done cb) {
        RenderActionClient.post(endpoint, action, payload, new RenderActionClient.Result() {
            @Override public void onSuccess(Map<String, Object> data) { if (cb != null) cb.onDone(data); }
            @Override public void onError(String message) { if (cb != null) cb.onError(message); }
        });
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static void createRoom(String title, String topic, String description, String category,
                           boolean allowRequests, Done cb) {
        post("/voiceroom/action", "create", map("title", title, "topic", topic,
                "description", description, "category", category,
                "allowRequests", allowRequests), cb);
    }

    static void endRoom(String roomId, Done cb) {
        post("/voiceroom/action", "end", map("roomId", roomId), cb);
    }

    static void leaveRoom(String roomId, Done cb) {
        post("/voiceroom/action", "leave", map("roomId", roomId), cb);
    }

    /** Host actions: promote | demote | mute | unmute | kick. */
    static void hostAction(String action, String roomId, String targetUid, Done cb) {
        post("/voiceroom/action", action, map("roomId", roomId, "targetUid", targetUid), cb);
    }

    static void token(String roomId, Done cb) {
        post("/voiceroom/token", "token", map("roomId", roomId), cb);
    }

    static void gift(String roomId, String giftId, String toUid, Done cb) {
        Map<String, Object> p = map("roomId", roomId, "giftId", giftId);
        if (toUid != null) p.put("toUid", toUid);
        post("/voiceroom/gift", "gift", p, cb);
    }

    static String str(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        return o == null ? "" : String.valueOf(o);
    }
}
