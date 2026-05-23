package com.callx.app.utils;

import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;

/**
 * ReelSeenTracker
 *
 * Jab koi viewer (A) kisi owner (B) ka reel dekhta hai, ek "reel_seen" message
 * B ke saath A ke 1-on-1 chat mein likha jaata hai.
 *
 * Chat screen par B ko ek special "🎬 Watched your reel" bubble dikhta hai —
 * bilkul status_seen bubble jaisa — with A ka circular avatar, reel thumbnail
 * (tappable → opens reel), aur time.
 *
 * Firebase path: messages/{chatId}/{msgId}
 *   (same node jo ChatActivity sun raha hai — "messages/{chatId}")
 *
 * Chat message schema (type = "reel_seen"):
 *   id            — push key
 *   senderId      — viewer UID (A — who watched)
 *   senderName    — viewer display name
 *   senderPhoto   — viewer photoUrl (for circular avatar in bubble)
 *   text          — "watched your reel"  (for search/export)
 *   type          — "reel_seen"
 *   reelId        — reel's Firebase ID (for tap-to-open)
 *   reelThumbUrl  — reel thumbnail URL (shown in bubble)
 *   timestamp     — ServerValue.TIMESTAMP
 *   seen          — false  (no unread badge — system event)
 *   reelOwnerUid  — owner UID (B) — both sides can filter if needed
 *
 * Dedup: within 1 hour per reelId — prevents flooding if user rewinds/re-opens.
 * Unlike status_seen (24h global dedup), reel dedup is per-reel so each reel
 * the viewer watches shows its own bubble.
 */
public final class ReelSeenTracker {

    /** Dedup window: 1 hour per reelId per viewer. */
    private static final long DEDUP_WINDOW_MS = 60 * 60 * 1000L;

    private ReelSeenTracker() {}

    /**
     * Call this after a reel view is recorded (i.e. from ReelPlayerFragment.recordView).
     *
     * @param ownerUid     UID of the reel owner (B)
     * @param reelId       Firebase key of the reel
     * @param reelThumbUrl Thumbnail URL of the reel (shown in bubble)
     */
    public static void writeReelSeenToChat(String ownerUid, String reelId, String reelThumbUrl) {
        if (ownerUid == null || reelId == null) return;
        String myUid = safeUid();
        if (myUid == null || myUid.equals(ownerUid)) return; // don't bubble own views

        // Deterministic chatId — same algo as ChatActivity
        String chatId = myUid.compareTo(ownerUid) < 0
                ? myUid + "_" + ownerUid
                : ownerUid + "_" + myUid;

        // Path MUST match ChatActivity's messagesRef: "messages/{chatId}"
        com.google.firebase.database.DatabaseReference messagesRef =
                FirebaseUtils.db()
                    .getReference("messages")
                    .child(chatId);

        final String finalThumb = reelThumbUrl != null ? reelThumbUrl : "";

        // Dedup check: find last reel_seen from this viewer for this specific reelId
        messagesRef.orderByChild("timestamp").limitToLast(5)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    long now = System.currentTimeMillis();

                    for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                        String lastType   = child.child("type").getValue(String.class);
                        String lastSender = child.child("senderId").getValue(String.class);
                        String lastReel   = child.child("reelId").getValue(String.class);
                        Long   lastTs     = child.child("timestamp").getValue(Long.class);

                        if ("reel_seen".equals(lastType)
                                && myUid.equals(lastSender)
                                && reelId.equals(lastReel)
                                && lastTs != null
                                && (now - lastTs) < DEDUP_WINDOW_MS) {
                            return; // same reel already seen within 1h — skip
                        }
                    }

                    // Fetch viewer's name + photo, then write
                    FirebaseUtils.db()
                        .getReference("users")
                        .child(myUid)
                        .addListenerForSingleValueEvent(
                            new com.google.firebase.database.ValueEventListener() {
                                @Override
                                public void onDataChange(
                                        com.google.firebase.database.DataSnapshot userSnap) {
                                    String viewerName  = userSnap.child("name").getValue(String.class);
                                    String viewerPhoto = userSnap.child("photoUrl").getValue(String.class);
                                    if (viewerPhoto == null)
                                        viewerPhoto = userSnap.child("thumbUrl").getValue(String.class);
                                    doWrite(messagesRef, myUid,
                                            viewerName  != null ? viewerName  : "Someone",
                                            viewerPhoto != null ? viewerPhoto : "",
                                            ownerUid, reelId, finalThumb);
                                }
                                @Override
                                public void onCancelled(
                                        com.google.firebase.database.DatabaseError e) {
                                    doWrite(messagesRef, myUid, "Someone", "",
                                            ownerUid, reelId, finalThumb);
                                }
                            });
                }

                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError e) {
                    // Dedup check failed — write anyway (safe, user sees bubble)
                    doWrite(messagesRef, myUid, "Someone", "", ownerUid, reelId, finalThumb);
                }
            });
    }

    /** Push the reel_seen message node. */
    private static void doWrite(
            com.google.firebase.database.DatabaseReference messagesRef,
            String viewerUid, String viewerName, String viewerPhoto,
            String ownerUid, String reelId, String reelThumbUrl) {

        String msgId = messagesRef.push().getKey();
        if (msgId == null) return;

        Map<String, Object> msg = new HashMap<>();
        msg.put("id",           msgId);
        msg.put("senderId",     viewerUid);
        msg.put("senderName",   viewerName);
        msg.put("senderPhoto",  viewerPhoto);   // circular avatar in bubble
        msg.put("text",         "watched your reel");
        msg.put("type",         "reel_seen");
        msg.put("reelId",       reelId);        // tap-to-open
        msg.put("reelThumbUrl", reelThumbUrl);  // thumbnail shown in bubble
        msg.put("timestamp",    ServerValue.TIMESTAMP);
        msg.put("seen",         false);         // no unread badge
        msg.put("reelOwnerUid", ownerUid);

        messagesRef.child(msgId).setValue(msg);
    }

    private static String safeUid() {
        try {
            return FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            return null;
        }
    }
}
