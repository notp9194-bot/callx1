package com.callx.app.utils;

import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;

/**
 * StatusSeenTracker v25 — Comprehensive seen/reaction tracking.
 * - markSeen / markSeenBatch: seenBy records + chat bubble (deduped 24h)
 * - reactTo / removeReaction: with UI toggle support
 * - recordViewDuration: analytics support
 * - deleteStatus: soft-delete (owner only)
 * - forwardStatus: increment forwardCount
 */
public final class StatusSeenTracker {

    private static final long DEDUP_WINDOW_MS = 24 * 60 * 60 * 1000L;

    private StatusSeenTracker() {}

    // ── Seen tracking ─────────────────────────────────────────────────────

    public static void markSeen(String ownerUid, String statusId) {
        if (ownerUid == null || statusId == null) return;
        String myUid = safeUid();
        if (myUid == null || myUid.equals(ownerUid)) return;
        FirebaseUtils.getStatusRef()
            .child(ownerUid).child(statusId).child("seenBy").child(myUid)
            .setValue(ServerValue.TIMESTAMP);
        FirebaseUtils.db().getReference("statusSeen")
            .child(myUid).child(ownerUid).child(statusId)
            .setValue(ServerValue.TIMESTAMP);
    }

    public static void markSeenBatch(String ownerUid, Iterable<String> statusIds,
                                     String ownerName, String statusThumbUrl) {
        if (ownerUid == null || statusIds == null) return;
        String myUid = safeUid();
        if (myUid == null || myUid.equals(ownerUid)) return;

        Map<String, Object> updates = new HashMap<>();
        for (String id : statusIds) {
            if (id != null) {
                updates.put(ownerUid + "/" + id + "/seenBy/" + myUid, ServerValue.TIMESTAMP);
            }
        }
        if (!updates.isEmpty()) FirebaseUtils.getStatusRef().updateChildren(updates);

        String safeOwnerName = ownerName != null ? ownerName : "";
        String safeThumb     = statusThumbUrl != null ? statusThumbUrl : "";
        writeStatusSeenToChat(myUid, ownerUid, safeOwnerName, safeThumb);
    }

    public static void markSeenBatch(String ownerUid, Iterable<String> statusIds) {
        markSeenBatch(ownerUid, statusIds, "", "");
    }

    // ── View duration (analytics) ─────────────────────────────────────────

    public static void recordViewDuration(String ownerUid, String statusId, long durationMs) {
        if (ownerUid == null || statusId == null || durationMs < 200) return;
        String myUid = safeUid();
        if (myUid == null || myUid.equals(ownerUid)) return;
        FirebaseUtils.getStatusRef()
            .child(ownerUid).child(statusId).child("viewDurations").child(myUid)
            .setValue(durationMs);
    }

    // ── Reactions ─────────────────────────────────────────────────────────

    /**
     * React to a status — if same emoji already set, removes it (toggle).
     * If different emoji, replaces it. Returns the new emoji or null if removed.
     */
    public static void reactTo(String ownerUid, String statusId, String emoji,
                                String currentReaction, OnReactionCallback cb) {
        if (ownerUid == null || statusId == null || emoji == null) return;
        String myUid = safeUid();
        if (myUid == null) return;

        com.google.firebase.database.DatabaseReference ref = FirebaseUtils.getStatusRef()
            .child(ownerUid).child(statusId).child("reactions").child(myUid);

        if (emoji.equals(currentReaction)) {
            // Toggle off — remove reaction
            ref.removeValue().addOnSuccessListener(u -> { if (cb != null) cb.onReaction(null); });
        } else {
            // Set new reaction
            ref.setValue(emoji).addOnSuccessListener(u -> { if (cb != null) cb.onReaction(emoji); });
            // Notify owner (fire-and-forget)
            notifyReaction(ownerUid, statusId, emoji, myUid);
        }
    }

    public static void reactTo(String ownerUid, String statusId, String emoji) {
        reactTo(ownerUid, statusId, emoji, null, null);
    }

    public static void removeReaction(String ownerUid, String statusId) {
        if (ownerUid == null || statusId == null) return;
        String myUid = safeUid();
        if (myUid == null) return;
        FirebaseUtils.getStatusRef()
            .child(ownerUid).child(statusId).child("reactions").child(myUid).removeValue();
    }

    public interface OnReactionCallback {
        void onReaction(String newEmoji); // null = removed
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────

    public static void deleteStatus(String ownerUid, String statusId) {
        if (ownerUid == null || statusId == null) return;
        String myUid = safeUid();
        if (myUid == null || !myUid.equals(ownerUid)) return;
        FirebaseUtils.getStatusRef()
            .child(ownerUid).child(statusId).child("deleted").setValue(true);
    }

    // ── Forward tracking ──────────────────────────────────────────────────

    public static void incrementForwardCount(String ownerUid, String statusId) {
        if (ownerUid == null || statusId == null) return;
        FirebaseUtils.getStatusRef()
            .child(ownerUid).child(statusId)
            .child("forwardCount")
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @Override
                public com.google.firebase.database.Transaction.Result doTransaction(
                        com.google.firebase.database.MutableData data) {
                    Long cur = data.getValue(Long.class);
                    data.setValue(cur == null ? 1L : cur + 1);
                    return com.google.firebase.database.Transaction.success(data);
                }
                @Override
                public void onComplete(com.google.firebase.database.DatabaseError e,
                                       boolean committed, com.google.firebase.database.DataSnapshot s) {}
            });
    }

    // ── Internal: chat bubble ─────────────────────────────────────────────

    private static void writeStatusSeenToChat(String viewerUid, String ownerUid,
                                               String ownerName, String statusThumbUrl) {
        String chatId = viewerUid.compareTo(ownerUid) < 0
                ? viewerUid + "_" + ownerUid : ownerUid + "_" + viewerUid;

        com.google.firebase.database.DatabaseReference messagesRef =
                FirebaseUtils.db().getReference("messages").child(chatId);

        messagesRef.orderByChild("timestamp").limitToLast(1)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    long now = System.currentTimeMillis();
                    for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                        String lastType   = child.child("type").getValue(String.class);
                        String lastSender = child.child("senderId").getValue(String.class);
                        Long   lastTs     = child.child("timestamp").getValue(Long.class);
                        if ("status_seen".equals(lastType) && viewerUid.equals(lastSender)
                                && lastTs != null && (now - lastTs) < DEDUP_WINDOW_MS) return;
                    }
                    FirebaseUtils.db().getReference("users").child(viewerUid)
                        .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                            @Override
                            public void onDataChange(com.google.firebase.database.DataSnapshot u) {
                                String vName  = u.child("name").getValue(String.class);
                                String vPhoto = u.child("photoUrl").getValue(String.class);
                                if (vPhoto == null) vPhoto = u.child("thumbUrl").getValue(String.class);
                                doWriteSeenBubble(messagesRef, viewerUid,
                                        vName  != null ? vName  : "Someone",
                                        vPhoto != null ? vPhoto : "",
                                        ownerUid, ownerName, statusThumbUrl);
                            }
                            @Override
                            public void onCancelled(com.google.firebase.database.DatabaseError e) {
                                doWriteSeenBubble(messagesRef, viewerUid, "Someone", "",
                                        ownerUid, ownerName, statusThumbUrl);
                            }
                        });
                }
                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError e) {
                    doWriteSeenBubble(messagesRef, viewerUid, "Someone", "",
                            ownerUid, ownerName, statusThumbUrl);
                }
            });
    }

    private static void doWriteSeenBubble(
            com.google.firebase.database.DatabaseReference ref,
            String viewerUid, String viewerName, String viewerPhoto,
            String ownerUid, String ownerName, String statusThumbUrl) {
        String msgId = ref.push().getKey();
        if (msgId == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("id",              msgId);
        msg.put("senderId",        viewerUid);
        msg.put("senderName",      viewerName);
        msg.put("senderPhoto",     viewerPhoto);
        msg.put("text",            "seen your status");
        msg.put("type",            "status_seen");
        msg.put("timestamp",       ServerValue.TIMESTAMP);
        msg.put("seen",            false);
        msg.put("statusOwnerUid",  ownerUid);
        msg.put("statusOwnerName", ownerName);
        msg.put("statusThumbUrl",  statusThumbUrl);
        ref.child(msgId).setValue(msg);
    }

    private static void notifyReaction(String ownerUid, String statusId,
                                        String emoji, String reactorUid) {
        Map<String, Object> n = new HashMap<>();
        n.put("type",      "status_reaction");
        n.put("fromUid",   reactorUid);
        n.put("emoji",     emoji);
        n.put("statusId",  statusId);
        n.put("timestamp", ServerValue.TIMESTAMP);
        FirebaseUtils.db().getReference("notifications")
            .child(ownerUid).push().setValue(n);
    }

    private static String safeUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }
}
