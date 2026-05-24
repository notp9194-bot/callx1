package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
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
 * Handles video, thumbnail, and banner uploads using the same Cloudinary
 * config already wired in the app.
 */
public class YouTubeCloudinaryUtils {

    private static final String CLOUD_NAME   = Constants.CLOUDINARY_CLOUD_NAME;
    private static final String UPLOAD_PRESET = Constants.CLOUDINARY_PRESET;
    private static final String BASE_URL =
        "https://api.cloudinary.com/v1_1/" + CLOUD_NAME;

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build();

    public interface UploadCallback {
        void onProgress(int percent);
        void onSuccess(String secureUrl, String publicId);
        void onError(String errorMsg);
    }

    // ── Upload a video file ───────────────────────────────────────────────────
    public static void uploadVideo(Context ctx, Uri videoUri,
                                   String folder, UploadCallback cb) {
        new Thread(() -> {
            try {
                File tmpFile = uriToTempFile(ctx, videoUri, "yt_video_", ".mp4");
                if (tmpFile == null) { postError(cb, "Failed to read video"); return; }

                RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", tmpFile.getName(),
                        RequestBody.create(tmpFile, MediaType.parse("video/mp4")))
                    .addFormDataPart("upload_preset", UPLOAD_PRESET)
                    .addFormDataPart("folder", "youtube/" + folder)
                    .addFormDataPart("resource_type", "video")
                    .build();

                Request req = new Request.Builder()
                    .url(BASE_URL + "/video/upload")
                    .post(body).build();

                try (Response resp = client.newCall(req).execute()) {
                    String json = resp.body() != null ? resp.body().string() : "";
                    JSONObject obj = new JSONObject(json);
                    if (obj.has("secure_url")) {
                        String url = obj.getString("secure_url");
                        String pid = obj.optString("public_id", "");
                        postSuccess(cb, url, pid);
                    } else {
                        postError(cb, obj.optString("error", "Upload failed"));
                    }
                }
                tmpFile.delete();
            } catch (Exception e) {
                postError(cb, e.getMessage());
            }
        }).start();
    }

    // ── Upload an image (thumbnail / banner / avatar) ─────────────────────────
    public static void uploadImage(Context ctx, Uri imageUri,
                                   String folder, UploadCallback cb) {
        new Thread(() -> {
            try {
                File tmpFile = uriToTempFile(ctx, imageUri, "yt_img_", ".jpg");
                if (tmpFile == null) { postError(cb, "Failed to read image"); return; }

                RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", tmpFile.getName(),
                        RequestBody.create(tmpFile, MediaType.parse("image/jpeg")))
                    .addFormDataPart("upload_preset", UPLOAD_PRESET)
                    .addFormDataPart("folder", "youtube/" + folder)
                    .build();

                Request req = new Request.Builder()
                    .url(BASE_URL + "/image/upload")
                    .post(body).build();

                try (Response resp = client.newCall(req).execute()) {
                    String json = resp.body() != null ? resp.body().string() : "";
                    JSONObject obj = new JSONObject(json);
                    if (obj.has("secure_url")) {
                        postSuccess(cb, obj.getString("secure_url"),
                            obj.optString("public_id", ""));
                    } else {
                        postError(cb, obj.optString("error", "Upload failed"));
                    }
                }
                tmpFile.delete();
            } catch (Exception e) {
                postError(cb, e.getMessage());
            }
        }).start();
    }

    // ── Helper: Uri → temp File ───────────────────────────────────────────────
    private static File uriToTempFile(Context ctx, Uri uri,
                                      String prefix, String suffix) {
        try {
            File tmp = File.createTempFile(prefix, suffix, ctx.getCacheDir());
            try (InputStream in  = ctx.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            }
            return tmp;
        } catch (Exception e) { return null; }
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static void postSuccess(UploadCallback cb, String url, String pid) {
        UI.post(() -> cb.onSuccess(url, pid));
    }
    private static void postError(UploadCallback cb, String msg) {
        UI.post(() -> cb.onError(msg != null ? msg : "Unknown error"));
    }
}
