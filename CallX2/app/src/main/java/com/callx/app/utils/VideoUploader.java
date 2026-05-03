package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import okhttp3.*;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * VideoUploader — Dual upload via Cloudinary: thumbnail (image) + video
 *
 * FLOW:
 *   VideoCompressor.Result
 *       ↓
 *   Upload thumb  → Cloudinary: resource_type=image  (0–20% progress)
 *   Upload video  → Cloudinary: resource_type=video  (20–100% progress)
 *       ↓
 *   Callback with both secure URLs
 *
 * Features:
 *  ✅ Cloudinary signed upload (server-side signing via /cloudinary/sign)
 *  ✅ Dual upload (thumb + video) in parallel threads
 *  ✅ Overall progress 0–100%
 *  ✅ Auto retry × 3 (exponential backoff)
 *  ✅ Temp file cleanup after success
 *
 * NOTE: Firebase Storage dependency removed — no FirebaseStorage import needed.
 */
public class VideoUploader {

    private static final String TAG       = "VideoUploader";
    private static final int    MAX_RETRY = 3;
    private static final Handler MAIN     = new Handler(Looper.getMainLooper());

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(30,  TimeUnit.SECONDS)
        .readTimeout(120,    TimeUnit.SECONDS)
        .writeTimeout(300,   TimeUnit.SECONDS)   // large video uploads
        .build();

    // ── Callbacks ─────────────────────────────────────────────────────────

    public interface UploadCallback {
        void onProgress(int percent);                            // 0–100
        void onSuccess(String thumbUrl, String videoUrl,
                       int durationMs, int width, int height);
        void onError(Exception e);
    }

    // ── Main entry ────────────────────────────────────────────────────────

    public static void upload(Context ctx,
                              VideoCompressor.Result compressed,
                              UploadCallback callback) {
        uploadBoth(ctx, compressed, callback, 1);
    }

    private static void uploadBoth(Context ctx,
                                   VideoCompressor.Result compressed,
                                   UploadCallback callback,
                                   int attempt) {

        if (!NetworkUtils.isOnline(ctx)) {
            MAIN.post(() -> callback.onError(new Exception("No network")));
            return;
        }

        final String[] thumbUrl = {null};
        final String[] videoUrl = {null};
        final boolean[] failed  = {false};
        final int[]     done    = {0};

        // Thumb upload: maps to 0–20% overall progress
        new Thread(() -> {
            try {
                String url = uploadToCloudinary(
                    compressed.thumbFile, "image",
                    "callx/videos/thumb",
                    pct -> {
                        int overall = (int)(pct * 0.20);
                        MAIN.post(() -> callback.onProgress(Math.min(overall, 20)));
                    }
                );
                thumbUrl[0] = url;
            } catch (Exception e) {
                Log.e(TAG, "Thumb upload failed: " + e.getMessage());
                failed[0] = true;
            }
            synchronized (done) {
                done[0]++;
                if (done[0] == 2) handleBothDone(ctx, compressed, thumbUrl[0],
                    videoUrl[0], failed[0], callback, attempt);
            }
        }).start();

        // Video upload: maps to 20–100% overall progress
        new Thread(() -> {
            try {
                String url = uploadToCloudinary(
                    compressed.videoFile, "video",
                    "callx/videos/file",
                    pct -> {
                        int overall = 20 + (int)(pct * 0.80);
                        MAIN.post(() -> callback.onProgress(Math.min(overall, 100)));
                    }
                );
                videoUrl[0] = url;
            } catch (Exception e) {
                Log.e(TAG, "Video upload failed: " + e.getMessage());
                failed[0] = true;
            }
            synchronized (done) {
                done[0]++;
                if (done[0] == 2) handleBothDone(ctx, compressed, thumbUrl[0],
                    videoUrl[0], failed[0], callback, attempt);
            }
        }).start();
    }

    // ── Core: sign + upload a single file to Cloudinary ──────────────────

    private static String uploadToCloudinary(File file,
                                             String resourceType,
                                             String folder,
                                             ProgressListener progress)
        throws Exception {

        // Step 1 — Get signed params from server
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

        if (!signRes.isSuccessful()) {
            throw new IOException("Sign failed (" + signRes.code() + "): " + signBody);
        }

        JSONObject signJson = new JSONObject(signBody);
        String signature = signJson.getString("signature");
        String timestamp = signJson.getString("timestamp");
        String apiKey    = signJson.getString("api_key");
        String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
        String f         = signJson.optString("folder", folder);
        String rt        = signJson.optString("resource_type", resourceType);

        // Step 2 — Upload directly to Cloudinary with progress tracking
        String mime = "image".equals(rt) ? "image/webp" : "video/mp4";
        String ext  = "image".equals(rt) ? "webp" : "mp4";

        RequestBody fileBody = new CountingRequestBody(
            RequestBody.create(file, MediaType.parse(mime)),
            progress
        );

        MultipartBody multipart = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "upload." + ext, fileBody)
            .addFormDataPart("api_key",   apiKey)
            .addFormDataPart("timestamp", timestamp)
            .addFormDataPart("signature", signature)
            .addFormDataPart("folder",    f)
            .build();

        String uploadUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/" + rt + "/upload";
        Request upReq = new Request.Builder()
            .url(uploadUrl).post(multipart).build();

        Response upRes = client.newCall(upReq).execute();
        String upBody  = upRes.body() != null ? upRes.body().string() : "";
        upRes.close();

        if (!upRes.isSuccessful()) {
            throw new IOException("Cloudinary upload failed (" + upRes.code() + "): " + upBody);
        }

        JSONObject upJson = new JSONObject(upBody);
        String secureUrl = upJson.optString("secure_url", upJson.optString("url"));
        if (secureUrl == null || secureUrl.isEmpty()) {
            throw new IOException("No URL in Cloudinary response");
        }
        Log.d(TAG, "Uploaded [" + rt + "]: " + secureUrl);
        return secureUrl;
    }

    // ── Both-done handler: retry or deliver ──────────────────────────────

    private static void handleBothDone(Context ctx,
                                       VideoCompressor.Result compressed,
                                       String thumbUrl,
                                       String videoUrl,
                                       boolean failed,
                                       UploadCallback callback,
                                       int attempt) {
        if (failed || thumbUrl == null || videoUrl == null) {
            if (attempt < MAX_RETRY) {
                long delay = (long) Math.pow(2, attempt) * 1000L; // 2s, 4s, 8s
                Log.w(TAG, "Retry attempt " + attempt + " in " + delay + "ms");
                MAIN.postDelayed(() ->
                    uploadBoth(ctx, compressed, callback, attempt + 1), delay);
            } else {
                MAIN.post(() -> callback.onError(
                    new Exception("Video upload failed after " + MAX_RETRY + " retries")));
            }
            return;
        }

        // Cleanup temp files
        if (compressed.thumbFile != null && compressed.thumbFile.exists())
            compressed.thumbFile.delete();
        if (compressed.videoFile != null && compressed.videoFile.exists())
            compressed.videoFile.delete();

        final String tUrl = thumbUrl;
        final String vUrl = videoUrl;
        MAIN.post(() -> {
            callback.onProgress(100);
            callback.onSuccess(tUrl, vUrl,
                compressed.durationMs, compressed.width, compressed.height);
        });
    }

    // ── Progress-tracking RequestBody wrapper ─────────────────────────────

    interface ProgressListener {
        void onProgress(int percent);
    }

    private static class CountingRequestBody extends RequestBody {
        private final RequestBody  delegate;
        private final ProgressListener listener;

        CountingRequestBody(RequestBody delegate, ProgressListener listener) {
            this.delegate = delegate;
            this.listener = listener;
        }

        @Override public MediaType contentType()         { return delegate.contentType(); }
        @Override public long      contentLength() throws IOException { return delegate.contentLength(); }

        @Override
        public void writeTo(okio.BufferedSink sink) throws IOException {
            long total = contentLength();
            okio.ForwardingSink fw = new okio.ForwardingSink(sink) {
                long written = 0;
                @Override public void write(okio.Buffer source, long byteCount) throws IOException {
                    super.write(source, byteCount);
                    written += byteCount;
                    if (total > 0 && listener != null) {
                        listener.onProgress((int)(written * 100 / total));
                    }
                }
            };
            delegate.writeTo(okio.Okio.buffer(fw));
        }
    }

    private VideoUploader() {}
}
