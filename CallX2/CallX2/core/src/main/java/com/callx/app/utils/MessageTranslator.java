package com.callx.app.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * MessageTranslator — translates a single chat message's text via the
 * app's own backend (Constants.SERVER_URL + "/translate"), which proxies
 * to Google's free translate endpoint. No API key / billing needed.
 *
 * NOTE: this is unrelated to the Cloudinary "Google Translation" add-on —
 * that one only translates Cloudinary asset tags, not free-form chat text.
 */
public class MessageTranslator {

    private static final String TAG = "MessageTranslator";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20,   TimeUnit.SECONDS)
        .build();

    public interface Callback {
        /** @param detectedLang source language Google detected, e.g. "hi" (may be empty) */
        void onSuccess(String translatedText, String detectedLang);
        void onError(String message);
    }

    /**
     * Translates {@code text} into {@code targetLangCode} (ISO-639-1, e.g.
     * "en", "hi", "es"). Callback always fires on the main thread.
     */
    public static void translate(String text, String targetLangCode, Callback cb) {
        if (text == null || text.trim().isEmpty()) {
            MAIN.post(() -> cb.onError("Nothing to translate"));
            return;
        }
        final String target = (targetLangCode == null || targetLangCode.trim().isEmpty())
            ? "en" : targetLangCode;

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("text", text);
                payload.put("target", target);

                Request req = new Request.Builder()
                    .url(Constants.SERVER_URL + "/translate")
                    .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                    .build();

                Response res = HTTP.newCall(req).execute();
                String body = res.body() != null ? res.body().string() : "";
                res.close();

                if (!res.isSuccessful()) {
                    Log.w(TAG, "Translate failed (" + res.code() + "): " + body);
                    MAIN.post(() -> cb.onError("Translate failed (" + res.code() + ")"));
                    return;
                }

                JSONObject j = new JSONObject(body);
                String translated   = j.optString("translated", "");
                String detectedLang = j.optString("detectedLang", "");

                if (translated.isEmpty()) {
                    MAIN.post(() -> cb.onError("No translation returned"));
                    return;
                }
                MAIN.post(() -> cb.onSuccess(translated, detectedLang));

            } catch (Exception e) {
                Log.e(TAG, "translate error", e);
                MAIN.post(() -> cb.onError(e.getMessage() != null ? e.getMessage() : "Translate error"));
            }
        }).start();
    }

    private MessageTranslator() {}
}
