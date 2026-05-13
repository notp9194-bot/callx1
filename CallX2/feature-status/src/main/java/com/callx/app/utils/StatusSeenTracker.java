package com.callx.app.utils;

import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks which statuses the current user has seen.
 * Writes viewer records to Firebase even when the app is in background.
 * Thread-safe; all Firebase writes are fire-and-forget.
 */
public final class StatusSeenTracker {

    private StatusSeenTracker() {}

    /**
     * Mark a single status item as seen by the current user.
     *
     * @param ownerUid  UID of the status owner
     * @param statusId  ID of the specific status item
     */
    public static void markSeen(String ownerUid, String statusId) {
        if (ownerUid == null || statusId == null) return;
        String myUid = safeUid();
        if (myUid == null || myUid.equals(ownerUid)) return; // don't track own views
        FirebaseUtils.getStatusRef()
            .child(ownerUid)
            .child(statusId)
            .child("seenBy")
            .child(myUid)
            .setValue(ServerValue.TIMESTAMP);
    }

    /**
     * Batch-mark multiple statuses as seen (e.g. after exiting StatusViewerActivity).
     *
     * @param ownerUid  UID of the status owner
     * @param statusIds list of status IDs that were displayed
     */
    public static void markSeenBatch(String ownerUid, Iterable<String> statusIds) {
        if (ownerUid == null || statusIds == null) return;
        String myUid = safeUid();
        if (myUid == null || myUid.equals(ownerUid)) return;

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
