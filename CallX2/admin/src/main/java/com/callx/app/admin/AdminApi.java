package com.callx.app.admin;

import androidx.annotation.NonNull;

import com.callx.app.corelite.RenderActionClient;

import java.util.HashMap;
import java.util.Map;

/**
 * The admin client deliberately uses one callable backend boundary for
 * privileged work. Do not move these operations to direct RTDB writes:
 * Firebase Admin SDK is the only safe place to revoke sessions, delete Auth
 * accounts, fan out push notifications, and read report trees that are
 * write-only for normal clients.
 */
public final class AdminApi {
    private AdminApi() {}

    public interface Callback {
        void onSuccess(Object data);
        void onError(String message);
    }

    public static void call(String action, Callback callback) {
        call(action, new HashMap<>(), callback);
    }

    public static void call(String action, Map<String, Object> payload, Callback callback) {
        Map<String, Object> request = new HashMap<>();
        request.put("action", action);
        request.put("payload", payload == null ? new HashMap<>() : payload);

        RenderActionClient.post("/admin/action", action, payload,
            new RenderActionClient.Result() {
                @Override public void onSuccess(Map<String, Object> data) {
                    callback.onSuccess(data);
                }
                @Override public void onError(String message) {
                    callback.onError(message == null ? "Admin operation failed" : message);
                }
            });
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new HashMap<>();
    }

    public static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    public static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}