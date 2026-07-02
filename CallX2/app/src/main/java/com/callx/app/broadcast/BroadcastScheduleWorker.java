package com.callx.app.broadcast;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.FirebaseDatabase;

import java.util.concurrent.TimeUnit;

/**
 * BroadcastScheduleWorker — fires at the scheduled time and kicks off
 * BroadcastDeliveryWorker for actual fan-out.
 *
 * Workflow:
 *   1. BroadcastChatActivity.dispatchScheduledBroadcast() calls
 *      BroadcastScheduleWorker.enqueue(ctx, delay, ...) with an initial delay.
 *   2. WorkManager fires this worker when the delay elapses.
 *   3. This worker updates the message status from "scheduled" → "sending"
 *      and enqueues BroadcastDeliveryWorker to do the actual delivery.
 *
 * This separation keeps BroadcastDeliveryWorker stateless and simple
 * (it always delivers immediately when it runs).
 */
public class BroadcastScheduleWorker extends Worker {

    private static final String TAG = "BroadcastSchedule";

    public static final String KEY_SENDER_ID  = "sched_senderId";
    public static final String KEY_LIST_ID    = "sched_listId";
    public static final String KEY_MSG_ID     = "sched_msgId";
    public static final String KEY_TEXT       = "sched_text";
    public static final String KEY_TYPE       = "sched_type";
    public static final String KEY_MEDIA_URL  = "sched_mediaUrl";
    public static final String KEY_FILE_NAME  = "sched_fileName";
    public static final String KEY_CAPTION    = "sched_caption";
    public static final String KEY_TIMESTAMP  = "sched_timestamp";
    public static final String KEY_EXPIRES_AT = "sched_expiresAt";

    public BroadcastScheduleWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    /**
     * Enqueue a scheduled broadcast delivery.
     *
     * @param delayMs milliseconds from now until delivery should fire.
     */
    public static void enqueue(Context ctx, long delayMs,
                               String senderId, String listId, String msgId,
                               String text, String type, String mediaUrl,
                               String fileName, String caption,
                               long timestamp, long expiresAt) {
        Data input = new Data.Builder()
                .putString(KEY_SENDER_ID,  senderId)
                .putString(KEY_LIST_ID,    listId)
                .putString(KEY_MSG_ID,     msgId)
                .putString(KEY_TEXT,       text)
                .putString(KEY_TYPE,       type)
                .putString(KEY_MEDIA_URL,  mediaUrl)
                .putString(KEY_FILE_NAME,  fileName)
                .putString(KEY_CAPTION,    caption)
                .putLong(KEY_TIMESTAMP,    timestamp)
                .putLong(KEY_EXPIRES_AT,   expiresAt)
                .build();

        long delay = Math.max(0, delayMs);

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(BroadcastScheduleWorker.class)
                .setInputData(input)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build();

        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork("broadcast_schedule_" + msgId,
                        ExistingWorkPolicy.REPLACE, req);

        Log.d(TAG, "Scheduled broadcast " + msgId + " in " + (delay / 60_000) + " min");
    }

    /** Cancel a previously scheduled broadcast (user cancelled the schedule). */
    public static void cancel(Context ctx, String msgId) {
        WorkManager.getInstance(ctx.getApplicationContext())
                .cancelUniqueWork("broadcast_schedule_" + msgId);
    }

    @NonNull
    @Override
    public Result doWork() {
        Data in = getInputData();
        String senderId  = in.getString(KEY_SENDER_ID);
        String listId    = in.getString(KEY_LIST_ID);
        String msgId     = in.getString(KEY_MSG_ID);
        String text      = in.getString(KEY_TEXT);
        String type      = in.getString(KEY_TYPE);
        String mediaUrl  = in.getString(KEY_MEDIA_URL);
        String fileName  = in.getString(KEY_FILE_NAME);
        String caption   = in.getString(KEY_CAPTION);
        long timestamp   = in.getLong(KEY_TIMESTAMP, System.currentTimeMillis());
        long expiresAt   = in.getLong(KEY_EXPIRES_AT, 0);

        if (senderId == null || listId == null || msgId == null) {
            return Result.failure();
        }

        // Update status to "sending" so the UI reflects the change
        FirebaseUtils.db()
                .getReference("broadcast_messages")
                .child(senderId).child(listId).child(msgId)
                .child("status").setValue("sending");

        // Hand off to BroadcastDeliveryWorker for the actual fan-out
        BroadcastDeliveryWorker.enqueue(getApplicationContext(),
                senderId, listId, msgId, text, type, mediaUrl,
                fileName, caption, timestamp, expiresAt);

        Log.d(TAG, "Scheduled broadcast " + msgId + " fired — handed to DeliveryWorker");
        return Result.success();
    }
}
