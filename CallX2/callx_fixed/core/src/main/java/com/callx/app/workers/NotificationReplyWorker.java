package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;
import java.util.Map;

/**
 * NotificationReplyWorker — Fix 3 & 8
 *
 * Pehle reply aur group-reply dono NotificationActionReceiver mein
 * new Thread() se hote the — killed state mein OS thread kill kar deta tha.
 *
 * Ab WorkManager use karo: guaranteed execution even in killed/doze state.
 * Supports both 1-1 chat (isGroup=false) and group (isGroup=true).
 */
public class NotificationReplyWorker extends Worker {

    public NotificationReplyWorker(@NonNull Context context,
                                   @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String myUid      = getInputData().getString("myUid");
        String myName     = getInputData().getString("myName");
        String chatId     = getInputData().getString("chatId");   // groupId for group
        String partnerUid = getInputData().getString("partnerUid"); // null for group
        String groupName  = getInputData().getString("groupName");  // Fix 11: group name
        String text       = getInputData().getString("text");
        boolean isGroup   = getInputData().getBoolean("isGroup", false);

        if (myUid == null || chatId == null || text == null || text.isEmpty()) {
            return Result.failure();
        }

        try {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());

            // Step 1: Firebase push key generate karo (offline bhi kaam karta hai)
            DatabaseReference msgRef = isGroup
                ? FirebaseUtils.getGroupMessagesRef(chatId).push()
                : FirebaseUtils.getMessagesRef(chatId).push();
            String msgKey = msgRef.getKey();
            if (msgKey == null) return Result.retry();

            // Step 2: Room mein pending status ke saath save karo (offline-first)
            MessageEntity entity = new MessageEntity();
            entity.id         = msgKey;
            entity.chatId     = chatId;
            entity.senderId   = myUid;
            entity.senderName = myName;
            entity.text       = text;
            entity.type       = "text";
            entity.timestamp  = System.currentTimeMillis();
            entity.status     = "pending";
            entity.isGroup    = isGroup;
            entity.syncedAt   = System.currentTimeMillis();
            db.messageDao().insertMessage(entity);

            // Step 3: Firebase push karo
            Map<String, Object> m = new HashMap<>();
            m.put("id",         msgKey);
            m.put("senderId",   myUid);
            m.put("senderName", myName);
            m.put("text",       text);
            m.put("type",       "text");
            m.put("timestamp",  entity.timestamp);

            // Synchronous set via CountDownLatch (Worker thread pe safe hai)
            final boolean[] success = {false};
            final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);

            msgRef.setValue(m)
                .addOnSuccessListener(unused -> { success[0] = true; latch.countDown(); })
                .addOnFailureListener(e -> latch.countDown());

            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);

            if (success[0]) {
                // Room mein status update karo
                db.messageDao().updateStatus(msgKey, "sent");

                if (isGroup) {
                    // Group meta update
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("lastMessage",     text);
                    meta.put("lastSenderName",  myName);
                    meta.put("lastMessageAt",   entity.timestamp);
                    meta.put("unread/" + myUid, 0);
                    FirebaseUtils.db().getReference("groups").child(chatId)
                        .updateChildren(meta);

                    // Fanout — baaki members ko notify karo
                    PushNotify.notifyGroupRich(chatId, myUid, myName, "",
                        msgKey, "group_message", text, "");
                } else {
                    // 1-1 chat: last message + unread counters update
                    Map<String, Object> meSide = new HashMap<>();
                    meSide.put("lastMessage",   text);
                    meSide.put("lastMessageAt", entity.timestamp);
                    meSide.put("unread",        0);
                    FirebaseUtils.getContactsRef(myUid).child(partnerUid)
                        .updateChildren(meSide);

                    Map<String, Object> partnerSide = new HashMap<>();
                    partnerSide.put("lastMessage",   text);
                    partnerSide.put("lastMessageAt", entity.timestamp);
                    partnerSide.put("unread",
                        com.google.firebase.database.ServerValue.increment(1));
                    FirebaseUtils.getContactsRef(partnerUid).child(myUid)
                        .updateChildren(partnerSide);

                    // Partner ko notify karo
                    PushNotify.notifyMessage(partnerUid, myUid, myName, chatId,
                        msgKey, text, "message", "");
                }
                return Result.success();
            } else {
                // Firebase fail — message Room mein pending hai, SyncWorker retry karega
                return Result.retry();
            }
        } catch (Exception e) {
            android.util.Log.e("NotificationReplyWorker", "doWork failed", e);
            return Result.retry();
        }
    }
}
