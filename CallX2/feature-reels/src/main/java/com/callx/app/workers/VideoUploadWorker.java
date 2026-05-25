package com.callx.app.workers;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.utils.Constants;
import com.callx.app.utils.VideoCompressor;
import com.callx.app.utils.VideoQualityPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * VideoUploadWorker v24 — Offline queue for video uploads via Cloudinary.
 *
 * FIXES vs previous version:
 *  ✅ setForeground() removed — uses NotificationManager.notify() directly
 *     (avoids WorkManager version dependency for ForegroundInfo)
 *  ✅ PendingIntent type correct for NotificationCompat.Action
 *  ✅ UploadCallback.onSuccess() keeps original 5-param signature (no breaking change)
 *
 * NEW in v24:
 *  ✅ Progress notification with % during compress + upload
 *  ✅ Quality-aware compression (reads KEY_QUALITY from input data)
 *  ✅ Chunked Cloudinary upload (5 MB chunks)
 *  ✅ Stores compressionSummary + savingsPercent in Firebase DB node
 *  ✅ Done / error notifications
 *
 * Usage:
 *   VideoUploadWorker.enqueue(ctx, localVideoPath, chatId, messageId, isGroup);
 *   VideoUploadWorker.enqueue(ctx, path, chatId, msgId, isGroup, Quality.HD);
 */
public class VideoUploadWorker extends Worker {

    private static final String TAG = "VideoUploadWorker";

    public static final String KEY_LOCAL_PATH  = "localPath";
    public static final String KEY_CHAT_ID     = "chatId";
    public static final String KEY_MESSAGE_ID  = "messageId";
    public static final String KEY_IS_GROUP    = "isGroup";
    public static final String KEY_QUALITY     = "quality";

    private static final String NOTIF_CHANNEL = "callx_video_upload";
    private static final int    NOTIF_ID_BASE = 9000;
    private static final long   CHUNK_SIZE    = 5L * 1024 * 1024; // 5 MB

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(30,  TimeUnit.SECONDS)
        .readTimeout(120,    TimeUnit.SECONDS)
        .writeTimeout(300,   TimeUnit.SECONDS)
        .build();

    public VideoUploadWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
        ensureNotifChannel(ctx);
    }

    @NonNull
    @Override
    public Result doWork() {
        String  localPath  = getInputData().getString(KEY_LOCAL_PATH);
        String  chatId     = getInputData().getString(KEY_CHAT_ID);
        String  messageId  = getInputData().getString(KEY_MESSAGE_ID);
        boolean isGroup    = getInputData().getBoolean(KEY_IS_GROUP, false);
        String  qualityStr = getInputData().getString(KEY_QUALITY);

        if (localPath == null || chatId == null || messageId == null) {
            Log.e(TAG, "Missing input data");
            return Result.failure();
        }
        File localFile = new File(localPath);
        if (!localFile.exists()) {
            Log.e(TAG, "Local file missing: " + localPath);
            return Result.failure();
        }

        VideoQualityPreferences.Quality quality =
            VideoQualityPreferences.Quality.fromName(
                qualityStr != null ? qualityStr : "STANDARD");

        try {
            return uploadAndUpdate(localFile, chatId, messageId, isGroup, quality);
        } catch (Exception e) {
            Log.e(TAG, "Worker error: " + e.getMessage(), e);
            showErrorNotification(messageId, e.getMessage());
            return Result.retry();
        }
    }

    // ── Upload + Firebase update ──────────────────────────────────────────

    private Result uploadAndUpdate(File localFile, String chatId, String messageId,
                                   boolean isGroup,
                                   VideoQualityPreferences.Quality quality) throws Exception {
        Context ctx = getApplicationContext();

        // 1. Compress
        showProgress(messageId, 5, "Compressing video…");
        Log.i(TAG, "Compressing [" + quality.label + "]: "
            + localFile.length() / 1024 + " KB");

        VideoCompressor.Result compressed = VideoCompressor.compressSync(
            ctx,
            Uri.fromFile(localFile),
            quality,
            pct -> showProgress(messageId, (int)(pct * 0.40f), "Compressing… " + pct + "%")
        );
        Log.i(TAG, compressed.compressionSummary());

        // 2. Upload thumbnail (40–50%)
        showProgress(messageId, 42, "Uploading thumbnail…");
        String thumbUrl = uploadSingle(compressed.thumbFile, "image", "callx/videos/thumb");

        // 3. Upload video chunked (50–95%)
        showProgress(messageId, 50, "Uploading video…");
        String videoUrl = uploadChunked(compressed.videoFile, "callx/videos/file",
            pct -> showProgress(messageId, 50 + (int)(pct * 0.45f),
                "Uploading… " + pct + "%"));

        showProgress(messageId, 97, "Finalizing…");

        // 4. Record compression stats
        new VideoQualityPreferences(ctx)
            .recordCompression(compressed.originalBytes, compressed.compressedBytes);

        // 5. Update Firebase Realtime DB
        String dbPath = isGroup
            ? "groupMessages/" + chatId + "/" + messageId
            : "chats/" + chatId + "/messages/" + messageId;

        HashMap<String, Object> updates = new HashMap<>();
        updates.put("mediaUrl",            videoUrl);
        updates.put("thumbnailUrl",        thumbUrl);
        updates.put("status",              "sent");
        updates.put("mediaLocalPath",      (Object) null);
        updates.put("duration",            compressed.durationMs);
        updates.put("width",               compressed.width);
        updates.put("height",              compressed.height);
        updates.put("compressionSummary",  compressed.compressionSummary());
        updates.put("savingsPercent",      compressed.savingsPercent());
        updates.put("codecUsed",           compressed.codecUsed);

        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference(dbPath)
            .updateChildren(updates);

        // 6. Cleanup
        VideoCompressor.safeDelete(compressed.thumbFile);
        VideoCompressor.safeDelete(compressed.videoFile);

        // 7. Done notification
        showDoneNotification(messageId, compressed.compressionSummary());
        Log.i(TAG, "Upload complete: " + messageId);
        return Result.success();
    }

    // ── Notifications (direct NotificationManager — no setForeground needed) ──

    private void showProgress(String messageId, int progress, String text) {
        if (isStopped()) return;
        Context ctx    = getApplicationContext();
        int     notifId = NOTIF_ID_BASE + Math.abs(messageId.hashCode());
        NotificationManager nm =
            (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Cancel action uses WorkManager's PendingIntent
        PendingIntent cancelPi = WorkManager.getInstance(ctx)
            .createCancelPendingIntent(getId());

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Sending video")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelPi);

        nm.notify(notifId, nb.build());
    }

    private void showDoneNotification(String messageId, String summary) {
        Context ctx    = getApplicationContext();
        int     notifId = NOTIF_ID_BASE + Math.abs(messageId.hashCode());
        NotificationManager nm =
            (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.cancel(notifId); // remove progress notification

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Video uploaded")
            .setContentText(summary)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        nm.notify(notifId + 1, nb.build());
    }

    private void showErrorNotification(String messageId, String error) {
        Context ctx    = getApplicationContext();
        int     notifId = NOTIF_ID_BASE + Math.abs(messageId.hashCode()) + 2;
        NotificationManager nm =
            (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Video upload failed")
            .setContentText(error != null ? error : "Tap to retry")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        nm.notify(notifId, nb.build());
    }

    private static void ensureNotifChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
            (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(NOTIF_CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(
            NOTIF_CHANNEL, "Video Uploads", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Video upload progress notifications");
        nm.createNotificationChannel(ch);
    }

    // ── Single-part upload ────────────────────────────────────────────────

    private String uploadSingle(File file, String resourceType, String folder)
        throws Exception {
        if (file == null || !file.exists() || file.length() == 0)
            throw new Exception("File missing/empty for " + resourceType);

        JSONObject s = signRequest(folder, resourceType);
        String apiKey = s.getString("api_key");
        String sig    = s.getString("signature");
        String ts     = s.getString("timestamp");
        String cloud  = s.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
        String f      = s.optString("folder", folder);
        String rt     = s.optString("resource_type", resourceType);

        String mime = "image".equals(rt) ? "image/webp" : "video/mp4";
        String ext  = "image".equals(rt) ? "webp" : "mp4";

        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "upload." + ext,
                RequestBody.create(file, MediaType.parse(mime)))
            .addFormDataPart("api_key",   apiKey)
            .addFormDataPart("timestamp", ts)
            .addFormDataPart("signature", sig)
            .addFormDataPart("folder",    f)
            .build();

        String upUrl = "https://api.cloudinary.com/v1_1/" + cloud + "/" + rt + "/upload";
        Response upRes = HTTP.newCall(new Request.Builder().url(upUrl).post(body).build())
            .execute();
        String upBody  = upRes.body() != null ? upRes.body().string() : "";
        upRes.close();
        if (!upRes.isSuccessful()) throw new Exception("Upload failed: " + upBody);

        String url = new JSONObject(upBody).optString("secure_url",
            new JSONObject(upBody).optString("url", ""));
        if (url.isEmpty()) throw new Exception("No URL in response");
        return url;
    }

    // ── Chunked video upload ──────────────────────────────────────────────

    interface ProgressCb { void onProgress(int pct); }

    private String uploadChunked(File file, String folder, ProgressCb progress)
        throws Exception {
        if (file == null || !file.exists() || file.length() == 0)
            throw new Exception("Video file missing/empty");

        if (file.length() <= CHUNK_SIZE)
            return uploadSingle(file, "video", folder);

        JSONObject s = signRequest(folder, "video");
        String apiKey  = s.getString("api_key");
        String sig     = s.getString("signature");
        String ts      = s.getString("timestamp");
        String cloud   = s.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
        String f       = s.optString("folder", folder);
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        String upUrl   = "https://api.cloudinary.com/v1_1/" + cloud + "/video/upload";

        long   fileSize    = file.length();
        long   offset      = 0;
        int    chunkNum    = 0;
        int    totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
        String lastUrl     = null;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] chunkBuf = new byte[(int) CHUNK_SIZE];
            while (offset < fileSize) {
                if (isStopped()) throw new Exception("Worker stopped");
                int bytesRead = fis.read(chunkBuf);
                if (bytesRead <= 0) break;
                byte[] chunkData = Arrays.copyOf(chunkBuf, bytesRead);
                long end = offset + bytesRead - 1;

                RequestBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "chunk.mp4",
                        RequestBody.create(chunkData, MediaType.parse("video/mp4")))
                    .addFormDataPart("api_key",   apiKey)
                    .addFormDataPart("timestamp", ts)
                    .addFormDataPart("signature", sig)
                    .addFormDataPart("folder",    f)
                    .build();

                Request req = new Request.Builder()
                    .url(upUrl).post(multipart)
                    .header("X-Unique-Upload-Id", uploadId)
                    .header("Content-Range", "bytes " + offset + "-" + end + "/" + fileSize)
                    .build();

                Response res  = HTTP.newCall(req).execute();
                String resBody = res.body() != null ? res.body().string() : "";
                res.close();

                if (res.code() == 200) {
                    lastUrl = new JSONObject(resBody).optString("secure_url",
                        new JSONObject(resBody).optString("url", ""));
                } else if (res.code() != 308) {
                    throw new Exception("Chunk " + chunkNum
                        + " failed (" + res.code() + "): " + resBody);
                }
                offset += bytesRead;
                chunkNum++;
                if (progress != null)
                    progress.onProgress(
                        Math.min((int)((float) chunkNum / totalChunks * 100), 99));
            }
        }

        if (lastUrl == null || lastUrl.isEmpty())
            throw new Exception("No URL after chunked upload");
        if (progress != null) progress.onProgress(100);
        return lastUrl;
    }

    // ── Sign helper ───────────────────────────────────────────────────────

    private JSONObject signRequest(String folder, String resourceType) throws Exception {
        JSONObject payload = new JSONObject()
            .put("folder", folder).put("resource_type", resourceType);
        Request req = new Request.Builder()
            .url(Constants.SERVER_URL + "/cloudinary/sign")
            .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
            .build();
        Response res  = HTTP.newCall(req).execute();
        String   body = res.body() != null ? res.body().string() : "";
        res.close();
        if (!res.isSuccessful()) throw new Exception("Sign failed (" + res.code() + "): " + body);
        return new JSONObject(body);
    }

    // ── Enqueue helpers ───────────────────────────────────────────────────

    public static void enqueue(Context ctx, String localVideoPath,
                               String chatId, String messageId, boolean isGroup) {
        enqueue(ctx, localVideoPath, chatId, messageId, isGroup,
            VideoQualityPreferences.Quality.STANDARD);
    }

    public static void enqueue(Context ctx, String localVideoPath,
                               String chatId, String messageId, boolean isGroup,
                               VideoQualityPreferences.Quality quality) {
        Data input = new Data.Builder()
            .putString(KEY_LOCAL_PATH,  localVideoPath)
            .putString(KEY_CHAT_ID,     chatId)
            .putString(KEY_MESSAGE_ID,  messageId)
            .putBoolean(KEY_IS_GROUP,   isGroup)
            .putString(KEY_QUALITY,     quality.name())
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
        Log.i(TAG, "Enqueued offline video upload [" + quality.label + "]: " + messageId);
    }
}
