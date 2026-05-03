package com.callx.app.workers;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.utils.ImageCompressor;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ImageUploadWorker — offline queue for image uploads
 *
 * Jab network nahi hota:
 *   → LocalPath save karo Room me (mediaLocalPath column)
 *   → ImageUploadWorker enqueue karo
 *   → Network aate hi → compress → upload → URL update in Firebase
 *
 * Usage (enqueue):
 *   ImageUploadWorker.enqueue(ctx, localImagePath, chatId, messageId);
 *
 * Data keys:
 *   KEY_LOCAL_PATH  — absolute path to original image file
 *   KEY_CHAT_ID     — Firebase chat node ID
 *   KEY_MESSAGE_ID  — Firebase message node ID (to update URL when done)
 */
public class ImageUploadWorker extends Worker {

    private static final String TAG = "ImageUploadWorker";

    public static final String KEY_LOCAL_PATH  = "localPath";
    public static final String KEY_CHAT_ID     = "chatId";
    public static final String KEY_MESSAGE_ID  = "messageId";

    public ImageUploadWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String localPath = getInputData().getString(KEY_LOCAL_PATH);
        String chatId    = getInputData().getString(KEY_CHAT_ID);
        String messageId = getInputData().getString(KEY_MESSAGE_ID);

        if (localPath == null || chatId == null || messageId == null) {
            Log.e(TAG, "Missing input data");
            return Result.failure();
        }

        File localFile = new File(localPath);
        if (!localFile.exists()) {
            Log.e(TAG, "Local file not found: " + localPath);
            return Result.failure();
        }

        try {
            return uploadAndUpdate(localFile, chatId, messageId);
        } catch (Exception e) {
            Log.e(TAG, "Upload worker failed", e);
            return Result.retry();
        }
    }

    private Result uploadAndUpdate(File localFile, String chatId, String messageId)
        throws Exception {

        Context ctx = getApplicationContext();
        String imageId = UUID.randomUUID().toString();

        // 1. Compress
        ImageCompressor.Result compressed = ImageCompressor.compressSync(
            ctx, Uri.fromFile(localFile));

        // 2. Upload both (blocking with latch)
        CountDownLatch latch = new CountDownLatch(2);
        AtomicBoolean  failed = new AtomicBoolean(false);
        AtomicReference<String> thumbUrl = new AtomicReference<>();
        AtomicReference<String> fullUrl  = new AtomicReference<>();

        FirebaseStorage storage = FirebaseStorage.getInstance();

        // Upload thumb
        StorageReference thumbRef = storage.getReference()
            .child("images/thumb/" + imageId + ".webp");
        thumbRef.putFile(Uri.fromFile(compressed.thumbFile))
            .continueWithTask(t -> { if (!t.isSuccessful()) throw t.getException(); return thumbRef.getDownloadUrl(); })
            .addOnSuccessListener(uri -> { thumbUrl.set(uri.toString()); latch.countDown(); })
            .addOnFailureListener(e  -> { failed.set(true); latch.countDown(); });

        // Upload full
        StorageReference fullRef  = storage.getReference()
            .child("images/full/"  + imageId + ".webp");
        fullRef.putFile(Uri.fromFile(compressed.fullFile))
            .continueWithTask(t -> { if (!t.isSuccessful()) throw t.getException(); return fullRef.getDownloadUrl(); })
            .addOnSuccessListener(uri -> { fullUrl.set(uri.toString()); latch.countDown(); })
            .addOnFailureListener(e  -> { failed.set(true); latch.countDown(); });

        // Wait max 60 seconds
        boolean done = latch.await(60, TimeUnit.SECONDS);
        if (!done || failed.get()) return Result.retry();

        // 3. Update Firebase message with URLs
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("chats")
            .child(chatId)
            .child("messages")
            .child(messageId)
            .updateChildren(new java.util.HashMap<String, Object>() {{
                put("thumbUrl",    thumbUrl.get());
                put("fullUrl",     fullUrl.get());
                put("status",      "sent");
                put("mediaLocalPath", null);   // clear local path
            }});

        // 4. Cleanup temp files
        compressed.thumbFile.delete();
        compressed.fullFile.delete();

        Log.i(TAG, "Offline upload complete: " + messageId);
        return Result.success();
    }

    // ── Static helper to enqueue ──────────────────────────────────────────

    /**
     * Enqueue an offline image upload.
     * WorkManager will retry when network is available.
     */
    public static void enqueue(Context ctx,
                               String localImagePath,
                               String chatId,
                               String messageId) {
        Data inputData = new Data.Builder()
            .putString(KEY_LOCAL_PATH,  localImagePath)
            .putString(KEY_CHAT_ID,     chatId)
            .putString(KEY_MESSAGE_ID,  messageId)
            .build();

        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(ImageUploadWorker.class)
            .setInputData(inputData)
            .setConstraints(constraints)
            // Retry with exponential backoff
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS)
            .addTag("image_upload_" + messageId)
            .build();

        WorkManager.getInstance(ctx).enqueue(workRequest);
        Log.i(TAG, "Enqueued offline upload for message: " + messageId);
    }
}
