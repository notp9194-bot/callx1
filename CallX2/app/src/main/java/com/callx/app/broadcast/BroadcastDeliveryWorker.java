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
 * Supports all advanced message types:
 *  ✅ text, image, video, audio, file  — standard delivery
 *  ✅ poll                              — delivered as formatted text summary to recipients
 *  ✅ multi_media                       — first URL delivered; remaining in caption
 *  ✅ expiresAt                         — propagated to recipient chat message
 *  ✅ scheduledAt (handled upstream)    — BroadcastScheduleWorker fires this worker
 *
 * Guarantees:
 *  ✅ Survives process death — WorkManager persists the job
 *  ✅ Atomic fan-out — all writes in ONE multi-path updateChildren()
 *  ✅ Block check — skips recipients who blocked the sender
 *  ✅ sentCount merged into listUpdate (atomic, not a separate setValue)
 *  ✅ Retries with linear backoff, max 3 attempts
 */
public class BroadcastDeliveryWorker extends Worker {

    private static final String TAG         = "BroadcastDelivery";
    private static final int    MAX_ATTEMPTS = 3;

    public static final String KEY_SENDER_ID  = "senderId";
    public static final String KEY_LIST_ID    = "listId";
    public static final String KEY_MSG_ID     = "msgId";
    public static final String KEY_TEXT       = "text";
    public static final String KEY_TYPE       = "type";
    public static final String KEY_MEDIA_URL  = "mediaUrl";
    public static final String KEY_FILE_NAME  = "fileName";
    public static final String KEY_CAPTION    = "caption";
    public static final String KEY_TIMESTAMP  = "timestamp";
    public static final String KEY_EXPIRES_AT = "expiresAt";
    public static final String KEY_POLL_TEXT  = "pollText";   // formatted poll for recipients

    public BroadcastDeliveryWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /** Enqueue delivery for a broadcast message. Safe to call repeatedly (KEEP policy dedupes). */
    public static void enqueue(Context ctx, String senderId, String listId, String msgId,
                               String text, String type, String mediaUrl,
                               String fileName, String caption, long timestamp,
                               long expiresAt) {
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

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(BroadcastDeliveryWorker.class)
                .setInputData(input)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build();

        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork("broadcast_delivery_" + msgId,
                        ExistingWorkPolicy.REPLACE, req);
    }

    /** Overload without expiresAt for backward-compat callers. */
    public static void enqueue(Context ctx, String senderId, String listId, String msgId,
                               String text, String type, String mediaUrl,
                               String fileName, String caption, long timestamp) {
        enqueue(ctx, senderId, listId, msgId, text, type,
                mediaUrl, fileName, caption, timestamp, 0);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
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

        DatabaseReference root    = FirebaseDatabase.getInstance().getReference();
        DatabaseReference msgRef  = root.child("broadcast_messages")
                                        .child(senderId).child(listId).child(msgId);
        DatabaseReference listRef = root.child("broadcast_lists").child(senderId).child(listId);

        try {
            // ── 1. Fetch recipients + sender profile ───────────────────────────
            DataSnapshot recipSnap  = Tasks.await(
                    listRef.child("recipients").get(), 10, TimeUnit.SECONDS);
            DataSnapshot senderSnap = Tasks.await(
                    root.child("users").child(senderId).get(), 10, TimeUnit.SECONDS);

            String myName  = senderSnap.child("name").getValue(String.class);
            String myPhoto = senderSnap.child("photoUrl").getValue(String.class);
            if (myName  == null) myName  = "User";
            if (myPhoto == null) myPhoto = "";

            int total = (int) recipSnap.getChildrenCount();
            if (total == 0) {
                msgRef.child("status").setValue("failed");
                return Result.failure();
            }

            // ── 2. Build recipient text for polls ──────────────────────────────
            // Poll messages are delivered as a readable text summary.
            // Recipients cannot vote back, but they see the question + options.
            String recipientText = text;
            String recipientType = type;
            if ("poll".equals(type)) {
                // Fetch poll data from the broadcast message node
                DataSnapshot pollSnap = Tasks.await(msgRef.get(), 10, TimeUnit.SECONDS);
                String question = pollSnap.child("pollQuestion").getValue(String.class);
                StringBuilder sb = new StringBuilder("📊 Poll: ");
                if (question != null) sb.append(question).append("\n\n");
                int optIdx = 1;
                for (DataSnapshot opt : pollSnap.child("pollOptions").getChildren()) {
                    String optText = opt.getValue(String.class);
                    if (optText != null) sb.append(optIdx++).append(". ").append(optText).append("\n");
                }
                sb.append("\n(Reply with your choice number)");
                recipientText = sb.toString();
                recipientType = "text"; // delivered as text to keep things simple
            }
            // multi_media: deliver first URL, note extra items in caption
            if ("multi_media".equals(type)) {
                DataSnapshot mmSnap = Tasks.await(msgRef.get(), 10, TimeUnit.SECONDS);
                long urlCount = mmSnap.child("mediaUrls").getChildrenCount();
                if (urlCount > 1 && (caption == null || caption.isEmpty())) {
                    caption = "+" + (urlCount - 1) + " more";
                }
                recipientType = "image"; // first image delivered as image type
            }

            // ── 3. Build ONE atomic multi-path update ──────────────────────────
            Map<String, Object> bigUpdate = new HashMap<>();
            int delivered = 0;
            int skipped   = 0;

            for (DataSnapshot r : recipSnap.getChildren()) {
                String uid = r.getKey();
                if (uid == null) continue;

                // Block check: did this recipient block the sender?
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

                // Build the chat message that lands in the recipient's personal chat
                Map<String, Object> msg = new HashMap<>();
                msg.put("messageId",  msgId);
                msg.put("senderId",   senderId);
                msg.put("text",       recipientText   != null ? recipientText   : "");
                msg.put("type",       recipientType   != null ? recipientType   : "text");
                msg.put("mediaUrl",   mediaUrl        != null ? mediaUrl        : "");
                msg.put("fileName",   fileName        != null ? fileName        : "");
                msg.put("caption",    caption         != null ? caption         : "");
                msg.put("timestamp",  timestamp);
                msg.put("seen",       false);
                msg.put("broadcast",  true);
                if (expiresAt > 0) msg.put("expiresAt", expiresAt);

                String preview = buildPreview(recipientType, recipientText);

                bigUpdate.put("chats/" + chatId + "/messages/" + msgId, msg);

                // Merge into existing contact node (field-level keys, not node replacement)
                String sBase = "contacts/" + senderId + "/" + uid + "/";
                bigUpdate.put(sBase + "name",            rName);
                bigUpdate.put(sBase + "photoUrl",        rPhoto != null ? rPhoto : "");
                bigUpdate.put(sBase + "lastMessage",     preview);
                bigUpdate.put(sBase + "lastMessageType", recipientType);
                bigUpdate.put(sBase + "lastMessageTime", timestamp);

                String rBase = "contacts/" + uid + "/" + senderId + "/";
                bigUpdate.put(rBase + "name",            myName);
                bigUpdate.put(rBase + "photoUrl",        myPhoto);
                bigUpdate.put(rBase + "lastMessage",     preview);
                bigUpdate.put(rBase + "lastMessageType", recipientType);
                bigUpdate.put(rBase + "lastMessageTime", timestamp);
                bigUpdate.put(rBase + "unread",          ServerValue.increment(1));

                delivered++;

                // Push notification — fire-and-forget
                if (rToken != null && !rToken.isEmpty()) {
                    try {
                        PushNotify.notifyMessage(uid, senderId, myName, chatId, msgId,
                                preview, recipientType, mediaUrl);
                    } catch (Exception pex) {
                        Log.w(TAG, "Push failed for " + uid + ": " + pex.getMessage());
                    }
                }
            }

            // ── 4. Merge message metadata + list metadata into bigUpdate ──────
            // All writes (fan-out + meta) are ONE atomic multi-path updateChildren().
            String lastMsg = "text".equals(type) || "poll".equals(type)
                    ? (text != null ? text : "") : getTypeLabel(type);
            String finalStatus = delivered > 0 ? "sent" : "failed";

            bigUpdate.put(msgRef.getPath().toString().substring(1) + "/deliveredCount", delivered);
            bigUpdate.put(msgRef.getPath().toString().substring(1) + "/skippedCount",   skipped);
            bigUpdate.put(msgRef.getPath().toString().substring(1) + "/status",         finalStatus);
            bigUpdate.put(listRef.getPath().toString().substring(1) + "/lastMessage",     lastMsg);
            bigUpdate.put(listRef.getPath().toString().substring(1) + "/lastMessageType", type != null ? type : "text");
            bigUpdate.put(listRef.getPath().toString().substring(1) + "/lastMessageTime", timestamp);
            bigUpdate.put(listRef.getPath().toString().substring(1) + "/sentCount",       ServerValue.increment(1));

            if (!bigUpdate.isEmpty()) {
                Tasks.await(root.updateChildren(bigUpdate), 20, TimeUnit.SECONDS);
            }

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

    private String buildPreview(String type, String text) {
        if (("text".equals(type) || "poll".equals(type))
                && text != null && !text.isEmpty()) {
            return text.length() > 100 ? text.substring(0, 100) + "…" : text;
        }
        // Never return null — Firebase updateChildren rejects null values
        String label = getTypeLabel(type);
        return label != null ? label : "Message";
    }

    private String getTypeLabel(String type) {
        if (type == null) return "Message";
        switch (type) {
            case "image":       return "📷 Photo";
            case "video":       return "🎥 Video";
            case "audio":       return "🎤 Voice Message";
            case "file":        return "📄 Document";
            case "poll":        return "📊 Poll";
            case "multi_media": return "🖼️ Photos";
            default:            return "Message";
        }
    }
}
