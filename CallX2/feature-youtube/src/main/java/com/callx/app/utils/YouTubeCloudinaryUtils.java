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

/**
 * YouTubeCloudinaryUtils — Cloudinary upload helper for YouTube module.
 *
 * UPDATED: Now uses core's ImageCompressor + VideoCompressor before uploading.
 *   • uploadVideo()  → VideoCompressor (STANDARD quality) → Cloudinary
 *   • uploadImage()  → ImageCompressor (WebP, max 1280px)  → Cloudinary (fullFile)
 *
 * Progress reporting:
 *   Video:  0–40% compression  |  40–100% Cloudinary upload
 *   Image:  0–20% compression  |  20–100% Cloudinary upload
 */
public class YouTubeCloudinaryUtils {

    private static final String TAG = "YTCloudinaryUtils";

    private static final String CLOUD_NAME    = Constants.CLOUDINARY_CLOUD_NAME;
    private static final String UPLOAD_PRESET = Constants.CLOUDINARY_PRESET;
    private static final String BASE_URL =
        "https://api.cloudinary.com/v1_1/" + CLOUD_NAME;

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build();

    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ── Callback ──────────────────────────────────────────────────────────────

    public interface UploadCallback {
        void onProgress(int percent);
        void onSuccess(String secureUrl, String publicId);
        void onError(String errorMsg);
    }

    // ── Upload Video (compress first, then upload) ────────────────────────────

    public static void uploadVideo(Context ctx, Uri videoUri,
                                   String folder, UploadCallback cb) {
        new Thread(() -> {
            try {
                // Step 1: Compress video using core VideoCompressor (0–40%)
                postProgress(cb, 2);
                Log.d(TAG, "Starting video compression...");

                VideoCompressor.Result result = VideoCompressor.compressSync(
                    ctx,
                    videoUri,
                    VideoQualityPreferences.Quality.STANDARD,
                    pct -> postProgress(cb, (int)(pct * 0.40f))  // map 0–100 → 0–40%
                );

                Log.d(TAG, "Video compressed: " + result.compressionSummary());
                postProgress(cb, 40);

                // Step 2: Upload compressed video to Cloudinary (40–100%)
                File videoFile = result.videoFile;
                RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", videoFile.getName(),
                        RequestBody.create(videoFile, MediaType.parse("video/mp4")))
                    .addFormDataPart("upload_preset", UPLOAD_PRESET)
                    .addFormDataPart("folder", "youtube/" + folder)
                    .addFormDataPart("resource_type", "video")
                    .build();

                postProgress(cb, 45);

                Request req = new Request.Builder()
                    .url(BASE_URL + "/video/upload")
                    .post(body)
                    .build();

                try (Response resp = client.newCall(req).execute()) {
                    String json = resp.body() != null ? resp.body().string() : "";
                    JSONObject obj = new JSONObject(json);
                    if (obj.has("secure_url")) {
                        postProgress(cb, 100);
                        String url = obj.getString("secure_url");
                        String pid = obj.optString("public_id", "");
                        postSuccess(cb, url, pid);
                    } else {
                        postError(cb, obj.optString("error", "Video upload failed"));
                    }
                }

                // Cleanup compressed temp file
                VideoCompressor.safeDelete(result.videoFile);

            } catch (Exception e) {
                Log.e(TAG, "uploadVideo failed", e);
                postError(cb, e.getMessage());
            }
        }).start();
    }

    // ── Upload Image (compress first, then upload) ────────────────────────────

    public static void uploadImage(Context ctx, Uri imageUri,
                                   String folder, UploadCallback cb) {
        // ImageCompressor runs on bg thread internally; we wrap in thread for consistency
        postProgress(cb, 2);
        Log.d(TAG, "Starting image compression...");

        ImageCompressor.compress(ctx, imageUri, new ImageCompressor.Callback() {

            @Override
            public void onSuccess(ImageCompressor.Result result) {
                postProgress(cb, 20);
                Log.d(TAG, "Image compressed: " + result.summary());

                // Upload compressed full image to Cloudinary on bg thread
                new Thread(() -> {
                    try {
                        File imgFile = result.fullFile;
                        RequestBody body = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("file", imgFile.getName(),
                                RequestBody.create(imgFile, MediaType.parse("image/webp")))
                            .addFormDataPart("upload_preset", UPLOAD_PRESET)
                            .addFormDataPart("folder", "youtube/" + folder)
                            .build();

                        postProgress(cb, 30);

                        Request req = new Request.Builder()
                            .url(BASE_URL + "/image/upload")
                            .post(body)
                            .build();

                        try (Response resp = client.newCall(req).execute()) {
                            String json = resp.body() != null ? resp.body().string() : "";
                            JSONObject obj = new JSONObject(json);
                            if (obj.has("secure_url")) {
                                postProgress(cb, 100);
                                postSuccess(cb, obj.getString("secure_url"),
                                    obj.optString("public_id", ""));
                            } else {
                                postError(cb, obj.optString("error", "Image upload failed"));
                            }
                        }

                        // Cleanup temp compressed files
                        if (result.fullFile != null)  result.fullFile.delete();
                        if (result.thumbFile != null) result.thumbFile.delete();

                    } catch (Exception e) {
                        Log.e(TAG, "uploadImage network failed", e);
                        postError(cb, e.getMessage());
                    }
                }).start();
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Image compression failed, uploading original", e);
                // Fallback: upload original without compression
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
                RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", tmpFile.getName(),
                        RequestBody.create(tmpFile, MediaType.parse("image/jpeg")))
                    .addFormDataPart("upload_preset", UPLOAD_PRESET)
                    .addFormDataPart("folder", "youtube/" + folder)
                    .build();

                Request req = new Request.Builder()
                    .url(BASE_URL + "/image/upload")
                    .post(body)
                    .build();

                try (Response resp = client.newCall(req).execute()) {
                    String json = resp.body() != null ? resp.body().string() : "";
                    JSONObject obj = new JSONObject(json);
                    if (obj.has("secure_url")) {
                        postProgress(cb, 100);
                        postSuccess(cb, obj.getString("secure_url"),
                            obj.optString("public_id", ""));
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void postProgress(UploadCallback cb, int pct) {
        UI.post(() -> cb.onProgress(pct));
    }

    private static void postSuccess(UploadCallback cb, String url, String pid) {
        UI.post(() -> cb.onSuccess(url, pid));
    }

    private static void postError(UploadCallback cb, String msg) {
        UI.post(() -> cb.onError(msg != null ? msg : "Unknown error"));
    }
}
