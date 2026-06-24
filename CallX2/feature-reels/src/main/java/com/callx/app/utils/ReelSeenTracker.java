package com.callx.app.utils;

import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;

/**
 * ReelSeenTracker — v21 (out-of-messages-tree edition)
 *
 * ARCHITECTURE CHANGE (v21):
 *   reel_seen events are NO LONGER written into messages/{chatId}.
 *   They now live in a dedicated side-tree:
 *
 *     reelSeenEvents/{ownerUid}/{viewerUid}/{eventId}
 *       senderId      — viewer UID
 *       senderName    — viewer display name
 *       senderPhoto   — viewer photo URL
 *       reelId        — reel's Firebase key
 *       reelThumbUrl  — reel thumbnail URL
 *       timestamp     — ServerValue.TIMESTAMP
 *
 *   WHY:
 *     • Keeps messages/{chatId} clean — zero reel_seen rows polluting
 *       warm-cache / limitToLast(30) / pagination slots.
 *     • DB never grows from reel_seen writes.
 *     • Room / sync listeners are completely untouched.
 *     • Viewer side gets no 0×0 RecyclerView bind at all.
 *
 *   DISPLAY:
 *     ChatActivity / ChatReelSeenController listens to
 *     reelSeenEvents/{myUid}/{partnerUid} (ordered by timestamp, limitToLast(20))
 *     and injects synthetic Message objects (type="reel_seen") into
 *     MessagePagingAdapter at display-time only — no DB write, no Room row.
 *
 * GATING:
 *   Same as before — only write if contacts/{viewerUid}/{ownerUid} exists
 *   (i.e. an actual chat relationship). Prevents phantom spam from scrolling
 *   through strangers' reels.
 *
 * DEDUP:
 *   reelSeenDedup/{viewerUid}/{reelId} → last-bubbled-at ms.
 *   Window: 1 hour per reelId per viewer.
 */
public final class ReelSeenTracker {

    /** Dedup window: 1 hour per reelId per viewer. */
    private static final long DEDUP_WINDOW_MS = 60 * 60 * 1000L;

    private ReelSeenTracker() {}

    /**
     * Call after a reel view is recorded (from ReelPlayerFragment.recordView).
     *
     * @param ownerUid     UID of the reel owner
     * @param reelId       Firebase key of the reel
     * @param reelThumbUrl Thumbnail URL (shown in bubble)
     */
    public static void writeReelSeenToChat(
            String ownerUid, String reelId, String reelThumbUrl) {
        if (ownerUid == null || reelId == null) return;
        String myUid = safeUid();
        if (myUid == null || myUid.equals(ownerUid)) return;

        final String finalThumb = reelThumbUrl != null ? reelThumbUrl : "";

        // GATE: confirm existing chat relationship via our own contacts subtree.
        FirebaseUtils.getContactsRef(myUid).child(ownerUid)
            .addListenerForSingleValueEvent(
                new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(
                            com.google.firebase.database.DataSnapshot snap) {
                        if (!snap.exists()) return; // no relationship — skip
                        checkDedupAndWrite(myUid, ownerUid, reelId, finalThumb);
                    }
                    @Override
                    public void onCancelled(
                            com.google.firebase.database.DatabaseError e) {
                        // Fail closed — no write on uncertainty.
                    }
                });
    }

    // ── Dedup ───────────────────────────────────────────────────────────────

    private static void checkDedupAndWrite(
            String myUid, String ownerUid, String reelId, String finalThumb) {

        com.google.firebase.database.DatabaseReference dedupRef =
                FirebaseUtils.db()
                    .getReference("reelSeenDedup")
                    .child(myUid)
                    .child(reelId);

        dedupRef.addListenerForSingleValueEvent(
            new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(
                        com.google.firebase.database.DataSnapshot snap) {
                    long now    = System.currentTimeMillis();
                    Long lastTs = snap.getValue(Long.class);
                    if (lastTs != null && (now - lastTs) < DEDUP_WINDOW_MS) return;
                    dedupRef.setValue(now);
                    fetchViewerAndWrite(myUid, ownerUid, reelId, finalThumb);
                }
                @Override
                public void onCancelled(
                        com.google.firebase.database.DatabaseError e) {
                    // Dedup failed — write anyway (at most one extra bubble;
                    // contact gate already confirmed a real relationship).
                    fetchViewerAndWrite(myUid, ownerUid, reelId, finalThumb);
                }
            });
    }

    // ── Fetch viewer profile → write ────────────────────────────────────────

    private static void fetchViewerAndWrite(
            String myUid, String ownerUid, String reelId, String finalThumb) {

        FirebaseUtils.db()
            .getReference("users")
            .child(myUid)
            .addListenerForSingleValueEvent(
                new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(
                            com.google.firebase.database.DataSnapshot userSnap) {
                        String name  = userSnap.child("name").getValue(String.class);
                        String photo = userSnap.child("photoUrl").getValue(String.class);
                        if (photo == null)
                            photo = userSnap.child("thumbUrl").getValue(String.class);
                        doWrite(myUid,
                                name  != null ? name  : "Someone",
                                photo != null ? photo : "",
                                ownerUid, reelId, finalThumb);
                    }
                    @Override
                    public void onCancelled(
                            com.google.firebase.database.DatabaseError e) {
                        doWrite(myUid, "Someone", "", ownerUid, reelId, finalThumb);
                    }
                });
    }

    // ── Write to reelSeenEvents side-tree ───────────────────────────────────

    /**
     * Writes to reelSeenEvents/{ownerUid}/{viewerUid}/{eventId}.
     * The messages/{chatId} tree is never touched.
     */
    private static void doWrite(
            String viewerUid, String viewerName, String viewerPhoto,
            String ownerUid,  String reelId,     String reelThumbUrl) {

        com.google.firebase.database.DatabaseReference eventsRef =
                FirebaseUtils.db()
                    .getReference("reelSeenEvents")
                    .child(ownerUid)
                    .child(viewerUid);

        String eventId = eventsRef.push().getKey();
        if (eventId == null) return;

        Map<String, Object> event = new HashMap<>();
        event.put("senderId",     viewerUid);
        event.put("senderName",   viewerName);
        event.put("senderPhoto",  viewerPhoto);
        event.put("reelId",       reelId);
        event.put("reelThumbUrl", reelThumbUrl);
        event.put("timestamp",    ServerValue.TIMESTAMP);

        eventsRef.child(eventId).setValue(event);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String safeUid() {
        try { return FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { return null; }
    }
}
