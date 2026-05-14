package com.callx.app.utils;

import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks which statuses the current user has seen.
 * Writes viewer records to Firebase even when the app is in background.
 * Thread-safe; all Firebase writes are fire-and-forget.
 *
 * FEATURE: markSeenBatch() now also writes a "status_seen" message into the
 * 1-on-1 chat between viewer and status-owner. This makes B's ChatActivity
 * with A show a special "👁 Seen your status" bubble on A's side, with A's
 * circular avatar and the time — exactly like WhatsApp story-seen receipts.
 *
 * Chat message schema (type = "status_seen"):
 *   id          — push key
 *   senderId    — viewer UID (A — who saw)
 *   senderPhoto — viewer's photoUrl (for avatar in bubble)
 *   text        — "seen your status"  (unused in UI, for search/export)
 *   type        — "status_seen"
 *   timestamp   — ServerValue.TIMESTAMP
 *   seen        — false (never shows unread badge — it's a system event)
 *   statusOwnerUid — owner's UID (B) — so both sides can filter if needed
 *
 * Dedup: before writing, we check if a status_seen message from this viewer
 * already exists in the last 24 h (limitToLast(1) ordered by timestamp).
 * If the last message is already a status_seen from the same sender within
 * 24 h, we skip the write to avoid flooding the chat.
 */
public final class StatusSeenTracker {

    private static final long DEDUP_WINDOW_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private StatusSeenTracker() {}

    /**
     * Mark a single status item as seen by the current user.
     */
    public static void markSeen(String ownerUid, String statusId) {
        if (ownerUid == null || statusId == null) return;
        String myUid = safeUid();
        if (myUid == null || myUid.equals(ownerUid)) return;
        FirebaseUtils.getStatusRef()
            .child(ownerUid)
            .child(statusId)
            .child("seenBy")
            .child(myUid)
            .setValue(ServerValue.TIMESTAMP);
    }

    /**
     * Batch-mark multiple statuses as seen (called from StatusViewerActivity.onDestroy).
     * Also writes a "status_seen" bubble into the 1-on-1 chat with the status owner.
     */
    public static void markSeenBatch(String ownerUid, Iterable<String> statusIds) {
        if (ownerUid == null || statusIds == null) return;
        String myUid = safeUid();
        if (myUid == null || myUid.equals(ownerUid)) return;

        // 1. Write seenBy records to status nodes (existing behaviour)
        Map<String, Object> updates = new HashMap<>();
        for (String id : statusIds) {
            if (id != null) {
                updates.put(ownerUid + "/" + id + "/seenBy/" + myUid,
                        ServerValue.TIMESTAMP);
            }
        }
        if (!updates.isEmpty()) {
            FirebaseUtils.getStatusRef().updateChildren(updates);
        }

        // 2. Write "status_seen" bubble into the 1-on-1 chat between viewer (myUid) and owner
        writeStatusSeenToChat(myUid, ownerUid);
    }

    /**
     * Writes a single "status_seen" system message into the 1-on-1 chat.
     * Dedup: skipped if a status_seen from same sender exists within last 24 h.
     *
     * @param viewerUid  UID of person who saw the status (A)
     * @param ownerUid   UID of the status owner (B)
     */
    private static void writeStatusSeenToChat(String viewerUid, String ownerUid) {
        // Deterministic chatId — same algo as ChatActivity / StatusViewerActivity
        String chatId = viewerUid.compareTo(ownerUid) < 0
                ? viewerUid + "_" + ownerUid
                : ownerUid + "_" + viewerUid;

        com.google.firebase.database.DatabaseReference messagesRef =
                FirebaseUtils.db()
                    .getReference("chats")
                    .child(chatId)
                    .child("messages");

        // Dedup check: look at last 1 message
        messagesRef.orderByChild("timestamp").limitToLast(1)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    long now = System.currentTimeMillis();

                    // If last message is already a status_seen from viewerUid within 24h — skip
                    for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                        String lastType   = child.child("type").getValue(String.class);
                        String lastSender = child.child("senderId").getValue(String.class);
                        Long   lastTs     = child.child("timestamp").getValue(Long.class);

                        if ("status_seen".equals(lastType)
                                && viewerUid.equals(lastSender)
                                && lastTs != null
                                && (now - lastTs) < DEDUP_WINDOW_MS) {
                            return; // already sent within 24h — skip
                        }
                    }

                    // Fetch viewer's photo for the bubble avatar
                    FirebaseUtils.db()
                        .getReference("users")
                        .child(viewerUid)
                        .addListenerForSingleValueEvent(
                            new com.google.firebase.database.ValueEventListener() {
                                @Override
                                public void onDataChange(
                                        com.google.firebase.database.DataSnapshot userSnap) {
                                    String viewerName  = userSnap.child("name").getValue(String.class);
                                    String viewerPhoto = userSnap.child("photoUrl").getValue(String.class);
                                    if (viewerPhoto == null) {
                                        viewerPhoto = userSnap.child("thumbUrl").getValue(String.class);
                                    }
                                    doWrite(messagesRef, viewerUid,
                                            viewerName  != null ? viewerName  : "Someone",
                                            viewerPhoto != null ? viewerPhoto : "",
                                            ownerUid);
                                }
                                @Override
                                public void onCancelled(
                                        com.google.firebase.database.DatabaseError e) {
                                    // Still write, just without photo
                                    doWrite(messagesRef, viewerUid, "Someone", "", ownerUid);
                                }
                            });
                }

                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError e) {
                    // Dedup check failed — write anyway (safe)
                    doWrite(messagesRef, viewerUid, "Someone", "", ownerUid);
                }
            });
    }

    /** Actually push the status_seen message node. */
    private static void doWrite(
            com.google.firebase.database.DatabaseReference messagesRef,
            String viewerUid, String viewerName, String viewerPhoto, String ownerUid) {
        String msgId = messagesRef.push().getKey();
        if (msgId == null) return;

        Map<String, Object> msg = new HashMap<>();
        msg.put("id",             msgId);
        msg.put("senderId",       viewerUid);
        msg.put("senderName",     viewerName);
        msg.put("senderPhoto",    viewerPhoto);   // used by adapter for circular avatar
        msg.put("text",           "seen your status");
        msg.put("type",           "status_seen");
        msg.put("timestamp",      ServerValue.TIMESTAMP);
        msg.put("seen",           false);         // no unread badge for system events
        msg.put("statusOwnerUid", ownerUid);

        messagesRef.child(msgId).setValue(msg);
    }

    /**
     * React to a status item with an emoji.
     *
     * @param ownerUid  UID of the status owner
     * @param statusId  ID of the specific status item
     * @param emoji     emoji string (e.g. "❤️", "😂")
     */
    public static void reactTo(String ownerUid, String statusId, String emoji) {
        if (ownerUid == null || statusId == null || emoji == null) return;
        String myUid = safeUid();
        if (myUid == null) return;
        FirebaseUtils.getStatusRef()
            .child(ownerUid)
            .child(statusId)
            .child("reactions")
            .child(myUid)
            .setValue(emoji);
    }

    /**
     * Remove the current user's reaction from a status item.
     */
    public static void removeReaction(String ownerUid, String statusId) {
        if (ownerUid == null || statusId == null) return;
        String myUid = safeUid();
        if (myUid == null) return;
        FirebaseUtils.getStatusRef()
            .child(ownerUid)
            .child(statusId)
            .child("reactions")
            .child(myUid)
            .removeValue();
    }

    /**
     * Soft-delete a status item (sets deleted = true).
     * Only the owner should call this.
     */
    public static void deleteStatus(String ownerUid, String statusId) {
        if (ownerUid == null || statusId == null) return;
        String myUid = safeUid();
        if (myUid == null || !myUid.equals(ownerUid)) return;
        FirebaseUtils.getStatusRef()
            .child(ownerUid)
            .child(statusId)
            .child("deleted")
            .setValue(true);
    }

    private static String safeUid() {
        try {
            return FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            return null;
        }
    }
}
