package com.callx.app.utils;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.HashSet;
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
    // GROUP TICK FIX v61: deliveredBy and readBy are now stamped by two
    // separate calls (ackDelivered() then, later, ackRead()) from live
    // message handling, instead of both being written in one call the
    // instant the chat screen opens. See ackRead()'s doc below for why.

    private GroupMessageStatusSync() {}

    /**
     * Called when a group member's device receives+displays a message.
     *
     * GROUP TICK FIX v61: this used to also be the call site that collapsed
     * "delivered" and "read" into the same instant whenever the chat screen
     * was open. It's kept around for callers that genuinely want both
     * stamped together (e.g. bulk-marking old history read on first open,
     * where there's no separate "delivered" moment worth showing), but
     * GroupChatActivity's real-time listener no longer calls this for live
     * incoming messages — it calls ackDelivered() immediately and ackRead()
     * separately afterwards, so the grey (delivered) and blue (read) ticks
     * are genuinely two events instead of one.
     *
     * Stamps this member's own receipt (a transaction guard makes this a
     * no-op if already stamped, so re-firing on listener re-attach never
     * overwrites the true original timestamp), then checks whether every
     * other member has now acked and, if so, advances the message's shared
     * status.
     *
     * @param groupMessagesRef  the group's messages ref (groupMessages/{groupId})
     * @param msgId             the message's push key
     * @param myUid             the current device's own uid
     * @param senderId          the message's original sender — never acks their own message
     * @param otherMemberUids   every OTHER group member who needs to ack for
     *                          the aggregate status to advance (i.e. all
     *                          current members minus senderId)
     * @param groupId           the group's id — used to re-check *live* group
     *                          membership at aggregate-check time (see
     *                          checkAggregate's doc for why this matters)
     */
    public static void ackDeliveredAndRead(@NonNull DatabaseReference groupMessagesRef,
                                            @NonNull String msgId,
                                            @NonNull String myUid,
                                            @NonNull String senderId,
                                            @NonNull Set<String> otherMemberUids,
                                            @NonNull String groupId) {
        if (myUid.equals(senderId)) return; // own message, nothing to ack

        DatabaseReference msgRef = groupMessagesRef.child(msgId);

        stampOnce(msgRef.child("deliveredBy").child(myUid),
                () -> checkAggregate(groupMessagesRef, msgRef, msgId, "deliveredBy", otherMemberUids, "delivered", groupId));
        stampOnce(msgRef.child("readBy").child(myUid),
                () -> checkAggregate(groupMessagesRef, msgRef, msgId, "readBy", otherMemberUids, "read", groupId));
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
                                     @NonNull Set<String> otherMemberUids,
                                     @NonNull String groupId) {
        if (myUid.equals(senderId)) return; // own message, nothing to ack

        DatabaseReference msgRef = groupMessagesRef.child(msgId);

        stampOnce(msgRef.child("deliveredBy").child(myUid),
                () -> checkAggregate(groupMessagesRef, msgRef, msgId, "deliveredBy", otherMemberUids, "delivered", groupId));
    }

    /**
     * GROUP TICK FIX v61: "read"-only ack — the counterpart to ackDelivered()
     * above. Stamps readBy ONLY (never re-touches deliveredBy).
     *
     * Previously ackDeliveredAndRead() stamped deliveredBy and readBy in the
     * very same call, so the instant a group chat screen was opened both
     * maps got an entry together — the aggregate "delivered" and "read"
     * checks would then both pass back-to-back on the same event loop tick,
     * and the sender's tick would jump straight from single-grey to
     * blue-double-tick. The grey-double-tick "everyone has received it, not
     * everyone has read it yet" state — the one WhatsApp always shows first —
     * never had a chance to actually render for the sender.
     *
     * Callers should now stamp delivered immediately (ackDelivered) when a
     * message is received/displayed, and call this method separately, after
     * the real "seen" moment (e.g. once the message view is actually on
     * screen, or after a short deliberate delay), so the two aggregate
     * checks land as genuinely separate events instead of collapsing into
     * the same instant.
     */
    public static void ackRead(@NonNull DatabaseReference groupMessagesRef,
                                @NonNull String msgId,
                                @NonNull String myUid,
                                @NonNull String senderId,
                                @NonNull Set<String> otherMemberUids,
                                @NonNull String groupId) {
        if (myUid.equals(senderId)) return; // own message, nothing to ack

        DatabaseReference msgRef = groupMessagesRef.child(msgId);

        stampOnce(msgRef.child("readBy").child(myUid),
                () -> checkAggregate(groupMessagesRef, msgRef, msgId, "readBy", otherMemberUids, "read", groupId));
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

    /**
     * One-shot read of the receipt map — if every other member has an entry, bump the shared status.
     *
     * GROUP TICK FIX v62: otherMemberUids is only ever a SNAPSHOT of group
     * membership taken at ack time — GroupChatActivity passes its local
     * memberNames.keySet() (a cache kept live by a members listener that only
     * ever adds uids, never removes ones that left), and CallxMessagingService
     * passes a fresh-but-still-only-once read from the same instant a push
     * arrived. Either way, that snapshot can go stale the moment a member
     * LEAVES the group afterwards without having acked yet: their uid stays
     * in otherMemberUids forever, but they can never gain a deliveredBy/readBy
     * entry once they're gone, so the `for` loop below would find them
     * "still pending" on every single check for the rest of the message's
     * life — the aggregate status would never advance to delivered/read,
     * even once everyone actually still IN the group has acked.
     *
     * Fix: re-read the group's *live* membership right here, at check time,
     * and only require an ack from a uid that is BOTH in the originally
     * requested otherMemberUids AND still currently a member. A uid that has
     * left no longer blocks the aggregate — matching "everyone who's still
     * here has acknowledged it", which is the only thing that's actually
     * checkable (and the only thing that matters) once someone's left.
     */
    private static void checkAggregate(DatabaseReference groupMessagesRef, DatabaseReference msgRef,
                                        String msgId, String childName,
                                        Set<String> otherMemberUids, String targetStatus,
                                        String groupId) {
        if (otherMemberUids.isEmpty()) return;
        FirebaseUtils.getGroupMembersRef(groupId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot membersSnapshot) {
                Set<String> stillRequired = new HashSet<>();
                for (String uid : otherMemberUids) {
                    if (membersSnapshot.hasChild(uid)) stillRequired.add(uid);
                }
                if (stillRequired.isEmpty()) {
                    // Everyone we were originally waiting on has since left
                    // the group — vacuously "everyone remaining has acked",
                    // so advance instead of blocking forever on ghosts.
                    MessageStatusSync.upgradeStatus(groupMessagesRef, msgId, targetStatus);
                    return;
                }

                msgRef.child(childName).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        for (String uid : stillRequired) {
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

            @Override
            public void onCancelled(@NonNull DatabaseError error) { /* retried on next ack anyway */ }
        });
    }
}
