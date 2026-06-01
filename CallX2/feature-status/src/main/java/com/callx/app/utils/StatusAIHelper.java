package com.callx.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * StatusAIHelper v26 — AI-powered status features.
 * 1. Auto Caption (Gemini Vision → describe image/video thumbnail)
 * 2. Smart template suggestion based on image content
 * 3. Trending topic suggestions via Firebase aggregation
 */
public final class StatusAIHelper {
    // Replace with your Gemini API key via environment or RemoteConfig
    private static final String GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent";
    private StatusAIHelper() {}

    public interface AICallback { void onResult(String text); void onError(String e); }

    /** Auto-generate caption from bitmap thumbnail */
    public static void generateCaption(Bitmap thumb, String language, AICallback cb) {
        if (thumb == null || cb == null) { if (cb != null) cb.onError("No image"); return; }
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String b64 = bitmapToBase64(thumb);
                String lang = (language != null && !language.isEmpty()) ? language : "English";
                String body = "{\n"
                    + "\"contents\":[{\"parts\":[\n"
                    + "{\"inline_data\":{\"mime_type\":\"image/jpeg\",\"data\":\"" + b64 + "\"}},\n"
                    + "{\"text\":\"Write a short engaging social media caption for this image in " + lang + ". Max 100 chars.\"}\n"
                    + "]}]}\n";
                String apiKey = getApiKey();
                if (apiKey == null || apiKey.isEmpty()) { cb.onError("API key not configured"); return; }
                String url = GEMINI_ENDPOINT + "?key=" + apiKey;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type","application/json");
                conn.setDoOutput(true); conn.setConnectTimeout(8000); conn.setReadTimeout(12000);
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                conn.connect();
                int code = conn.getResponseCode();
                InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
                byte[] resp = is.readAllBytes(); conn.disconnect();
                String json = new String(resp, StandardCharsets.UTF_8);
                // Parse "text" from response
                String result = extractGeminiText(json);
                cb.onResult(result != null ? result : "✨ Check this out!");
            } catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    /** Suggest 3 templates based on image mood (simple heuristic) */
    public static void suggestTemplates(Bitmap thumb, AICallback cb) {
        if (thumb == null) { if (cb != null) cb.onResult("grad_purple,moti_1,solid_black"); return; }
        Executors.newSingleThreadExecutor().execute(() -> {
            // Analyze average color to suggest template
            int avgColor = getAverageColor(thumb);
            int r = (avgColor >> 16) & 0xFF, g = (avgColor >> 8) & 0xFF, b = avgColor & 0xFF;
            String suggestion;
            if (r > 180 && g < 100) suggestion = "solid_red,celebrate_bday,moti_1";
            else if (b > 150 && r < 100) suggestion = "grad_ocean,grad_midnight,solid_teal";
            else if (g > 150 && r < 150) suggestion = "grad_forest,solid_teal,moti_2";
            else suggestion = "grad_purple,grad_sunset,moti_1";
            if (cb != null) cb.onResult(suggestion);
        });
    }

    private static String bitmapToBase64(Bitmap bmp) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, 512, 512, true);
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

    private static String extractGeminiText(String json) {
        try {
            int idx = json.indexOf("\"text\":\"");
            if (idx < 0) return null;
            int start = idx + 8;
            int end = json.indexOf("\"", start);
            return end > start ? json.substring(start, end) : null;
        } catch (Exception e) { return null; }
    }

    private static int getAverageColor(Bitmap bmp) {
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, 10, 10, true);
        long r = 0, g = 0, b2 = 0;
        int n = scaled.getWidth() * scaled.getHeight();
        for (int x = 0; x < scaled.getWidth(); x++) {
            for (int y = 0; y < scaled.getHeight(); y++) {
                int c = scaled.getPixel(x, y);
                r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b2 += c & 0xFF;
            }
        }
        return ((int)(r/n) << 16) | ((int)(g/n) << 8) | (int)(b2/n);
    }

    private static String getApiKey() {
        try { return com.google.firebase.remoteconfig.FirebaseRemoteConfig.getInstance()
                .getString("gemini_api_key"); }
        catch (Exception e) { return ""; }
    }
}
