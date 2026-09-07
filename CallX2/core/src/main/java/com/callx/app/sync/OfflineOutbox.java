package com.callx.app.sync;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.db.entity.OutboxOperationEntity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Public entry point for all durable chat writes.
 *
 * Callers optimistically update Room first, then append the intent here.
 * The deterministic operation id makes retries and reconnect callbacks
 * idempotent: the same message can never be sent twice by the queue.
 */
public final class OfflineOutbox {

    public static final String OP_SEND = "send";
    public static final String OP_MEDIA_UPLOAD = "media_upload";
    public static final String OP_EDIT = "edit";
    public static final String OP_DELETE_EVERYONE = "delete_everyone";
    private static final String UNIQUE_WORK = "callx_chat_outbox";
    private static final Gson GSON = new Gson();

    private OfflineOutbox() {}

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                OutboxSyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(
                        androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(UNIQUE_WORK)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request);
    }

    public static void enqueueSend(Context context, MessageEntity message) {
        if (message == null || message.id == null || message.chatId == null) return;
        OutboxOperationEntity op = base(
                "send:" + message.chatId + ":" + message.id,
                message, OP_SEND);
        upsertAndSchedule(context, op);
    }

    public static void enqueueMediaUpload(Context context, MessageEntity message) {
        if (message == null || message.id == null || message.chatId == null
                || message.mediaLocalPath == null || message.mediaLocalPath.isEmpty()) return;
        OutboxOperationEntity op = base(
                "media:" + message.chatId + ":" + message.id,
                message, OP_MEDIA_UPLOAD);
        op.mediaLocalPath = message.mediaLocalPath;
        op.mediaResourceType = message.mediaResourceType;
        op.mediaFileName = message.fileName;
        upsertAndSchedule(context, op);
    }

    public static void enqueueEdit(Context context, String chatId, String messageId,
                                   String text, long editedAt, String historyJson,
                                   boolean isGroup) {
        if (chatId == null || messageId == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", text);
        payload.put("edited", true);
        payload.put("editedAt", editedAt);
        Type listType = new TypeToken<java.util.List<Map<String, Object>>>() {}.getType();
        if (historyJson != null) {
            payload.put("editHistory", GSON.fromJson(historyJson, listType));
        }
        OutboxOperationEntity op = new OutboxOperationEntity();
        op.id = "edit:" + chatId + ":" + messageId;
        op.chatId = chatId;
        op.messageId = messageId;
        op.operationType = OP_EDIT;
        op.payloadJson = GSON.toJson(payload);
        op.isGroup = isGroup;
        op.state = "pending";
        op.nextAttemptAt = 0L;
        op.createdAt = System.currentTimeMillis();
        op.updatedAt = op.createdAt;
        upsertAndSchedule(context, op);
    }

    public static void enqueueDeleteForEveryone(Context context, String chatId,
                                                String messageId, boolean isGroup) {
        if (chatId == null || messageId == null) return;
        OutboxOperationEntity op = new OutboxOperationEntity();
        op.id = "delete:" + chatId + ":" + messageId;
        op.chatId = chatId;
        op.messageId = messageId;
        op.operationType = OP_DELETE_EVERYONE;
        op.isGroup = isGroup;
        op.state = "pending";
        op.nextAttemptAt = 0L;
        op.createdAt = System.currentTimeMillis();
        op.updatedAt = op.createdAt;
        upsertAndSchedule(context, op);
    }

    /** Manual retry resets backoff and exposes the row immediately. */
    public static void retry(Context context, MessageEntity message) {
        if (message == null) return;
        com.callx.app.utils.AppBgExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(context.getApplicationContext());
            db.messageDao().updateStatus(message.id, "pending");
            enqueueSend(context, message);
        });
    }

    private static OutboxOperationEntity base(String id, MessageEntity message,
                                              String type) {
        OutboxOperationEntity op = new OutboxOperationEntity();
        op.id = id;
        op.chatId = message.chatId;
        op.messageId = message.id;
        op.operationType = type;
        op.isGroup = message.isGroup;
        op.state = "pending";
        op.attemptCount = 0;
        op.nextAttemptAt = 0L;
        op.createdAt = System.currentTimeMillis();
        op.updatedAt = op.createdAt;
        return op;
    }

    private static void upsertAndSchedule(Context context, OutboxOperationEntity op) {
        com.callx.app.utils.AppBgExecutor.execute(() -> {
            AppDatabase.getInstance(context.getApplicationContext())
                    .outboxOperationDao().upsert(op);
            schedule(context);
        });
    }
}