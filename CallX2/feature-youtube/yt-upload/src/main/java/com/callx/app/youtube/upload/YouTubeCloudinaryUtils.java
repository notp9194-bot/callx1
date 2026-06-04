package com.callx.app.youtube.upload;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

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
 * SIGNED UPLOAD:
 *   Step 1 → GET signature from server  (Constants.SERVER_URL + /cloudinary/sign)
 *   Step 2 → POST file directly to Cloudinary using signature + api_key + timestamp
 *
 * DEBUG TOASTS (visible on screen):
 *   Every major step shows a Toast so you can see exactly what is happening.
 *
 * Progress:
 *   Video:  0–40% compression  |  40–100% upload
 *   Image:  0–20% compression  |  20–100% upload
 */
public class YouTubeCloudinaryUtils {

    private static final String TAG = "YT_UPLOAD_DEBUG";

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
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.d(TAG, "▶ uploadVideo() SHURU HUA");
        Log.d(TAG, "  URI    : " + videoUri);
        Log.d(TAG, "  Folder : " + folder);
        showToast(ctx, "📹 Upload shuru... video compress ho raha hai");

        new Thread(() -> {
            try {
                // ── STEP 1: Compression ───────────────────────────────────
                postProgress(cb, 2);
                Log.d(TAG, "[STEP 1] Video compression shuru...");

                VideoCompressor.Result compressed = VideoCompressor.compressSync(
                    ctx, videoUri,
                    VideoQualityPreferences.Quality.STANDARD,
                    pct -> {
                        postProgress(cb, (int)(pct * 0.40f));
                        if (pct % 25 == 0) {
                            Log.d(TAG, "  Compression progress: " + pct + "%");
                        }
                    }
                );

                if (compressed == null || compressed.videoFile == null || !compressed.videoFile.exists()) {
                    String msg = "❌ Compression FAIL — compressed file nahi bana";
                    Log.e(TAG, msg);
                    showToast(ctx, msg);
                    postError(cb, "Video compression failed — file not found after compress");
                    return;
                }

                long originalBytes = getUriSize(ctx, videoUri);
                long compressedBytes = compressed.videoFile.length();
                String compressionInfo = String.format(
                    "✅ Compression DONE\n  Original : %s\n  Compressed: %s\n  Summary  : %s",
                    formatBytes(originalBytes), formatBytes(compressedBytes),
                    compressed.compressionSummary()
                );
                Log.d(TAG, compressionInfo);
                showToast(ctx, "✅ Compress complete: " + formatBytes(compressedBytes));
                postProgress(cb, 40);

                // ── STEP 2: Server se Signature lo ───────────────────────
                String ytFolder = "youtube/" + folder;
                Log.d(TAG, "[STEP 2] Server se signature maang raha hai...");
                Log.d(TAG, "  Server URL : " + Constants.SERVER_URL + "/cloudinary/sign");
                Log.d(TAG, "  Folder     : " + ytFolder);
                showToast(ctx, "🔐 Server se signature le raha hai...");

                JSONObject signJson = getSignature(ctx, ytFolder, "video");
                if (signJson == null) {
                    String msg = "❌ Signature FAIL\n  Server URL: " + Constants.SERVER_URL + "\n  /cloudinary/sign se response nahi aaya\n  Server chal raha hai? Internet hai?";
                    Log.e(TAG, msg);
                    showToast(ctx, "❌ Server se signature nahi mila! SERVER_URL check karo:\n" + Constants.SERVER_URL);
                    postError(cb, "Server se signature nahi mila.\nServer URL: " + Constants.SERVER_URL + "\n/cloudinary/sign endpoint check karo.");
                    return;
                }

                String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
                Log.d(TAG, "  Signature mila ✅");
                Log.d(TAG, "  cloud_name : " + cloudName);
                Log.d(TAG, "  api_key    : " + signJson.optString("api_key", "N/A"));
                Log.d(TAG, "  timestamp  : " + signJson.optString("timestamp", "N/A"));
                showToast(ctx, "✅ Signature mila! Cloud: " + cloudName);
                postProgress(cb, 45);

                // ── STEP 3: Cloudinary pe Upload ─────────────────────────
                File videoFile = compressed.videoFile;
                String upUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/video/upload";
                Log.d(TAG, "[STEP 3] Cloudinary pe upload shuru...");
                Log.d(TAG, "  Upload URL : " + upUrl);
                Log.d(TAG, "  File size  : " + formatBytes(videoFile.length()));
                showToast(ctx, "☁️ Cloudinary pe upload ho raha hai...\n" + formatBytes(videoFile.length()));

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
                    int code = resp.code();
                    String json = resp.body() != null ? resp.body().string() : "";
                    Log.d(TAG, "[STEP 3] Cloudinary response HTTP " + code);
                    Log.d(TAG, "  Response body: " + json);

                    if (!resp.isSuccessful()) {
                        String msg = "❌ Cloudinary HTTP " + code + "\n" + json;
                        Log.e(TAG, msg);
                        showToast(ctx, "❌ Cloudinary error " + code + "\n" + truncate(json, 120));
                        postError(cb, "Cloudinary upload failed (HTTP " + code + "): " + truncate(json, 200));
                        VideoCompressor.safeDelete(videoFile);
                        return;
                    }

                    JSONObject obj = new JSONObject(json);
                    if (obj.has("secure_url")) {
                        String secureUrl = obj.getString("secure_url");
                        long durSecs     = Math.round(obj.optDouble("duration", 0));
                        String publicId  = obj.optString("public_id", "");
                        Log.d(TAG, "✅ VIDEO UPLOAD SUCCESS!");
                        Log.d(TAG, "  secure_url : " + secureUrl);
                        Log.d(TAG, "  public_id  : " + publicId);
                        Log.d(TAG, "  duration   : " + durSecs + "s");
                        showToast(ctx, "✅ VIDEO UPLOAD SUCCESS!\n" + secureUrl);
                        postProgress(cb, 100);
                        postSuccess(cb, secureUrl, publicId, durSecs);
                    } else {
                        String errMsg = obj.optString("error", "Unknown Cloudinary error");
                        Log.e(TAG, "❌ Cloudinary error field: " + errMsg);
                        Log.e(TAG, "   Full response: " + json);
                        showToast(ctx, "❌ Cloudinary reject: " + errMsg);
                        postError(cb, "Cloudinary rejected: " + errMsg + "\nFull: " + truncate(json, 200));
                    }
                }
                VideoCompressor.safeDelete(compressed.videoFile);

            } catch (Exception e) {
                Log.e(TAG, "❌ uploadVideo EXCEPTION", e);
                showToast(ctx, "❌ Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                postError(cb, "Exception: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            }
        }).start();
    }

    // ── Upload Image ─────────────────────────────────────────────────────────

    public static void uploadImage(Context ctx, Uri imageUri,
                                   String folder, UploadCallback cb) {
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.d(TAG, "▶ uploadImage() SHURU HUA");
        Log.d(TAG, "  URI    : " + imageUri);
        Log.d(TAG, "  Folder : " + folder);
        showToast(ctx, "🖼️ Thumbnail compress ho raha hai...");
        postProgress(cb, 2);

        ImageCompressor.compress(ctx, imageUri, new ImageCompressor.Callback() {

            @Override
            public void onSuccess(ImageCompressor.Result result) {
                postProgress(cb, 20);
                Log.d(TAG, "[IMG] Compression DONE: " + result.summary());
                showToast(ctx, "✅ Thumbnail compress hua: " + result.summary());

                new Thread(() -> {
                    try {
                        String ytFolder = "youtube/" + folder;
                        Log.d(TAG, "[IMG STEP 2] Server se signature maang raha hai...");
                        showToast(ctx, "🔐 Thumbnail ke liye signature le raha hai...");

                        JSONObject signJson = getSignature(ctx, ytFolder, "image");
                        if (signJson == null) {
                            String msg = "❌ Thumbnail: Server se signature nahi mila\nURL: " + Constants.SERVER_URL;
                            Log.e(TAG, msg);
                            showToast(ctx, msg);
                            postError(cb, msg);
                            return;
                        }

                        String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
                        Log.d(TAG, "  [IMG] Signature mila ✅ cloud: " + cloudName);
                        showToast(ctx, "✅ Thumbnail signature mila!");
                        postProgress(cb, 30);

                        File imgFile  = result.fullFile;
                        String upUrl  = "https://api.cloudinary.com/v1_1/" + cloudName + "/image/upload";
                        Log.d(TAG, "[IMG STEP 3] Cloudinary pe upload: " + upUrl);
                        showToast(ctx, "☁️ Thumbnail upload ho raha hai...");

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
                            int code = resp.code();
                            String json = resp.body() != null ? resp.body().string() : "";
                            Log.d(TAG, "[IMG] Cloudinary response HTTP " + code + ": " + json);

                            JSONObject obj = new JSONObject(json);
                            if (obj.has("secure_url")) {
                                String secureUrl = obj.getString("secure_url");
                                Log.d(TAG, "✅ THUMBNAIL UPLOAD SUCCESS: " + secureUrl);
                                showToast(ctx, "✅ Thumbnail upload success!");
                                postProgress(cb, 100);
                                postSuccess(cb, secureUrl, obj.optString("public_id", ""), 0);
                            } else {
                                String errMsg = obj.optString("error", "Image upload failed");
                                Log.e(TAG, "❌ Thumbnail Cloudinary error: " + errMsg + " | Full: " + json);
                                showToast(ctx, "❌ Thumbnail error: " + errMsg);
                                postError(cb, "Thumbnail Cloudinary rejected: " + errMsg);
                            }
                        }

                        if (result.fullFile  != null) result.fullFile.delete();
                        if (result.thumbFile != null) result.thumbFile.delete();

                    } catch (Exception e) {
                        Log.e(TAG, "❌ uploadImage network EXCEPTION", e);
                        showToast(ctx, "❌ Thumbnail exception: " + e.getMessage());
                        postError(cb, "Thumbnail exception: " + e.getMessage());
                    }
                }).start();
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "⚠️ Image compression failed, direct upload try kar raha hai: " + e.getMessage());
                showToast(ctx, "⚠️ Compress fail, direct upload try kar raha hai...");
                uploadImageDirect(ctx, imageUri, folder, cb);
            }
        });
    }

    // ── Fallback: direct upload without compression ───────────────────────────

    private static void uploadImageDirect(Context ctx, Uri imageUri,
                                          String folder, UploadCallback cb) {
        Log.d(TAG, "[IMG DIRECT] Bina compression ke upload kar raha hai...");
        new Thread(() -> {
            try {
                File tmpFile = VideoCompressor.copyUriToFile(ctx, imageUri);
                String ytFolder = "youtube/" + folder;

                JSONObject signJson = getSignature(ctx, ytFolder, "image");
                if (signJson == null) {
                    showToast(ctx, "❌ Direct upload: Server se signature nahi mila.");
                    postError(cb, "Direct image upload: Server se signature nahi mila.");
                    return;
                }

                String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
                String upUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/image/upload";
                Log.d(TAG, "[IMG DIRECT] Upload URL: " + upUrl);

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
                    Log.d(TAG, "[IMG DIRECT] Response HTTP " + resp.code() + ": " + json);
                    JSONObject obj = new JSONObject(json);
                    if (obj.has("secure_url")) {
                        showToast(ctx, "✅ Thumbnail direct upload success!");
                        postProgress(cb, 100);
                        postSuccess(cb, obj.getString("secure_url"), obj.optString("public_id", ""), 0);
                    } else {
                        String errMsg = obj.optString("error", "Upload failed");
                        showToast(ctx, "❌ Direct upload fail: " + errMsg);
                        postError(cb, "Direct image upload failed: " + errMsg);
                    }
                }
                VideoCompressor.safeDelete(tmpFile);
            } catch (Exception e) {
                Log.e(TAG, "❌ uploadImageDirect EXCEPTION", e);
                showToast(ctx, "❌ Direct upload exception: " + e.getMessage());
                postError(cb, "Direct image upload exception: " + e.getMessage());
            }
        }).start();
    }

    // ── Signed upload helper ─────────────────────────────────────────────────

    /**
     * Server se Cloudinary signature lo.
     * Returns JSONObject with: signature, timestamp, api_key, cloud_name, folder
     * Returns null if server unreachable or error.
     */
    private static JSONObject getSignature(Context ctx, String folder, String resourceType) {
        String signUrl = Constants.SERVER_URL + "/cloudinary/sign";
        Log.d(TAG, "[SIGN] Request → " + signUrl);
        Log.d(TAG, "[SIGN] Payload → folder=" + folder + ", resource_type=" + resourceType);
        try {
            JSONObject payload = new JSONObject()
                .put("folder", folder)
                .put("resource_type", resourceType);
            Request req = new Request.Builder()
                .url(signUrl)
                .post(RequestBody.create(payload.toString(),
                    MediaType.parse("application/json")))
                .build();
            try (Response resp = client.newCall(req).execute()) {
                int code = resp.code();
                String body = resp.body() != null ? resp.body().string() : "";
                Log.d(TAG, "[SIGN] Response HTTP " + code + ": " + body);
                if (!resp.isSuccessful()) {
                    Log.e(TAG, "❌ Sign request FAIL (HTTP " + code + "): " + body);
                    showToast(ctx, "❌ Sign server error " + code + ": " + truncate(body, 100));
                    return null;
                }
                JSONObject result = new JSONObject(body);
                Log.d(TAG, "✅ Signature received: api_key=" + result.optString("api_key")
                    + ", cloud=" + result.optString("cloud_name"));
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ getSignature EXCEPTION: " + e.getMessage(), e);
            showToast(ctx, "❌ Server connect FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    private static void showToast(Context ctx, String msg) {
        if (ctx == null) return;
        UI.post(() -> Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static long getUriSize(Context ctx, Uri uri) {
        try {
            android.database.Cursor c = ctx.getContentResolver()
                .query(uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                long size = c.getLong(0);
                c.close();
                return size;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
