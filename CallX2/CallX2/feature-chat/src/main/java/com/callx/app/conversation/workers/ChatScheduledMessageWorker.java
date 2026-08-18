package com.callx.app.conversation.workers;

import android.content.Context;

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

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.db.entity.ScheduledMessageEntity;
import com.callx.app.models.Message;
import com.callx.app.models.ScheduledMessage;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ChatScheduledMessageWorker — WorkManager worker that fires at the
 * scheduled time and publishes a previously queued chat message.
 *
 * Mirrors XScheduledPostWorker's shape exactly (fetch the queued draft →
 * write it to the live location → clean up the queue entry → done), just
 * pointed at a 1:1 chat thread instead of the X feed:
 *
 *   scheduledMessages/{chatId}/{scheduleId}  →  messages/{chatId}/{newMsgId}
 *
 * Runs even if the app/ChatActivity isn't open — WorkManager survives
 * process death, which is the whole point of scheduling instead of just
 * using a Handler.postDelayed() inside the Activity.
 *
 * Usage:
 *   ChatScheduledMessageWorker.schedule(context, chatId, scheduleId, sendAt);
 */
public class ChatScheduledMessageWorker extends Worker {

    private static final String KEY_CHAT_ID     = "chat_id";
    private static final String KEY_SCHEDULE_ID = "schedule_id";

    public ChatScheduledMessageWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String chatId     = getInputData().getString(KEY_CHAT_ID);
        String scheduleId = getInputData().getString(KEY_SCHEDULE_ID);
        if (chatId == null || chatId.isEmpty() || scheduleId == null || scheduleId.isEmpty()) {
            return Result.failure();
        }

        CountDownLatch latch = new CountDownLatch(1);
        final Result[] outcome = {Result.failure()};

        FirebaseUtils.getScheduledMessagesRef(chatId).child(scheduleId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    ScheduledMessage sm = snap.getValue(ScheduledMessage.class);
                    if (sm == null) {
                        // Already fired/cancelled by another path (e.g. user
                        // cancelled right as this worker started) — not an error.
                        outcome[0] = Result.success();
                        latch.countDown();
                        return;
                    }

                    DatabaseReference messagesRef = FirebaseUtils.getMessagesRef(chatId);
                    String newMsgId = messagesRef.push().getKey();
                    if (newMsgId == null) {
                        outcome[0] = Result.retry();
                        latch.countDown();
                        return;
                    }

                    Message m       = new Message();
                    m.id            = newMsgId;
                    m.messageId     = newMsgId;
                    m.senderId      = sm.senderId;
                    m.senderName    = sm.senderName;
                    m.text          = sm.text;
                    m.type          = sm.type != null ? sm.type : "text";
                    m.timestamp     = System.currentTimeMillis();
                    m.status        = "sent";
                    m.fontStyle     = sm.fontStyle;

                    Map<String, Object> updates = new HashMap<>();
                    updates.put("/messages/" + chatId + "/" + newMsgId, m);
                    // Remove the now-fired entry from the scheduled queue.
                    updates.put("/scheduledMessages/" + chatId + "/" + scheduleId, null);

                    String previewText = m.text != null ? m.text : "[message]";

                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference().updateChildren(updates)
                        .addOnSuccessListener(v -> {
                            // Same "last message" + unread-count bookkeeping a live
                            // send would do — mirrors ChatMessageSender#firebasePushMessage.
                            updateContactsAndNotify(chatId, sm, previewText);
                            cacheLocally(chatId, newMsgId, sm, m.timestamp);
                            outcome[0] = Result.success();
                            latch.countDown();
                        })
                        .addOnFailureListener(e -> {
                            outcome[0] = Result.retry();
                            latch.countDown();
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    outcome[0] = Result.retry();
                    latch.countDown();
                }
            });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return Result.retry();
        }
        return outcome[0];
    }

    private void updateContactsAndNotify(String chatId, ScheduledMessage sm, String previewText) {
        if (sm.senderId == null || sm.partnerUid == null) return;
        long ts = System.currentTimeMillis();

        Map<String, Object> myUpd = new HashMap<>();
        myUpd.put("lastMessage", previewText);
        myUpd.put("lastTs", ts);
        FirebaseUtils.getContactsRef(sm.senderId).child(sm.partnerUid).updateChildren(myUpd);

        Map<String, Object> theirUpd = new HashMap<>();
        theirUpd.put("lastMessage", previewText);
        theirUpd.put("lastTs", ts);
        FirebaseUtils.getContactsRef(sm.partnerUid).child(sm.senderId).updateChildren(theirUpd);

        FirebaseUtils.getContactsRef(sm.partnerUid).child(sm.senderId).child("unread")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    Long cur = s.getValue(Long.class);
                    s.getRef().setValue((cur != null ? cur : 0) + 1);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        PushNotify.notifyMessage(sm.partnerUid, sm.senderId, sm.senderName,
                chatId, null, previewText, "text", "");
    }

    /** Best-effort local Room insert + scheduled-row cleanup so the device
     *  that scheduled it sees the message instantly without waiting for the
     *  Firebase ChildEventListener round-trip — same local-first spirit as
     *  ChatMessageSender. Safe to skip on failure; the live listener will
     *  still pick the message up from Firebase shortly after. */
    private void cacheLocally(String chatId, String newMsgId, ScheduledMessage sm, long timestamp) {
        try {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            MessageEntity e = new MessageEntity();
            e.id         = newMsgId;
            e.chatId     = chatId;
            e.senderId   = sm.senderId;
            e.senderName = sm.senderName;
            e.text       = sm.text;
            e.type       = sm.type != null ? sm.type : "text";
            e.timestamp  = timestamp;
            e.status     = "sent";
            e.fontStyle  = sm.fontStyle;
            e.syncedAt   = System.currentTimeMillis();
            db.messageDao().insertMessage(e);
            db.scheduledMessageDao().deleteById(sm.id);
        } catch (Exception ignored) {
            // Non-fatal — Firebase already has the canonical copy.
        }
    }

    // ── Static schedule / cancel helpers ────────────────────────────────────

    public static void schedule(Context ctx, String chatId, String scheduleId, long sendAtMs) {
        long delay = Math.max(0, sendAtMs - System.currentTimeMillis());

        Data inputData = new Data.Builder()
            .putString(KEY_CHAT_ID, chatId)
            .putString(KEY_SCHEDULE_ID, scheduleId)
            .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ChatScheduledMessageWorker.class)
            .setInputData(inputData)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(workTag(chatId, scheduleId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            workTag(chatId, scheduleId),
            ExistingWorkPolicy.REPLACE,
            request);
    }

    /** Cancel a previously scheduled message — removes both the queued
     *  WorkManager job and the Firebase queue entry. */
    public static void cancel(Context ctx, String chatId, String scheduleId) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(workTag(chatId, scheduleId));
        FirebaseUtils.getScheduledMessagesRef(chatId).child(scheduleId).removeValue();
    }

    private static String workTag(String chatId, String scheduleId) {
        return "scheduled_msg_" + chatId + "_" + scheduleId;
    }
}
