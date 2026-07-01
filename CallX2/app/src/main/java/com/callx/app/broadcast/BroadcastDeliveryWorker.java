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

import com.callx.app.utils.PushNotify;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BroadcastDeliveryWorker — background-kill-safe delivery of a single broadcast message.
 *
 * Fixes vs the old executor-thread approach:
 *  ✅ Survives process death / app kill — WorkManager persists the job.
 *  ✅ Atomic delivery — ALL recipient writes (chat message + both contact nodes)
 *     are combined into ONE multi-path updateChildren() call. Firebase applies
 *     multi-location updates atomically, so there's no "half delivered" state.
 *  ✅ Blocked-user check — skips any recipient who has blocked the sender
 *     (blocks/{recipientUid}/{senderUid}) before writing anything to their chat.
 *  ✅ Retries with backoff on transient failure (network, Firebase outage),
 *     capped at 3 attempts, after which the message is marked "failed" so the
 *     user can manually retry from the chat screen.
 *  ✅ Direct PushNotify call (no reflection) — the old signature mismatch meant
 *     FCM pushes silently never fired.
 */
public class BroadcastDeliveryWorker extends Worker {

    private static final String TAG = "BroadcastDelivery";
    private static final int MAX_ATTEMPTS = 3;

    public static final String KEY_SENDER_ID  = "senderId";
    public static final String KEY_LIST_ID    = "listId";
    public static final String KEY_MSG_ID     = "msgId";
    public static final String KEY_TEXT       = "text";
    public static final String KEY_TYPE       = "type";
    public static final String KEY_MEDIA_URL  = "mediaUrl";
    public static final String KEY_FILE_NAME  = "fileName";
    public static final String KEY_CAPTION    = "caption";
    public static final String KEY_TIMESTAMP  = "timestamp";

    public BroadcastDeliveryWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /** Enqueue delivery for a broadcast message. Safe to call repeatedly (KEEP policy dedupes). */
    public static void enqueue(Context ctx, String senderId, String listId, String msgId,
                               String text, String type, String mediaUrl,
                               String fileName, String caption, long timestamp) {
        Data input = new Data.Builder()
                .putString(KEY_SENDER_ID, senderId)
                .putString(KEY_LIST_ID,   listId)
                .putString(KEY_MSG_ID,    msgId)
                .putString(KEY_TEXT,      text)
                .putString(KEY_TYPE,      type)
                .putString(KEY_MEDIA_URL, mediaUrl)
                .putString(KEY_FILE_NAME, fileName)
                .putString(KEY_CAPTION,   caption)
                .putLong(KEY_TIMESTAMP,   timestamp)
                .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(BroadcastDeliveryWorker.class)
                .setInputData(input)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build();

        // Unique per message — retrying the same message replaces the pending job
        // instead of stacking duplicate deliveries.
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork("broadcast_delivery_" + msgId,
                        ExistingWorkPolicy.REPLACE, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        Data in = getInputData();
        String senderId = in.getString(KEY_SENDER_ID);
        String listId   = in.getString(KEY_LIST_ID);
        String msgId    = in.getString(KEY_MSG_ID);
        String text     = in.getString(KEY_TEXT);
        String type     = in.getString(KEY_TYPE);
        String mediaUrl = in.getString(KEY_MEDIA_URL);
        String fileName = in.getString(KEY_FILE_NAME);
        String caption  = in.getString(KEY_CAPTION);
        long timestamp  = in.getLong(KEY_TIMESTAMP, System.currentTimeMillis());

        if (senderId == null || listId == null || msgId == null) {
            return Result.failure();
        }

        DatabaseReference root    = FirebaseDatabase.getInstance().getReference();
        DatabaseReference msgRef  = root.child("broadcast_messages").child(listId).child(msgId);
        DatabaseReference listRef = root.child("broadcast_lists").child(senderId).child(listId);

        try {
            // ── 1. Fetch recipients + sender profile ───────────────────────────
            DataSnapshot recipSnap = Tasks.await(
                    listRef.child("recipients").get(), 10, TimeUnit.SECONDS);
            DataSnapshot senderSnap = Tasks.await(
                    root.child("users").child(senderId).get(), 10, TimeUnit.SECONDS);

            String myName  = senderSnap.child("name").getValue(String.class);
            String myPhoto = senderSnap.child("photoUrl").getValue(String.class);
            if (myName == null)  myName  = "User";
            if (myPhoto == null) myPhoto = "";

            int total = (int) recipSnap.getChildrenCount();
            if (total == 0) {
                msgRef.child("status").setValue("failed");
                return Result.failure();
            }

            // ── 2. Build ONE atomic multi-path update, skipping blocked recipients ──
            Map<String, Object> bigUpdate = new HashMap<>();
            int delivered = 0;
            int skipped   = 0;

            for (DataSnapshot r : recipSnap.getChildren()) {
                String uid = r.getKey();
                if (uid == null) continue;

                // Did the recipient block the sender? If so, skip delivery entirely.
                DataSnapshot blockSnap = Tasks.await(
                        root.child("blocks").child(uid).child(senderId).get(),
                        5, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(blockSnap.getValue(Boolean.class))) {
                    skipped++;
                    continue;
                }

                DataSnapshot userSnap = Tasks.await(
                        root.child("users").child(uid).get(), 10, TimeUnit.SECONDS);
                String rName  = userSnap.child("name").getValue(String.class);
                String rPhoto = userSnap.child("photoUrl").getValue(String.class);
                String rToken = userSnap.child("fcmToken").getValue(String.class);
                if (rName == null) rName = "User";

                String chatId = senderId.compareTo(uid) < 0
                        ? senderId + "_" + uid
                        : uid + "_" + senderId;

                Map<String, Object> msg = new HashMap<>();
                msg.put("messageId", msgId);
                msg.put("senderId",  senderId);
                msg.put("text",      text     != null ? text     : "");
                msg.put("type",      type     != null ? type     : "text");
                msg.put("mediaUrl",  mediaUrl != null ? mediaUrl : "");
                msg.put("fileName",  fileName != null ? fileName : "");
                msg.put("caption",   caption  != null ? caption  : "");
                msg.put("timestamp", timestamp);
                msg.put("seen",      false);
                msg.put("broadcast", true);

                String preview = "text".equals(type) ? text : getTypeLabel(type);

                bigUpdate.put("chats/" + chatId + "/messages/" + msgId, msg);

                // Field-level keys (not whole-node keys) so this MERGES into the
                // existing contact node instead of replacing it wholesale —
                // Firebase multi-path update() replaces whatever value sits at
                // each key's path, so nesting a flat map under a node-level key
                // would wipe out any other existing fields on that contact.
                String senderContactBase = "contacts/" + senderId + "/" + uid + "/";
                bigUpdate.put(senderContactBase + "name",            rName);
                bigUpdate.put(senderContactBase + "photoUrl",        rPhoto != null ? rPhoto : "");
                bigUpdate.put(senderContactBase + "lastMessage",     preview);
                bigUpdate.put(senderContactBase + "lastMessageType", type);
                bigUpdate.put(senderContactBase + "lastMessageTime", timestamp);

                String recipContactBase = "contacts/" + uid + "/" + senderId + "/";
                bigUpdate.put(recipContactBase + "name",            myName);
                bigUpdate.put(recipContactBase + "photoUrl",        myPhoto);
                bigUpdate.put(recipContactBase + "lastMessage",     preview);
                bigUpdate.put(recipContactBase + "lastMessageType", type);
                bigUpdate.put(recipContactBase + "lastMessageTime", timestamp);
                bigUpdate.put(recipContactBase + "unread",          ServerValue.increment(1));

                delivered++;

                // Push notification — fire and forget, doesn't affect atomicity of data writes
                if (rToken != null && !rToken.isEmpty()) {
                    try {
                        PushNotify.notifyMessage(uid, senderId, myName, chatId, msgId,
                                preview, type, mediaUrl);
                    } catch (Exception pex) {
                        Log.w(TAG, "Push failed for " + uid + ": " + pex.getMessage());
                    }
                }
            }

            if (!bigUpdate.isEmpty()) {
                Tasks.await(root.updateChildren(bigUpdate), 20, TimeUnit.SECONDS);
            }

            // ── 3. Update message + list metadata ───────────────────────────────
            Map<String, Object> msgMeta = new HashMap<>();
            msgMeta.put("deliveredCount", delivered);
            msgMeta.put("skippedCount",   skipped);
            msgMeta.put("status",         delivered > 0 ? "sent" : "failed");
            msgRef.updateChildren(msgMeta);

            Map<String, Object> listUpdate = new HashMap<>();
            listUpdate.put("lastMessage",     "text".equals(type) ? text : getTypeLabel(type));
            listUpdate.put("lastMessageType", type);
            listUpdate.put("lastMessageTime", timestamp);
            listRef.updateChildren(listUpdate);
            listRef.child("sentCount").setValue(ServerValue.increment(1));

            if (delivered == 0) return Result.failure();
            return Result.success();

        } catch (Exception e) {
            Log.w(TAG, "Delivery attempt " + getRunAttemptCount() + " failed: " + e.getMessage());
            if (getRunAttemptCount() + 1 >= MAX_ATTEMPTS) {
                msgRef.child("status").setValue("failed");
                return Result.failure();
            }
            return Result.retry();
        }
    }

    private String getTypeLabel(String type) {
        if (type == null) return "Message";
        switch (type) {
            case "image": return "📷 Photo";
            case "video": return "🎥 Video";
            case "audio": return "🎤 Voice Message";
            case "file":  return "📄 Document";
            default:      return "Message";
        }
    }
}
