package com.callx.app.disappear;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DisappearingMessageWorker — Auto-deletes messages after their expiry time.
 *
 * How it works:
 *   1. Runs every 15 minutes in background
 *   2. Queries Room DB for messages where disappearAt <= now AND deleted != true
 *   3. Marks them deleted locally + pushes delete to Firebase
 *
 * To enable disappearing messages for a chat:
 *   Set ChatEntity.disappearTimer = duration in ms (e.g., 24 * 60 * 60 * 1000L for 24h)
 *   When a message is sent/received, set MessageEntity.disappearAt = timestamp + disappearTimer
 *
 * Options: 5 seconds (test), 24 hours, 7 days, 90 days (WhatsApp-style).
 */
public class DisappearingMessageWorker extends Worker {

    private static final String TAG          = "DisappearWorker";
    private static final String WORK_NAME    = "disappearing_messages";
    private static final int    MAX_ATTEMPTS = 3;

    public DisappearingMessageWorker(@NonNull Context context,
                                     @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override
    public Result doWork() {
        if (getRunAttemptCount() >= MAX_ATTEMPTS) {
            Log.w(TAG, "Max attempts reached, giving up");
            return Result.failure();
        }

        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return Result.retry();

        try {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            long now = System.currentTimeMillis();

            List<MessageEntity> expired = db.messageDao().getExpiredMessages(now);
            if (expired.isEmpty()) return Result.success();

            Log.d(TAG, "Deleting " + expired.size() + " expired messages");

            for (MessageEntity msg : expired) {
                db.messageDao().markDeleted(msg.id);

                if (msg.senderId != null && msg.senderId.equals(uid)) {
                    FirebaseDatabase.getInstance()
                        .getReference("chats")
                        .child(msg.chatId)
                        .child("messages")
                        .child(msg.id)
                        .child("deleted")
                        .setValue(true);
                }
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage());
            return Result.retry();
        }
    }

    public static void schedule(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                DisappearingMessageWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build();

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request);

        Log.d(TAG, "Scheduled disappearing message worker");
    }

    /**
     * Timer options for disappearing messages (in milliseconds).
     * Show these in ChatActivity settings dialog.
     */
    public static final long TIMER_OFF    = 0L;
    public static final long TIMER_5_SEC  = 5_000L;           // dev/test
    public static final long TIMER_24H    = 86_400_000L;       // 1 day
    public static final long TIMER_7_DAYS = 604_800_000L;      // 7 days
    public static final long TIMER_90_DAYS= 7_776_000_000L;    // 90 days

    public static String timerLabel(long ms) {
        if (ms <= 0)                    return "Off";
        if (ms == TIMER_24H)            return "24 hours";
        if (ms == TIMER_7_DAYS)         return "7 days";
        if (ms == TIMER_90_DAYS)        return "90 days";
        return ms / 1000 + " seconds";
    }
}
