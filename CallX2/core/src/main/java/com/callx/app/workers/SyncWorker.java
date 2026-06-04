package com.callx.app.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SyncWorker — WorkManager Worker for retrying failed offline media uploads.
 */
public class SyncWorker extends Worker {

    private static final String TAG       = "SyncWorker";
    private static final String WORK_NAME = "media_upload_sync";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SCHEDULE
    // ─────────────────────────────────────────────────────────────────────

    public static void schedule(@NonNull Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request);

        Log.d(TAG, "SyncWorker scheduled");
    }

    public static void cancel(@NonNull Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }

    // ─────────────────────────────────────────────────────────────────────
    // DO WORK
    // ─────────────────────────────────────────────────────────────────────

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "SyncWorker starting...");

        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        // FIX 1: getPendingMediaUploads() → getFailedMediaUploads()
        List<MessageEntity> pending = db.messageDao().getFailedMediaUploads();

        if (pending == null || pending.isEmpty()) {
            Log.d(TAG, "SyncWorker: No pending uploads found");
            return Result.success();
        }

        Log.d(TAG, "SyncWorker: Found " + pending.size() + " pending uploads");

        int failCount = 0;

        for (MessageEntity msg : pending) {
            if (isStopped()) {
                Log.d(TAG, "SyncWorker: Stopped mid-run");
                break;
            }

            try {
                boolean ok = processOneMessage(db, msg);
                if (!ok) failCount++;
            } catch (Exception e) {
                Log.e(TAG, "SyncWorker: Error processing msg " + msg.id, e);
                failCount++;
            }
        }

        if (failCount > 0) {
            Log.w(TAG, "SyncWorker: " + failCount + " uploads still failed — retrying later");
            return Result.retry();
        }

        Log.d(TAG, "SyncWorker: All uploads complete");
        return Result.success();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PROCESS ONE PENDING MESSAGE
    // ─────────────────────────────────────────────────────────────────────

    private boolean processOneMessage(AppDatabase db, MessageEntity msg) throws Exception {
        // 1. Verify local file exists
        File localFile = new File(msg.mediaLocalPath);
        if (!localFile.exists()) {
            Log.w(TAG, "Local file gone: " + msg.mediaLocalPath + " — marking as failed");
            msg.mediaLocalPath    = null;
            msg.mediaResourceType = null;
            // FIX 2: insertOrReplace() → insertMessage() (already uses REPLACE strategy)
            db.messageDao().insertMessage(msg);
            return true; // Don't retry a deleted file
        }

        Log.d(TAG, "Uploading: " + msg.mediaLocalPath + " type=" + msg.mediaResourceType);

        // 2. Upload to Cloudinary — synchronous (Worker runs on background thread)
        String resourceType = msg.mediaResourceType != null ? msg.mediaResourceType : "auto";
        String[] resultUrl  = {null};
        Object   lock       = new Object();

        // FIX 3: Use correct CloudinaryUploader.upload() signature with UploadCallback interface
        CloudinaryUploader.upload(
                getApplicationContext(),
                android.net.Uri.fromFile(localFile),
                "callx",          // folder param (required)
                resourceType,
                new CloudinaryUploader.UploadCallback() {
                    @Override
                    public void onSuccess(CloudinaryUploader.Result result) {
                        synchronized (lock) {
                            resultUrl[0] = result.secureUrl != null ? result.secureUrl : "";
                            lock.notifyAll();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        synchronized (lock) {
                            resultUrl[0] = ""; // empty = failed
                            lock.notifyAll();
                        }
                    }
                }
        );

        // Wait for upload (max 120 seconds)
        synchronized (lock) {
            if (resultUrl[0] == null) {
                lock.wait(120_000);
            }
        }

        if (resultUrl[0] == null || resultUrl[0].isEmpty()) {
            Log.w(TAG, "Upload failed for msg: " + msg.id);
            return false;
        }

        String cdnUrl = resultUrl[0];
        Log.d(TAG, "Upload success: " + cdnUrl);

        // 3. Update Room DB
        msg.mediaUrl          = cdnUrl;
        msg.mediaLocalPath    = null; // cleared — won't be retried
        msg.mediaResourceType = null;
        // FIX 2: insertOrReplace() → insertMessage()
        db.messageDao().insertMessage(msg);

        // 4. Push to Firebase RTDB
        pushToFirebase(msg, cdnUrl);

        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIREBASE PUSH
    // ─────────────────────────────────────────────────────────────────────

    private void pushToFirebase(MessageEntity msg, String cdnUrl) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId",    msg.id);
        payload.put("senderId",     msg.senderId);
        payload.put("senderName",   msg.senderName);
        payload.put("senderPhoto",  msg.senderPhoto);
        payload.put("text",         msg.text);
        payload.put("type",         msg.type);
        payload.put("mediaUrl",     cdnUrl);
        payload.put("thumbnailUrl", msg.thumbnailUrl);
        payload.put("fileName",     msg.fileName);
        payload.put("fileSize",     msg.fileSize);
        payload.put("duration",     msg.duration);
        payload.put("timestamp",    msg.timestamp);
        payload.put("status",       msg.status != null ? msg.status : "sent");
        payload.put("replyToId",    msg.replyToId);
        payload.put("replyToText",  msg.replyToText);
        payload.put("forwardedFrom",msg.forwardedFrom);
        payload.put("fontStyle",    msg.fontStyle);
        // Remove null values
        payload.values().removeIf(v -> v == null);

        FirebaseUtils.getMessagesRef(msg.chatId).child(msg.id)
                .setValue(payload)
                .addOnSuccessListener(v -> {
                    Log.d(TAG, "Firebase push success: " + msg.id);
                    updateChatListPreview(msg);
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "Firebase push failed for msg: " + msg.id, e));
    }

    private void updateChatListPreview(MessageEntity msg) {
        if (msg.chatId == null || !msg.chatId.contains("_")) return;
        String[] parts = msg.chatId.split("_", 2);
        if (parts.length != 2) return;

        String preview = buildPreview(msg);
        Map<String, Object> update = new HashMap<>();
        update.put("lastMessage",   preview);
        update.put("lastTimestamp", msg.timestamp);
        update.put("lastSenderId",  msg.senderId);

        for (String uid : parts) {
            FirebaseUtils.db().getReference("chatList").child(uid).child(msg.chatId)
                    .updateChildren(update);
        }
    }

    private String buildPreview(MessageEntity msg) {
        switch (msg.type != null ? msg.type : "text") {
            case "image":  return "\uD83D\uDCF7 Photo";
            case "video":  return "\uD83C\uDFAC Video";
            case "audio":  return "\uD83C\uDFA4 Voice message";
            case "file":   return "\uD83D\uDCCE " + (msg.fileName != null ? msg.fileName : "File");
            default:       return msg.text != null ? msg.text : "";
        }
    }
}
