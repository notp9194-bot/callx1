package com.callx.app.sync;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.db.entity.OutboxOperationEntity;
import com.callx.app.models.Message;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.MessageEntityMapper;
import com.google.firebase.database.DatabaseReference;
import com.google.android.gms.tasks.Task;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Process-death-safe chat outbox.
 *
 * One operation is marked processing before it touches the network. Success
 * deletes the journal row; failure leaves it with exponential nextAttemptAt.
 * Replaying an operation is safe because Firebase writes use the original
 * message id and edits/deletes are deterministic updateChildren calls.
 */
public class OutboxSyncWorker extends Worker {

    private static final String TAG = "OutboxSyncWorker";
    private static final int BATCH_SIZE = 40;
    private static final int MAX_ATTEMPTS = 10;
    private static final Gson GSON = new Gson();

    public OutboxSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        long now = System.currentTimeMillis();
        db.outboxOperationDao().recoverStaleProcessing(
                now - TimeUnit.MINUTES.toMillis(5), now);
        List<OutboxOperationEntity> operations =
                db.outboxOperationDao().getDue(now, BATCH_SIZE);
        if (operations == null || operations.isEmpty()) return Result.success();

        boolean hadFailure = false;
        for (OutboxOperationEntity op : operations) {
            if (isStopped()) return Result.retry();
            int attempt = op.attemptCount + 1;
            db.outboxOperationDao().updateAttempt(
                    op.id, "processing", attempt, Long.MAX_VALUE,
                    System.currentTimeMillis(), null);
            try {
                boolean success = process(op, db);
                if (success) {
                    db.outboxOperationDao().delete(op.id);
                } else {
                    fail(op, attempt, "network operation did not complete", db);
                    hadFailure = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Operation failed: " + op.id, e);
                fail(op, attempt, e.getMessage(), db);
                hadFailure = true;
            }
        }
        // A successful batch can still leave more queued work. Returning
        // retry lets WorkManager run the next bounded batch without relying
        // on another app-start or enqueue event.
        return hadFailure || operations.size() == BATCH_SIZE
                ? Result.retry() : Result.success();
    }

    private boolean process(OutboxOperationEntity op, AppDatabase db) throws Exception {
        if (OfflineOutbox.OP_MEDIA_UPLOAD.equals(op.operationType)) {
            return uploadMedia(op, db);
        }
        MessageEntity entity = db.messageDao().getMessageById(op.messageId);
        if (OfflineOutbox.OP_SEND.equals(op.operationType)) {
            if (entity == null) return true;
            return sendMessage(op, entity, db);
        }
        DatabaseReference ref = messageRef(op);
        if (OfflineOutbox.OP_EDIT.equals(op.operationType)) {
            Map<String, Object> payload = GSON.fromJson(
                    op.payloadJson, new TypeToken<Map<String, Object>>() {}.getType());
            return await(ref.updateChildren(payload));
        }
        if (OfflineOutbox.OP_DELETE_EVERYONE.equals(op.operationType)) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("deleted", true);
            payload.put("text", "");
            payload.put("deletedAt", System.currentTimeMillis());
            return await(ref.updateChildren(payload));
        }
        return true;
    }

    private boolean sendMessage(OutboxOperationEntity op, MessageEntity entity,
                                AppDatabase db) throws Exception {
        Message message = MessageEntityMapper.toModel(entity);
        if (message == null) return true;
        String wireText = entity.wireText;
        String plainText = message.text;
        // Message.e2eWireText is @Exclude, so temporarily swapping text is the
        // only way to preserve the existing encrypted wire format here.
        if (wireText != null && !wireText.isEmpty()) message.text = wireText;
        boolean success = await(messageRef(op).setValue(message));
        message.text = plainText;
        if (success) db.messageDao().updateStatus(entity.id, "sent");
        return success;
    }

    private boolean uploadMedia(OutboxOperationEntity op, AppDatabase db) throws Exception {
        MessageEntity entity = db.messageDao().getMessageById(op.messageId);
        if (entity == null) return true;
        String localPath = entity.mediaLocalPath != null
                ? entity.mediaLocalPath : op.mediaLocalPath;
        if (localPath == null || localPath.isEmpty()) {
            db.messageDao().updateStatus(entity.id, "failed");
            return true; // permanent local-file loss; manual send is required
        }
        if (entity.mediaUrl == null || entity.mediaUrl.isEmpty()) {
            CloudinaryUploader.Result result = uploadFile(
                    Uri.parse(localPath), entity.type, op.mediaResourceType, op.mediaFileName);
            if (result == null || result.secureUrl == null) return false;
            entity.mediaUrl = result.secureUrl;
            if (entity.thumbnailUrl == null && "image".equals(entity.type)) {
                entity.thumbnailUrl = CloudinaryUploader.deriveThumbUrl(entity.mediaUrl, 480);
            }
        }
        if (entity.voiceLocalPath != null && !entity.voiceLocalPath.isEmpty()
                && (entity.voiceUrl == null || entity.voiceUrl.isEmpty())) {
            CloudinaryUploader.Result voice = uploadFile(
                    Uri.parse(entity.voiceLocalPath), "audio", "auto", "voice.m4a");
            if (voice == null || voice.secureUrl == null) return false;
            entity.voiceUrl = voice.secureUrl;
        }
        entity.status = "pending";
        db.messageDao().insertMessage(entity);
        OfflineOutbox.enqueueSend(getApplicationContext(), entity);
        return true;
    }

    private CloudinaryUploader.Result uploadFile(Uri uri, String type,
                                                  String resourceType,
                                                  String fileName) throws InterruptedException {
        final CloudinaryUploader.Result[] result = {null};
        CountDownLatch latch = new CountDownLatch(1);
        String resource = resourceType != null && !resourceType.isEmpty()
                ? resourceType : "auto";
        CloudinaryUploader.upload(
                getApplicationContext(), uri,
                "callx/" + (type != null ? type : "media"),
                resource, fileName,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result value) {
                        result[0] = value; latch.countDown();
                    }
                    @Override public void onError(String value) {
                        Log.w(TAG, "Media upload: " + value); latch.countDown();
                    }
                });
        latch.await(2, TimeUnit.MINUTES);
        return result[0];
    }

    private DatabaseReference messageRef(OutboxOperationEntity op) {
        DatabaseReference root = op.isGroup != null && op.isGroup
                ? FirebaseUtils.getGroupMessagesRef(op.chatId)
                : FirebaseUtils.getMessagesRef(op.chatId);
        return root.child(op.messageId);
    }

    private static boolean await(Task<Void> task) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        task.addOnSuccessListener(value -> { ok[0] = true; latch.countDown(); })
                .addOnFailureListener(error -> latch.countDown());
        latch.await(45, TimeUnit.SECONDS);
        return ok[0];
    }

    private static void fail(OutboxOperationEntity op, int attempt, String error,
                             AppDatabase db) {
        long exponent = Math.min(8, Math.max(0, attempt - 1));
        long delay = Math.min(TimeUnit.HOURS.toMillis(6),
                TimeUnit.SECONDS.toMillis(30) * (1L << exponent));
        String state = attempt >= MAX_ATTEMPTS ? "failed" : "pending";
        db.outboxOperationDao().updateAttempt(
                op.id, state, attempt, System.currentTimeMillis() + delay,
                System.currentTimeMillis(), error != null ? error : "unknown error");
        if (op.messageId != null) {
            db.messageDao().updateStatus(op.messageId, "failed");
        }
    }
}