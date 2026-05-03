package com.callx.app.workers;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.Constants;
import com.callx.app.utils.VideoCompressor;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * VideoUploadWorker — Offline queue for video uploads via Cloudinary
 *
 * Jab network nahi hota ya upload beech mein fail ho:
 *   → localPath save karo Room me (mediaLocalPath column)
 *   → VideoUploadWorker enqueue karo
 *   → Network aate hi → compress → upload via Cloudinary → Firebase DB update
 *
 * Usage (enqueue):
 *   VideoUploadWorker.enqueue(ctx, localVideoPath, chatId, messageId, isGroup);
 *
 * WorkManager automatically retries with exponential backoff.
 *
 * NOTE: Firebase Storage removed — uploads now go to Cloudinary.
 */
public class VideoUploadWorker extends Worker {

    private static final String TAG = "VideoUploadWorker";

    public static final String KEY_LOCAL_PATH  = "localPath";
    public static final String KEY_CHAT_ID     = "chatId";
    public static final String KEY_MESSAGE_ID  = "messageId";
    public static final String KEY_IS_GROUP    = "isGroup";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120,   TimeUnit.SECONDS)
        .writeTimeout(300,  TimeUnit.SECONDS)
        .build();

    public VideoUploadWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String  localPath  = getInputData().getString(KEY_LOCAL_PATH);
        String  chatId     = getInputData().getString(KEY_CHAT_ID);
        String  messageId  = getInputData().getString(KEY_MESSAGE_ID);
        boolean isGroup    = getInputData().getBoolean(KEY_IS_GROUP, false);

        if (localPath == null || chatId == null || messageId == null) {
            Log.e(TAG, "Missing input data");
            return Result.failure();
        }

        File localFile = new File(localPath);
        if (!localFile.exists()) {
            Log.e(TAG, "Local file missing: " + localPath);
            return Result.failure();
        }

        try {
            return uploadAndUpdate(localFile, chatId, messageId, isGroup);
        } catch (Exception e) {
            Log.e(TAG, "Worker error: " + e.getMessage());
            return Result.retry();
        }
    }

    private Result uploadAndUpdate(File localFile, String chatId,
                                   String messageId, boolean isGroup)
        throws Exception {

        Context ctx = getApplicationContext();

        // 1. Compress
        Log.i(TAG, "Compressing: " + localFile.length() / 1024 + " KB");
        VideoCompressor.Result compressed = VideoCompressor.compressSync(
            ctx,
            Uri.fromFile(localFile),
            pct -> Log.d(TAG, "Compress: " + pct + "%")
        );
        Log.i(TAG, compressed.compressionSummary());

        // 2. Upload both files to Cloudinary (blocking via CountDownLatch)
        CountDownLatch           latch    = new CountDownLatch(2);
        AtomicBoolean            failed   = new AtomicBoolean(false);
        AtomicReference<String>  thumbUrl = new AtomicReference<>();
        AtomicReference<String>  videoUrl = new AtomicReference<>();

        // Upload thumbnail (image/webp)
        new Thread(() -> {
            try {
                String url = uploadFileToCloudinary(
                    compressed.thumbFile, "image", "callx/videos/thumb");
                thumbUrl.set(url);
            } catch (Exception e) {
                Log.e(TAG, "Thumb upload failed: " + e.getMessage());
                failed.set(true);
            } finally {
                latch.countDown();
            }
        }).start();

        // Upload video (video/mp4)
        new Thread(() -> {
            try {
                String url = uploadFileToCloudinary(
                    compressed.videoFile, "video", "callx/videos/file");
                videoUrl.set(url);
            } catch (Exception e) {
                Log.e(TAG, "Video upload failed: " + e.getMessage());
                failed.set(true);
            } finally {
                latch.countDown();
            }
        }).start();

        // Wait up to 10 minutes for large videos
        boolean done = latch.await(10, TimeUnit.MINUTES);
        if (!done || failed.get()) {
            Log.w(TAG, "Upload timeout or failure — will retry");
            return Result.retry();
        }

        // 3. Update Firebase Realtime DB message node with Cloudinary URLs
        String dbPath = isGroup
            ? "groupMessages/" + chatId + "/" + messageId
            : "chats/" + chatId + "/messages/" + messageId;

        HashMap<String, Object> updates = new HashMap<>();
        updates.put("mediaUrl",       videoUrl.get());
        updates.put("thumbnailUrl",   thumbUrl.get());
        updates.put("status",         "sent");
        updates.put("mediaLocalPath", (Object) null);
        updates.put("duration",       compressed.durationMs);
        updates.put("width",          compressed.width);
        updates.put("height",         compressed.height);

        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference(dbPath)
            .updateChildren(updates);

        // 4. Cleanup temp files
        if (compressed.thumbFile != null && compressed.thumbFile.exists())
            compressed.thumbFile.delete();
        if (compressed.videoFile != null && compressed.videoFile.exists())
            compressed.videoFile.delete();

        Log.i(TAG, "Offline video upload complete (Cloudinary): " + messageId);
        return Result.success();
    }

    /** Blocking Cloudinary upload: sign → upload → return secure_url */
    private String uploadFileToCloudinary(File file, String resourceType, String folder)
        throws Exception {

        // Sign
        JSONObject payload = new JSONObject()
            .put("folder", folder)
            .put("resource_type", resourceType);

        Request signReq = new Request.Builder()
            .url(Constants.SERVER_URL + "/cloudinary/sign")
            .post(RequestBody.create(payload.toString(),
                MediaType.parse("application/json")))
            .build();

        Response signRes = HTTP.newCall(signReq).execute();
        String signBody = signRes.body() != null ? signRes.body().string() : "";
        signRes.close();

        if (!signRes.isSuccessful())
            throw new Exception("Sign failed (" + signRes.code() + "): " + signBody);

        JSONObject s  = new JSONObject(signBody);
        String sig    = s.getString("signature");
        String ts     = s.getString("timestamp");
        String apiKey = s.getString("api_key");
        String cloud  = s.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
        String f      = s.optString("folder", folder);
        String rt     = s.optString("resource_type", resourceType);

        // Upload
        String mime = "image".equals(rt) ? "image/webp" : "video/mp4";
        String ext  = "image".equals(rt) ? "webp" : "mp4";

        MultipartBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "upload." + ext,
                RequestBody.create(file, MediaType.parse(mime)))
            .addFormDataPart("api_key",   apiKey)
            .addFormDataPart("timestamp", ts)
            .addFormDataPart("signature", sig)
            .addFormDataPart("folder",    f)
            .build();

        String upUrl = "https://api.cloudinary.com/v1_1/" + cloud + "/" + rt + "/upload";
        Request upReq = new Request.Builder().url(upUrl).post(body).build();

        Response upRes = HTTP.newCall(upReq).execute();
        String upBody  = upRes.body() != null ? upRes.body().string() : "";
        upRes.close();

        if (!upRes.isSuccessful())
            throw new Exception("Cloudinary upload failed (" + upRes.code() + "): " + upBody);

        JSONObject j = new JSONObject(upBody);
        String url   = j.optString("secure_url", j.optString("url"));
        if (url == null || url.isEmpty())
            throw new Exception("No URL in Cloudinary response");

        Log.d(TAG, "Cloudinary [" + rt + "]: " + url);
        return url;
    }

    // ── Static helper to enqueue ──────────────────────────────────────────

    public static void enqueue(Context ctx,
                               String localVideoPath,
                               String chatId,
                               String messageId,
                               boolean isGroup) {

        Data input = new Data.Builder()
            .putString(KEY_LOCAL_PATH, localVideoPath)
            .putString(KEY_CHAT_ID,    chatId)
            .putString(KEY_MESSAGE_ID, messageId)
            .putBoolean(KEY_IS_GROUP,  isGroup)
            .build();

        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(VideoUploadWorker.class)
            .setInputData(input)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .addTag("video_upload_" + messageId)
            .build();

        WorkManager.getInstance(ctx).enqueue(request);
        Log.i(TAG, "Enqueued offline video upload (Cloudinary): " + messageId);
    }
}
