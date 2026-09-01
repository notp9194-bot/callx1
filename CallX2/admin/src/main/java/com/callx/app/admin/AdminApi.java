package com.callx.app.admin;

import androidx.annotation.NonNull;

import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;

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

        FirebaseFunctions.getInstance()
            .getHttpsCallable("adminAction")
            .call(request)
            .addOnSuccessListener(result -> {
                Object data = result == null ? null : result.getData();
                callback.onSuccess(data);
            })
            .addOnFailureListener(error -> callback.onError(
                error.getMessage() == null ? "Admin operation failed" : error.getMessage()));
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