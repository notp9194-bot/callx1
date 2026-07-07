package com.callx.app.utils;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;

import java.util.HashMap;
import java.util.Map;

/**
 * TICK FIX v19 (+ v20 advances): single, atomic source of truth for message
 * status upgrades (sent -> delivered -> read).
 *
 * v19: runTransaction() instead of read-then-write — race-safe, monotonic
 * (pending < sent < delivered < read), never downgrades.
 *
 * v20 additions:
 *  - deliveredAt / readAt server timestamps written alongside the status,
 *    so "message info" style UI (and debugging) has real data to show.
 *  - Every call is queued in PendingAckQueue first and only dequeued once
 *    the transaction actually commits — a crash/offline gap mid-write no
 *    longer loses the ack; GlobalDeliveryAckManager retries it later.
 *  - "sent" writes also drop an entry in deliveryPending/{msgId}; delivered/
 *    read writes remove it. The backend (index.js) uses that small index as
 *    a fallback safety net for messages that never get a client ACK at all.
 */
public final class MessageStatusSync {

    private static final String TAG = "MessageStatusSync";

    private MessageStatusSync() {}

    /** Ordinal rank so we only ever move status forward: sent < delivered < read. */
    private static int rank(String status) {
        if (status == null) return -1;
        switch (status) {
            case "pending":   return 0;
            case "sent":      return 1;
            case "delivered": return 2;
            case "read":
            case "seen":      return 3;
            default:          return -1; // "failed" etc. — don't touch via this path
        }
    }

    /** Back-compat overload — no retry-queue / timestamp / index bookkeeping. */
    public static void upgradeStatus(@NonNull DatabaseReference chatMessagesRef,
                                      @NonNull String msgId,
                                      @NonNull String targetStatus) {
        upgradeStatus(null, chatMessagesRef, null, msgId, targetStatus);
    }

    /**
     * Full version: atomically upgrades status, stamps deliveredAt/readAt,
     * queues the attempt for retry until it commits, and maintains the
     * deliveryPending server-fallback index.
     *
     * @param ctx     nullable — pass a Context to get retry-queue + index
     *                bookkeeping; null gives you the bare transaction only.
     * @param chatId  nullable — required only for retry-queue/index bookkeeping.
     */
    public static void upgradeStatus(@Nullable Context ctx,
                                      @NonNull DatabaseReference chatMessagesRef,
                                      @Nullable String chatId,
                                      @NonNull String msgId,
                                      @NonNull String targetStatus) {
        final int targetRank = rank(targetStatus);
        if (targetRank < 0) return;

        final Context appCtx = ctx != null ? ctx.getApplicationContext() : null;
        if (appCtx != null && chatId != null) {
            PendingAckQueue.enqueue(appCtx, chatId, msgId, targetStatus);
        }

        DatabaseReference msgRef = chatMessagesRef.child(msgId);
        msgRef.child("status").runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                String cur = currentData.getValue(String.class);
                if (rank(cur) >= targetRank) {
                    return Transaction.success(currentData); // already there/further — no-op
                }
                currentData.setValue(targetStatus);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed,
                                    DataSnapshot currentData) {
                if (error != null) {
                    Log.w(TAG, "upgradeStatus(" + targetStatus + ") failed, stays queued: "
                            + error.getMessage());
                    return; // leave it in PendingAckQueue — GlobalDeliveryAckManager retries later
                }
                if (appCtx != null && chatId != null) {
                    PendingAckQueue.dequeue(appCtx, chatId, msgId, targetStatus);
                }
                if (!committed) return; // status was already this or further along

                // Stamp the timestamp for "message info"-style UI.
                if ("delivered".equals(targetStatus)) {
                    msgRef.child("deliveredAt").setValue(ServerValue.TIMESTAMP);
                } else if ("read".equals(targetStatus) || "seen".equals(targetStatus)) {
                    msgRef.child("readAt").setValue(ServerValue.TIMESTAMP);
                }

                // delivered or read confirmed client-side → server fallback no longer needed.
                if (rank(targetStatus) >= rank("delivered")) {
                    FirebaseUtils.getDeliveryPendingRef().child(msgId).removeValue();
                }
            }
        });
    }

    /**
     * Call right after a message is written with status="sent". Drops a small
     * index entry the backend cron can scan without touching the full
     * messages tree — see index.js's delivery-fallback job.
     */
    public static void markPendingDelivery(@NonNull String chatId,
                                            @NonNull String msgId,
                                            @NonNull String toUid) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("chatId", chatId);
        entry.put("toUid", toUid);
        entry.put("ts", ServerValue.TIMESTAMP);
        FirebaseUtils.getDeliveryPendingRef().child(msgId).setValue(entry);
    }
}
