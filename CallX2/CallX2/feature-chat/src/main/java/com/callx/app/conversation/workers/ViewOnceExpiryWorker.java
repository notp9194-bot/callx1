package com.callx.app.conversation.workers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.conversation.controllers.ChatViewOnceController;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ViewOnceExpiryWorker — Feature 7: runs after viewOnceExpiresAt to mark message expired.
 *
 * Enqueued by ChatActivity.scheduleViewOnceExpiry() when sender picks an expiry duration.
 * Before marking expired, checks viewOnceState == "sent" on Firebase — if the receiver
 * already opened the message, no action is taken (avoid overwriting "opened"/"deleted").
 *
 * On success: viewOnceState = "expired", content permanently wiped, receiver sees "Expired".
 */
public class ViewOnceExpiryWorker extends Worker {

    public ViewOnceExpiryWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String messageId = getInputData().getString("messageId");
        String chatId    = getInputData().getString("chatId");
        if (messageId == null || chatId == null) return Result.failure();

        DatabaseReference msgRef = FirebaseUtils.db()
                .getReference("chats").child(chatId).child("messages").child(messageId);

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] shouldExpire = {false};

        // Only expire if still in "sent" state (not yet opened)
        msgRef.child("viewOnceState").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                String state = s.getValue(String.class);
                shouldExpire[0] = ChatViewOnceController.STATE_SENT.equals(state);
                latch.countDown();
            }
            @Override public void onCancelled(DatabaseError e) { latch.countDown(); }
        });

        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        if (!shouldExpire[0]) return Result.success(); // already opened or revoked — skip

        // Wipe content + set expired state
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        updates.put(ChatViewOnceController.FIELD_VIEW_ONCE_STATE, ChatViewOnceController.STATE_EXPIRED);
        updates.put(ChatViewOnceController.FIELD_DELETED,         true);
        updates.put(ChatViewOnceController.FIELD_TEXT,            "");
        updates.put(ChatViewOnceController.FIELD_MEDIA_URL,       null);
        updates.put(ChatViewOnceController.FIELD_THUMBNAIL_URL,   null);
        updates.put("fileName",                                   null);

        CountDownLatch writeLatch = new CountDownLatch(1);
        final boolean[] writeOk = {false};
        msgRef.updateChildren(updates, (error, ref) -> {
            writeOk[0] = (error == null);
            writeLatch.countDown();
        });
        try { writeLatch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        return writeOk[0] ? Result.success() : Result.retry();
    }
}
