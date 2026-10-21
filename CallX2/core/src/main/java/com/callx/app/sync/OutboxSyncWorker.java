package com.callx.app.sync;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
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
 * Replaying an operation is safe because sends are create-if-absent transactions on the original
 * message id and edits/deletes are deterministic updateChildren calls.
 */
public class OutboxSyncWorker extends Worker {

    private static final String TAG = "OutboxSyncWorker";
    private static final int BATCH_SIZE = 40;
    private static final int MAX_ATTEMPTS = 10;
    /** Upload is aborted when no progress was reported for this long (replaces the old flat 2-minute cap). */
    private static final long UPLOAD_STALL_MS = TimeUnit.SECONDS.toMillis(90);
    /** Hard cap per file; also the cap when the uploader never reports progress (e.g. dedup / unknown size). */
    private static final long UPLOAD_MAX_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long UPLOAD_NO_PROGRESS_MAX_MS = TimeUnit.MINUTES.toMillis(5);
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
            // The foreground send path deletes the journal row once Firebase acked it; re-check so we
            // never replay a send that already succeeded in the meantime.
            if (db.outboxOperationDao().get(op.id) == null) continue;
            // Ordering: an edit / delete-for-everyone must never run before its message has actually been
            // delivered — updateChildren() on a missing node would create a stray partial message.
            if ((OfflineOutbox.OP_EDIT.equals(op.operationType)
                    || OfflineOutbox.OP_DELETE_EVERYONE.equals(op.operationType))
                    && op.messageId != null
                    && db.outboxOperationDao().countPendingSendFor(op.messageId) > 0) {
                continue;   // stays pending; picked up again once the send op is gone
            }
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
                    // Firebase write timed out => the connection is bad. Don't burn 45s per remaining op
                    // (head-of-line blocking); stop the batch and retry later. A failed media upload is
                    // file/CDN specific, so later text messages are still allowed to go out.
                    if (!OfflineOutbox.OP_MEDIA_UPLOAD.equals(op.operationType)) break;
                }
            } catch (InterruptedException ie) {
                // Worker cancelled/replaced mid-op: release the row untouched instead of counting an attempt.
                Thread.currentThread().interrupt();
                db.outboxOperationDao().updateAttempt(
                        op.id, "pending", op.attemptCount, System.currentTimeMillis(),
                        System.currentTimeMillis(), null);
                return Result.retry();
            } catch (Exception e) {
                Log.w(TAG, "Operation failed: " + op.id, e);
                fail(op, attempt, e.getMessage(), db);
                hadFailure = true;
            }
        }
        // Ops enqueued while this run was busy were skipped by OfflineOutbox.scheduleNow() (worker RUNNING);
        // pick them up here instead of leaving them parked until the next app start / enqueue.
        boolean moreDue = db.outboxOperationDao().countDue(System.currentTimeMillis()) > 0;
        return hadFailure || operations.size() == BATCH_SIZE || moreDue
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
        // Re-read: the foreground path may have delivered it (and the recipient may already have
        // delivered/read it) between getDue() and now.
        MessageEntity fresh = db.messageDao().getMessageById(entity.id);
        if (fresh == null) return true;
        if (isDelivered(fresh.status)) return true;

        Message message = MessageEntityMapper.toModel(entity);
        if (message == null) return true;
        String wireText = entity.wireText;
        String plainText = message.text;
        // Message.e2eWireText is @Exclude, so temporarily swapping text is the
        // only way to preserve the existing encrypted wire format here.
        if (wireText != null && !wireText.isEmpty()) message.text = wireText;
        boolean success;
        try {
            success = createIfAbsent(messageRef(op), message);
        } finally {
            message.text = plainText;
        }
        if (success) db.messageDao().updateStatus(entity.id, "sent");
        return success;
    }

    private static boolean isDelivered(String status) {
        return "sent".equals(status) || "delivered".equals(status)
                || "read".equals(status) || "seen".equals(status);
    }

    /**
     * Writes the message only if the node does not exist yet. A plain setValue() would overwrite
     * fields the recipient already wrote (delivered/read ticks, reactions) when the foreground send
     * and this worker both go out for the same message id. If the node exists, the send already
     * happened, which counts as success.
     */
    private static boolean createIfAbsent(DatabaseReference ref, Message message)
            throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] exists = {false};
        final boolean[] ok = {false};
        ref.runTransaction(new com.google.firebase.database.Transaction.Handler() {
            @NonNull @Override
            public com.google.firebase.database.Transaction.Result doTransaction(
                    @NonNull com.google.firebase.database.MutableData data) {
                exists[0] = data.getValue() != null;
                if (exists[0]) return com.google.firebase.database.Transaction.abort();
                data.setValue(message);
                return com.google.firebase.database.Transaction.success(data);
            }
            @Override
            public void onComplete(com.google.firebase.database.DatabaseError error,
                                   boolean committed,
                                   com.google.firebase.database.DataSnapshot snapshot) {
                ok[0] = error == null && (committed || exists[0]);
                latch.countDown();
            }
        }, false);
        latch.await(45, TimeUnit.SECONDS);
        return ok[0];
    }

    private boolean uploadMedia(OutboxOperationEntity op, AppDatabase db) throws Exception {
        MessageEntity entity = db.messageDao().getMessageById(op.messageId);
        if (entity == null) return true;
        String localPath = entity.mediaLocalPath != null
                ? entity.mediaLocalPath : op.mediaLocalPath;
        if (localPath == null || localPath.isEmpty()) {
            MediaFailureReasons.set(entity.id, MediaFailureReasons.FILE_MISSING);
            db.messageDao().updateStatus(entity.id, "failed");
            return true; // permanent local-file loss; manual send is required
        }
        final boolean needMain = entity.mediaUrl == null || entity.mediaUrl.isEmpty();
        final boolean needVoice = entity.voiceLocalPath != null && !entity.voiceLocalPath.isEmpty()
                && (entity.voiceUrl == null || entity.voiceUrl.isEmpty());
        try {
            if (needMain || needVoice) {
                // The bubble shows its own spinner ring while "uploading"; progress comes via OutboxProgressBus.
                db.messageDao().updateStatus(entity.id, "uploading");
                entity.status = "uploading";
            }
            if (needMain) {
                CloudinaryUploader.Result result = uploadFile(
                        Uri.parse(localPath), entity.type, op.mediaResourceType, op.mediaFileName, entity.id);
                if (result == null || result.secureUrl == null) return false;
                if (isCancelled(op, db)) return true;   // user cancelled while uploading: don't resurrect the message
                entity.mediaUrl = result.secureUrl;
                if (entity.thumbnailUrl == null && "image".equals(entity.type)) {
                    entity.thumbnailUrl = CloudinaryUploader.deriveThumbUrl(entity.mediaUrl, 480);
                }
                // Stage checkpoint: if the voice part / send fails, the retry resumes there instead of
                // re-uploading the (possibly large) main file.
                db.messageDao().insertMessage(entity);
            }
            if (needVoice) {
                CloudinaryUploader.Result voice = uploadFile(
                        Uri.parse(entity.voiceLocalPath), "audio", "auto", "voice.m4a", entity.id);
                if (voice == null || voice.secureUrl == null) return false;
                if (isCancelled(op, db)) return true;
                entity.voiceUrl = voice.secureUrl;
                db.messageDao().insertMessage(entity);
            }
        } finally {
            OutboxProgressBus.finish(entity.id);
        }
        if (isCancelled(op, db)) return true;
        entity.status = "pending";
        db.messageDao().insertMessage(entity);
        OfflineOutbox.enqueueSend(getApplicationContext(), entity);
        return true;
    }

    /** cancelUpload() deletes the journal row; if it is gone mid-run the upload result must be discarded. */
    private static boolean isCancelled(OutboxOperationEntity op, AppDatabase db) {
        return db.outboxOperationDao().get(op.id) == null;
    }

    /**
     * Uploads one file and waits for the result. Instead of a flat 2-minute cap, the wait is
     * stall-based: it keeps going while progress advances and gives up only after UPLOAD_STALL_MS
     * without any progress (or UPLOAD_MAX_MS overall). If the uploader never reports progress the
     * wait falls back to a flat UPLOAD_NO_PROGRESS_MAX_MS, so this is never stricter than before.
     * (The HTTP call itself cannot be cancelled from here; a timed-out upload is simply abandoned and retried.)
     */
    private CloudinaryUploader.Result uploadFile(Uri uri, String type,
                                                  String resourceType,
                                                  String fileName,
                                                  String messageId) throws InterruptedException {
        final CloudinaryUploader.Result[] result = {null};
        final long[] lastProgressAt = {SystemClock.elapsedRealtime()};
        final int[] lastPercent = {-1};
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
                    @Override public void onProgress(int percent) {
                        if (percent != lastPercent[0]) {
                            lastPercent[0] = percent;
                            lastProgressAt[0] = SystemClock.elapsedRealtime();
                        }
                        OutboxProgressBus.publish(messageId, percent);
                    }
                });
        final long start = SystemClock.elapsedRealtime();
        while (!latch.await(5, TimeUnit.SECONDS)) {
            if (isStopped()) break;
            long now = SystemClock.elapsedRealtime();
            boolean reportsProgress = lastPercent[0] >= 0;
            boolean stalled = reportsProgress
                    ? now - lastProgressAt[0] > UPLOAD_STALL_MS
                    : now - start > UPLOAD_NO_PROGRESS_MAX_MS;
            if (stalled || now - start > UPLOAD_MAX_MS) {
                Log.w(TAG, "Media upload abandoned (stalled/timeout) for " + messageId);
                break;
            }
        }
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
        // Only flip the bubble to the ⚠ tap-to-retry state once the queue has given up. While the op is
        // still "pending" (auto-retry scheduled) the bubble keeps its clock/uploading state.
        if (op.messageId != null && "failed".equals(state)) {
            MediaFailureReasons.set(op.messageId, MediaFailureReasons.classify(error));
            db.messageDao().updateStatus(op.messageId, "failed");
        }
    }
}