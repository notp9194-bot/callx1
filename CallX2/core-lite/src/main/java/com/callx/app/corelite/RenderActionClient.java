package com.callx.app.corelite;

import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * HTTP replacement for the Firebase callable functions that now run on the
 * Render server. The Firebase ID token is still used for authentication; no
 * service-account credential is ever shipped in an APK.
 */
public final class RenderActionClient {
    public static final String SERVER_URL = "https://callx-server.onrender.com";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final Gson GSON = new Gson();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Result {
        void onSuccess(Map<String, Object> data);
        void onError(String message);
    }

    private RenderActionClient() {}

    public static void post(String endpoint, String action,
                            Map<String, Object> payload, Result result) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            deliverError(result, "Sign-in required.");
            return;
        }

        user.getIdToken(false)
            .addOnSuccessListener(tokenResult -> {
                String token = tokenResult == null ? null : tokenResult.getToken();
                if (token == null || token.isEmpty()) {
                    deliverError(result, "Authentication token unavailable.");
                    return;
                }

                Map<String, Object> requestMap = new HashMap<>();
                requestMap.put("action", action);
                requestMap.put("payload", payload == null ? new HashMap<>() : payload);
                Request request = new Request.Builder()
                    .url(SERVER_URL + endpoint)
                    .header("Authorization", "Bearer " + token)
                    .post(RequestBody.create(GSON.toJson(requestMap), JSON))
                    .build();

                HTTP.newCall(request).enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException error) {
                        deliverError(result, error.getMessage() == null
                            ? "Server request failed" : error.getMessage());
                    }

                    @Override public void onResponse(Call call, Response response) {
                        try (ResponseBody body = response.body()) {
                            String raw = body == null ? "" : body.string();
                            Map<String, Object> data = parseMap(raw);
                            if (!response.isSuccessful()) {
                                String message = data == null ? null : text(data.get("error"));
                                deliverError(result, message == null || message.isEmpty()
                                    ? "Server request failed (" + response.code() + ")" : message);
                                return;
                            }
                            deliverSuccess(result, data == null ? new HashMap<>() : data);
                        } catch (Exception error) {
                            deliverError(result, error.getMessage() == null
                                ? "Invalid server response" : error.getMessage());
                        }
                    }
                });
            })
            .addOnFailureListener(error -> deliverError(result,
                error.getMessage() == null ? "Authentication failed" : error.getMessage()));
    }

    public static void post(String endpoint, String action,
                            Map<String, Object> payload) {
        post(endpoint, action, payload, null);
    }

    private static Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new HashMap<>();
        return GSON.fromJson(raw, MAP_TYPE);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void deliverSuccess(Result result, Map<String, Object> data) {
        if (result != null) MAIN.post(() -> result.onSuccess(data));
    }

    private static void deliverError(Result result, String message) {
        if (result != null) MAIN.post(() -> result.onError(message));
    }
}