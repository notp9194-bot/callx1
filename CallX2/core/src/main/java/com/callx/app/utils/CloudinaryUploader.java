package com.callx.app.utils;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.MimeTypeMap;
import okhttp3.*;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
public class CloudinaryUploader {
    private static final String TAG = "Cloudinary";
    public interface UploadCallback {
        void onSuccess(Result result);
        void onError(String message);
    }

    /** Avatar dual-upload callback — thumb pehle, full baad */
    public interface AvatarUploadCallback {
        /** thumbUrl → Firebase mein seedha save karo, UI update karo */
        void onThumbReady(String thumbUrl);
        /** photoUrl → Firebase mein save karo */
        void onFullReady(String photoUrl);
        void onError(String message);
    }
    public static class Result {
        public String secureUrl;
        public String publicId;
        public String resourceType;
        public String format;
        public Long bytes;
        public Long durationMs;
        public String thumbnailUrl;
    }
    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build();

    /** resource_type: image | video | raw | auto. Use 'raw' for PDF/docs. */
    public static void upload(Context ctx, Uri uri, String folder,
                              String resourceType, UploadCallback cb) {
        new Thread(() -> {
            try {
                byte[] bytes = readBytes(ctx, uri);
                if (bytes == null || bytes.length == 0) {
                    post(cb, null, "Empty file");
                    return;
                }
                String mime = ctx.getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (ext == null || ext.isEmpty()) ext = "bin";
                String filename = "upload." + ext;
                final String rType = (resourceType == null || resourceType.isEmpty())
                    ? "auto" : resourceType;

                // Step 1 — sign
                JSONObject payload = new JSONObject()
                    .put("folder", folder == null ? "callx" : folder)
                    .put("resource_type", rType);
                Request signReq = new Request.Builder()
                    .url(Constants.SERVER_URL + "/cloudinary/sign")
                    .post(RequestBody.create(payload.toString(),
                        MediaType.parse("application/json")))
                    .build();
                Response signRes = client.newCall(signReq).execute();
                String signBody = signRes.body() != null ? signRes.body().string() : "";
                signRes.close();
                if (!signRes.isSuccessful()) {
                    Log.e(TAG, "Sign failed (" + signRes.code() + "): " + signBody);
                    post(cb, null, "Server error " + signRes.code() +
                        ". Image bhejne mein dikkat. Server pe Cloudinary configure nahi hai shayad.");
                    return;
                }
                JSONObject signJson = new JSONObject(signBody);
                String signature = signJson.getString("signature");
                String timestamp = signJson.getString("timestamp");
                String apiKey    = signJson.getString("api_key");
                String cloudName = signJson.optString("cloud_name",
                    Constants.CLOUDINARY_CLOUD_NAME);
                String f         = signJson.optString("folder", "callx");
                String rt        = signJson.optString("resource_type", rType);

                // Step 2 — direct upload
                MultipartBody.Builder mp = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename,
                        RequestBody.create(bytes, MediaType.parse(mime)))
                    .addFormDataPart("api_key", apiKey)
                    .addFormDataPart("timestamp", timestamp)
                    .addFormDataPart("signature", signature)
                    .addFormDataPart("folder", f);
                String upUrl = "https://api.cloudinary.com/v1_1/" +
                    cloudName + "/" + rt + "/upload";
                Request upReq = new Request.Builder()
                    .url(upUrl).post(mp.build()).build();
                Response upRes = client.newCall(upReq).execute();
                String body = upRes.body() != null ? upRes.body().string() : "";
                upRes.close();
                if (!upRes.isSuccessful()) {
                    Log.e(TAG, "Upload failed: " + body);
                    post(cb, null, "Upload failed (" + upRes.code() + ")");
                    return;
                }
                JSONObject upJson = new JSONObject(body);
                Result r = new Result();
                r.secureUrl    = upJson.optString("secure_url",
                    upJson.optString("url"));
                r.publicId     = upJson.optString("public_id");
                r.resourceType = upJson.optString("resource_type", rt);
                r.format       = upJson.optString("format");
                if (upJson.has("bytes")) r.bytes = upJson.getLong("bytes");
                if (upJson.has("duration"))
                    r.durationMs = (long)(upJson.getDouble("duration") * 1000);
                // Cloudinary returns eager[0].secure_url as the video thumbnail when
                // an eager transformation is configured on the server side.
                if (upJson.has("eager")) {
                    org.json.JSONArray eager = upJson.optJSONArray("eager");
                    if (eager != null && eager.length() > 0) {
                        r.thumbnailUrl = eager.getJSONObject(0).optString("secure_url", null);
                    }
                }
                // Fallback: use the poster frame URL Cloudinary sometimes provides
                if (r.thumbnailUrl == null || r.thumbnailUrl.isEmpty()) {
                    String rawUrl = r.secureUrl;
                    if (rawUrl != null && "video".equals(r.resourceType)) {
                        r.thumbnailUrl = rawUrl.replaceFirst("\\.[^.]+$", ".jpg");
                    }
                }
                if (r.secureUrl == null || r.secureUrl.isEmpty()) {
                    post(cb, null, "No URL in response");
                    return;
                }
                post(cb, r, null);
            } catch (Exception e) {
                Log.e(TAG, "Upload error", e);
                post(cb, null, e.getMessage() == null ? "Upload error" : e.getMessage());
            }
        }).start();
    }
    private static byte[] readBytes(Context ctx, Uri uri) throws IOException {
        // Images: compress before upload (resize + JPEG 80%)
        String mime = ctx.getContentResolver().getType(uri);
        if (mime != null && mime.startsWith("image/")) {
            byte[] compressed = MediaCompressor.compressImage(ctx, uri);
            if (compressed != null && compressed.length > 0) {
                Log.d(TAG, "Image compressed for upload: " + compressed.length / 1024 + " KB");
                return compressed;
            }
        }
        // Other types: read as-is
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
    private static void post(UploadCallback cb, Result r, String err) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (r != null) cb.onSuccess(r); else cb.onError(err);
        });
    }
    /**
     * Avatar dual-upload:
     *   Step 1 → thumbnail (100×100 WebP 60%) upload → onThumbReady callback
     *   Step 2 → full photo (800×800 JPEG 85%) upload → onFullReady callback
     *
     * Usage:
     *   CloudinaryUploader.uploadAvatar(ctx, uri, new AvatarUploadCallback() { ... });
     */
    public static void uploadAvatar(Context ctx, Uri uri, AvatarUploadCallback cb) {
        new Thread(() -> {
            try {
                // ── Step 1: Thumbnail ─────────────────────────────────────
                byte[] thumbBytes = MediaCompressor.compressImageWithQuality(
                    ctx, uri, 100, 60, true);   // 100px, WebP 60%
                if (thumbBytes == null || thumbBytes.length == 0) {
                    post(cb, null, null, "Thumbnail compress failed");
                    return;
                }
                String thumbUrl = uploadBytes(ctx, thumbBytes, "image/webp",
                    "thumb.webp", "callx/avatars/thumbs", "image");
                if (thumbUrl == null) {
                    post(cb, null, null, "Thumbnail upload failed");
                    return;
                }
                post(cb, thumbUrl, null, null);   // onThumbReady fired

                // ── Step 2: Full photo ────────────────────────────────────
                byte[] fullBytes = MediaCompressor.compressImageWithQuality(
                    ctx, uri, 800, 85, false);   // 800px, JPEG 85%
                if (fullBytes == null || fullBytes.length == 0) {
                    post(cb, null, null, "Full photo compress failed");
                    return;
                }
                String photoUrl = uploadBytes(ctx, fullBytes, "image/jpeg",
                    "photo.jpg", "callx/avatars", "image");
                if (photoUrl == null) {
                    post(cb, null, null, "Full photo upload failed");
                    return;
                }
                postFull(cb, photoUrl);           // onFullReady fired

            } catch (Exception e) {
                Log.e(TAG, "uploadAvatar error", e);
                post(cb, null, null, e.getMessage() != null ? e.getMessage() : "Upload error");
            }
        }).start();
    }

    /** Internal: byte[] → Cloudinary → secureUrl. Returns null on failure. */
    private static String uploadBytes(Context ctx, byte[] bytes, String mime,
                                      String filename, String folder,
                                      String resourceType) {
        try {
            // Sign
            JSONObject payload = new JSONObject()
                .put("folder", folder)
                .put("resource_type", resourceType);
            Request signReq = new Request.Builder()
                .url(Constants.SERVER_URL + "/cloudinary/sign")
                .post(RequestBody.create(payload.toString(),
                    MediaType.parse("application/json")))
                .build();
            Response signRes = client.newCall(signReq).execute();
            String signBody = signRes.body() != null ? signRes.body().string() : "";
            signRes.close();
            if (!signRes.isSuccessful()) return null;

            JSONObject signJson = new JSONObject(signBody);
            String signature = signJson.getString("signature");
            String timestamp = signJson.getString("timestamp");
            String apiKey    = signJson.getString("api_key");
            String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
            String f         = signJson.optString("folder", folder);
            String rt        = signJson.optString("resource_type", resourceType);

            // Upload
            MultipartBody.Builder mp = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename,
                    RequestBody.create(bytes, MediaType.parse(mime)))
                .addFormDataPart("api_key", apiKey)
                .addFormDataPart("timestamp", timestamp)
                .addFormDataPart("signature", signature)
                .addFormDataPart("folder", f);
            String upUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/" + rt + "/upload";
            Request upReq = new Request.Builder().url(upUrl).post(mp.build()).build();
            Response upRes = client.newCall(upReq).execute();
            String upBody = upRes.body() != null ? upRes.body().string() : "";
            upRes.close();
            if (!upRes.isSuccessful()) return null;

            JSONObject upJson = new JSONObject(upBody);
            String url = upJson.optString("secure_url", upJson.optString("url"));
            return (url == null || url.isEmpty()) ? null : url;

        } catch (Exception e) {
            Log.e(TAG, "uploadBytes error: " + e.getMessage());
            return null;
        }
    }

    private static void post(AvatarUploadCallback cb, String thumbUrl,
                             String photoUrl, String err) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (err != null)       cb.onError(err);
            else if (thumbUrl != null) cb.onThumbReady(thumbUrl);
        });
    }
    private static void postFull(AvatarUploadCallback cb, String photoUrl) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onFullReady(photoUrl));
    }

    private CloudinaryUploader() {}
}
