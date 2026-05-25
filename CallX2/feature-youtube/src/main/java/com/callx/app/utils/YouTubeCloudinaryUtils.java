package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

// core module utilities (same base package — explicit imports for clarity & IDE support)
import com.callx.app.utils.VideoCompressor;
import com.callx.app.utils.VideoQualityPreferences;
import com.callx.app.utils.ImageCompressor;
import com.callx.app.utils.Constants;

/**
 * YouTubeCloudinaryUtils — Cloudinary upload helper for YouTube module.
 *
 * SIGNED UPLOAD (same as core CloudinaryUploader):
 *   Step 1 → GET signature from server  (Constants.SERVER_URL + /cloudinary/sign)
 *   Step 2 → POST file directly to Cloudinary using signature + api_key + timestamp
 *   No upload_preset needed — server signs with Cloudinary API secret.
 *
 * Media compression:
 *   uploadVideo() → VideoCompressor (STANDARD)  → signed Cloudinary upload
 *   uploadImage() → ImageCompressor (WebP 1280px) → signed Cloudinary upload
 *
 * Progress:
 *   Video:  0–40% compression  |  40–100% upload
 *   Image:  0–20% compression  |  20–100% upload
 */
public class YouTubeCloudinaryUtils {

    private static final String TAG = "YTCloudinaryUtils";

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build();

    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ── Callback ─────────────────────────────────────────────────────────────

    public interface UploadCallback {
        void onProgress(int percent);
        /** @param durationSecs video duration in seconds (0 for images) */
        void onSuccess(String secureUrl, String publicId, long durationSecs);
        void onError(String errorMsg);
    }

    // ── Upload Video ─────────────────────────────────────────────────────────

    public static void uploadVideo(Context ctx, Uri videoUri,
                                   String folder, UploadCallback cb) {
        new Thread(() -> {
            try {
                // Step 1: Compress (0–40%)
                postProgress(cb, 2);
                Log.d(TAG, "Compressing video...");
                VideoCompressor.Result compressed = VideoCompressor.compressSync(
                    ctx, videoUri,
                    VideoQualityPreferences.Quality.STANDARD,
                    pct -> postProgress(cb, (int)(pct * 0.40f))
                );
                Log.d(TAG, "Video compressed: " + compressed.compressionSummary());
                postProgress(cb, 40);

                // Step 2: Sign (from server)
                String ytFolder = "youtube/" + folder;
                JSONObject signJson = getSignature(ytFolder, "video");
                if (signJson == null) { postError(cb, "Server se signature nahi mila. Server check karo."); return; }

                postProgress(cb, 45);

                // Step 3: Upload to Cloudinary
                File videoFile = compressed.videoFile;
                String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
                String upUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/video/upload";

                MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", videoFile.getName(),
                        RequestBody.create(videoFile, MediaType.parse("video/mp4")))
                    .addFormDataPart("api_key",   signJson.getString("api_key"))
                    .addFormDataPart("timestamp", signJson.getString("timestamp"))
                    .addFormDataPart("signature", signJson.getString("signature"))
                    .addFormDataPart("folder",    signJson.optString("folder", ytFolder))
                    .build();

                Request req = new Request.Builder().url(upUrl).post(body).build();
                try (Response resp = client.newCall(req).execute()) {
                    String json = resp.body() != null ? resp.body().string() : "";
                    Log.d(TAG, "Video upload response: " + json);
                    JSONObject obj = new JSONObject(json);
                    if (obj.has("secure_url")) {
                        postProgress(cb, 100);
                        long durSecs = Math.round(obj.optDouble("duration", 0));
                        postSuccess(cb, obj.getString("secure_url"), obj.optString("public_id", ""), durSecs);
                    } else {
                        postError(cb, obj.optString("error", "Video upload failed"));
                    }
                }
                VideoCompressor.safeDelete(compressed.videoFile);

            } catch (Exception e) {
                Log.e(TAG, "uploadVideo failed", e);
                postError(cb, e.getMessage());
            }
        }).start();
    }

    // ── Upload Image ─────────────────────────────────────────────────────────

    public static void uploadImage(Context ctx, Uri imageUri,
                                   String folder, UploadCallback cb) {
        postProgress(cb, 2);
        Log.d(TAG, "Compressing image...");

        ImageCompressor.compress(ctx, imageUri, new ImageCompressor.Callback() {

            @Override
            public void onSuccess(ImageCompressor.Result result) {
                postProgress(cb, 20);
                Log.d(TAG, "Image compressed: " + result.summary());

                new Thread(() -> {
                    try {
                        // Sign
                        String ytFolder = "youtube/" + folder;
                        JSONObject signJson = getSignature(ytFolder, "image");
                        if (signJson == null) { postError(cb, "Server se signature nahi mila. Server check karo."); return; }

                        postProgress(cb, 30);

                        // Upload
                        File imgFile = result.fullFile;
                        String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
                        String upUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/image/upload";

                        MultipartBody body = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("file", imgFile.getName(),
                                RequestBody.create(imgFile, MediaType.parse("image/webp")))
                            .addFormDataPart("api_key",   signJson.getString("api_key"))
                            .addFormDataPart("timestamp", signJson.getString("timestamp"))
                            .addFormDataPart("signature", signJson.getString("signature"))
                            .addFormDataPart("folder",    signJson.optString("folder", ytFolder))
                            .build();

                        Request req = new Request.Builder().url(upUrl).post(body).build();
                        try (Response resp = client.newCall(req).execute()) {
                            String json = resp.body() != null ? resp.body().string() : "";
                            Log.d(TAG, "Image upload response: " + json);
                            JSONObject obj = new JSONObject(json);
                            if (obj.has("secure_url")) {
                                postProgress(cb, 100);
                                postSuccess(cb, obj.getString("secure_url"), obj.optString("public_id", ""), 0);
                            } else {
                                postError(cb, obj.optString("error", "Image upload failed"));
                            }
                        }

                        if (result.fullFile  != null) result.fullFile.delete();
                        if (result.thumbFile != null) result.thumbFile.delete();

                    } catch (Exception e) {
                        Log.e(TAG, "uploadImage network failed", e);
                        postError(cb, e.getMessage());
                    }
                }).start();
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "Image compression failed, uploading original: " + e.getMessage());
                uploadImageDirect(ctx, imageUri, folder, cb);
            }
        });
    }

    // ── Fallback: direct upload without compression ───────────────────────────

    private static void uploadImageDirect(Context ctx, Uri imageUri,
                                          String folder, UploadCallback cb) {
        new Thread(() -> {
            try {
                File tmpFile = VideoCompressor.copyUriToFile(ctx, imageUri);
                String ytFolder = "youtube/" + folder;
                JSONObject signJson = getSignature(ytFolder, "image");
                if (signJson == null) { postError(cb, "Server se signature nahi mila."); return; }

                String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
                String upUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/image/upload";

                MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", tmpFile.getName(),
                        RequestBody.create(tmpFile, MediaType.parse("image/jpeg")))
                    .addFormDataPart("api_key",   signJson.getString("api_key"))
                    .addFormDataPart("timestamp", signJson.getString("timestamp"))
                    .addFormDataPart("signature", signJson.getString("signature"))
                    .addFormDataPart("folder",    signJson.optString("folder", ytFolder))
                    .build();

                Request req = new Request.Builder().url(upUrl).post(body).build();
                try (Response resp = client.newCall(req).execute()) {
                    String json = resp.body() != null ? resp.body().string() : "";
                    JSONObject obj = new JSONObject(json);
                    if (obj.has("secure_url")) {
                        postProgress(cb, 100);
                        postSuccess(cb, obj.getString("secure_url"), obj.optString("public_id", ""), 0);
                    } else {
                        postError(cb, obj.optString("error", "Upload failed"));
                    }
                }
                VideoCompressor.safeDelete(tmpFile);
            } catch (Exception e) {
                postError(cb, e.getMessage());
            }
        }).start();
    }

    // ── Signed upload helper ─────────────────────────────────────────────────

    /**
     * Server se Cloudinary signature lo.
     * Returns JSONObject with: signature, timestamp, api_key, cloud_name, folder
     * Returns null if server unreachable or error.
     */
    private static JSONObject getSignature(String folder, String resourceType) {
        try {
            JSONObject payload = new JSONObject()
                .put("folder", folder)
                .put("resource_type", resourceType);
            Request req = new Request.Builder()
                .url(Constants.SERVER_URL + "/cloudinary/sign")
                .post(RequestBody.create(payload.toString(),
                    MediaType.parse("application/json")))
                .build();
            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    Log.e(TAG, "Sign request failed (" + resp.code() + "): " + body);
                    return null;
                }
                return new JSONObject(body);
            }
        } catch (Exception e) {
            Log.e(TAG, "getSignature error: " + e.getMessage());
            return null;
        }
    }

    // ── UI thread helpers ─────────────────────────────────────────────────────

    private static void postProgress(UploadCallback cb, int pct) {
        UI.post(() -> cb.onProgress(pct));
    }

    private static void postSuccess(UploadCallback cb, String url, String pid, long durationSecs) {
        UI.post(() -> cb.onSuccess(url, pid, durationSecs));
    }

    private static void postError(UploadCallback cb, String msg) {
        UI.post(() -> cb.onError(msg != null ? msg : "Unknown error"));
    }
}
