package com.callx.app.utils;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.Set;

/**
 * GROUP TICK SYSTEM: group-chat analogue of MessageStatusSync.
 *
 * A 1:1 chat has exactly one recipient, so its shared `status` field
 * (sent/delivered/read) IS that recipient's status. A group has many
 * recipients, so each member's own ack is first stamped individually —
 * Message#deliveredBy / Message#readBy, uid → server timestamp — and only
 * once EVERY OTHER member has acked does this class flip the message's
 * shared `status` field forward, via MessageStatusSync's existing
 * rank-safe transaction (sent < delivered < read, never downgrades).
 *
 * This is exactly what the existing tick-drawing code already understands:
 * MessageBubbleCanvasView#drawTick reads "delivered"/"read".equals(m.status),
 * same as it does for 1:1 — so group grey-double-tick means "everyone has
 * received it" and blue-double-tick means "everyone has read it", matching
 * WhatsApp's group tick semantics. The per-member maps are additionally kept
 * around so the group Message Info dialog can show who has/hasn't seen it.
 */
public final class GroupMessageStatusSync {

    private GroupMessageStatusSync() {}

    /**
     * Called when a group member's device receives+displays a message (the
     * group chat screen being open collapses "delivered" and "read" into the
     * same instant, same simplification ChatActivity's 1:1 markRead() already
     * makes when the chat is open). Stamps this member's own receipt (a
     * transaction guard makes this a no-op if already stamped, so re-firing
     * on listener re-attach never overwrites the true original timestamp),
     * then checks whether every other member has now acked and, if so,
     * advances the message's shared status.
     *
     * @param groupMessagesRef  the group's messages ref (groupMessages/{groupId})
     * @param msgId             the message's push key
     * @param myUid             the current device's own uid
     * @param senderId          the message's original sender — never acks their own message
     * @param otherMemberUids   every OTHER group member who needs to ack for
     *                          the aggregate status to advance (i.e. all
     *                          current members minus senderId)
     */
    public static void ackDeliveredAndRead(@NonNull DatabaseReference groupMessagesRef,
                                            @NonNull String msgId,
                                            @NonNull String myUid,
                                            @NonNull String senderId,
                                            @NonNull Set<String> otherMemberUids) {
        if (myUid.equals(senderId)) return; // own message, nothing to ack

        DatabaseReference msgRef = groupMessagesRef.child(msgId);

        stampOnce(msgRef.child("deliveredBy").child(myUid),
                () -> checkAggregate(groupMessagesRef, msgRef, msgId, "deliveredBy", otherMemberUids, "delivered"));
        stampOnce(msgRef.child("readBy").child(myUid),
                () -> checkAggregate(groupMessagesRef, msgRef, msgId, "readBy", otherMemberUids, "read"));
    }

    /**
     * GROUP TICK FIX v60: "delivered"-only ack — group's analogue of 1:1's
     * FCM handler unconditionally calling MessageStatusSync.upgradeStatus(
     * ...,"delivered") the instant a push is received, chat open ho ya na ho.
     *
     * ackDeliveredAndRead() above was ONLY ever called from GroupChatActivity's
     * realtime listener, i.e. only when a member actually opens the group chat.
     * A member who sees the notification and never opens the chat therefore
     * never got a deliveredBy entry — sender's grey tick for that member
     * stayed silent forever, not just delayed.
     *
     * This overload stamps deliveredBy ONLY (never readBy) so it's safe to
     * call from CallxMessagingService's background FCM handler — receiving
     * a push means "delivered", not "read". GroupChatActivity's markRead()
     * still separately stamps readBy (and re-stamps deliveredBy, a no-op
     * thanks to stampOnce's transaction guard) once the chat is actually opened.
     */
    public static void ackDelivered(@NonNull DatabaseReference groupMessagesRef,
                                     @NonNull String msgId,
                                     @NonNull String myUid,
                                     @NonNull String senderId,
                                     @NonNull Set<String> otherMemberUids) {
        if (myUid.equals(senderId)) return; // own message, nothing to ack

        DatabaseReference msgRef = groupMessagesRef.child(msgId);

        stampOnce(msgRef.child("deliveredBy").child(myUid),
                () -> checkAggregate(groupMessagesRef, msgRef, msgId, "deliveredBy", otherMemberUids, "delivered"));
    }

    private interface OnStamped { void run(); }

    /** Writes ServerValue.TIMESTAMP only if this uid's receipt isn't already there. */
    private static void stampOnce(DatabaseReference receiptRef, OnStamped onStamped) {
        receiptRef.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                if (currentData.getValue() != null) return Transaction.success(currentData); // already stamped
                currentData.setValue(ServerValue.TIMESTAMP);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot snapshot) {
                // Whether we just stamped it or it was already there from an
                // earlier call, our own ack now exists either way — safe to
                // check the aggregate every time.
                if (error == null) onStamped.run();
            }
        });
    }

    /** One-shot read of the receipt map — if every other member has an entry, bump the shared status. */
    private static void checkAggregate(DatabaseReference groupMessagesRef, DatabaseReference msgRef,
                                        String msgId, String childName,
                                        Set<String> otherMemberUids, String targetStatus) {
        if (otherMemberUids.isEmpty()) return;
        msgRef.child(childName).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (String uid : otherMemberUids) {
                    if (!snapshot.hasChild(uid)) return; // someone still pending — not yet
                }
                // Reuses MessageStatusSync's rank-safe transaction (sent <
                // delivered < read, never downgrades) — same one 1:1 uses.
                MessageStatusSync.upgradeStatus(groupMessagesRef, msgId, targetStatus);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { /* retried on next ack anyway */ }
        });
    }
}
