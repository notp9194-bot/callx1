package com.callx.app.utils;

import com.callx.app.feed.ReelPlayerFragment;

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
 * (tappable → opens reel), aur time. Sirf B (owner) ko dikhta hai — A (viewer)
 * ko kabhi nahi, even though same Firebase node dono load karte hain. See
 * MessageAdapter / MessagePagingAdapter getItemViewType() — wahan
 * reelOwnerUid se decide hota hai kis user ko bubble render karna hai.
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
 *   reelOwnerUid  — owner UID (B) — used by chat adapters to gate visibility
 *
 * GATING (IMPORTANT — read before changing):
 *   We only write a bubble if the viewer (A) and the owner (B) already have
 *   an existing contact/chat relationship — i.e. contacts/{A}/{B} exists.
 *   Without this gate, casually scrolling through reels from creators you've
 *   never messaged would silently create a brand-new phantom chat thread in
 *   Firebase for every single reel watched, plus 3 Firebase calls per view
 *   (dedup read + profile read + write) — pure spam, since that chat would
 *   never even surface in the viewer's or owner's inbox (ChatsFragment reads
 *   from contacts/{uid}/{partnerUid}, which ReelSeenTracker never touches).
 *
 *   We check contacts/{A}/{B} (the VIEWER's own subtree) rather than
 *   contacts/{B}/{A} (the owner's) because it's always readable — it's the
 *   logged-in user's own data — regardless of how Firebase rules restrict
 *   reads of other users' contact lists. Contacts are written bidirectionally
 *   by ChatMessageSender on first real message, so checking either side's
 *   copy is equivalent.
 *
 * DEDUP (IMPORTANT — read before changing):
 *   Old approach scanned the last 5 chat messages with an orderByChild query
 *   on every single reel view — expensive and pointless extra read. Now uses
 *   one direct key: reelSeenDedup/{viewerUid}/{reelId} → last-bubbled-at ms.
 *   Window: 1 hour per reelId per viewer — prevents flooding if user
 *   rewinds/re-opens the same reel. Unlike status_seen (24h global dedup),
 *   reel dedup is per-reel so each reel the viewer watches shows its own
 *   bubble.
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

        final String finalThumb = reelThumbUrl != null ? reelThumbUrl : "";

        // GATE: only bubble if we already have a chat relationship with the
        // owner. Reading our OWN contacts subtree — cheap, always allowed,
        // and equivalent to checking the owner's copy (written both ways).
        FirebaseUtils.getContactsRef(myUid).child(ownerUid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot contactSnap) {
                    if (!contactSnap.exists()) return; // never chatted — skip, no writes at all
                    checkDedupAndWrite(myUid, ownerUid, reelId, finalThumb);
                }

                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError e) {
                    // Can't confirm the relationship — fail closed (skip) so a
                    // transient read error never turns into phantom-thread spam.
                }
            });
    }

    /**
     * Cheap dedup via a single direct key read — no ordered query / scan.
     * Path: reelSeenDedup/{viewerUid}/{reelId} → last-bubbled-at (client ms).
     */
    private static void checkDedupAndWrite(
            String myUid, String ownerUid, String reelId, String finalThumb) {

        com.google.firebase.database.DatabaseReference dedupRef =
                FirebaseUtils.db()
                    .getReference("reelSeenDedup")
                    .child(myUid)
                    .child(reelId);

        dedupRef.addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                long now = System.currentTimeMillis();
                Long lastTs = snap.getValue(Long.class);
                if (lastTs != null && (now - lastTs) < DEDUP_WINDOW_MS) {
                    return; // same reel already bubbled within 1h — skip
                }
                dedupRef.setValue(now);

                // Deterministic chatId — same algo as ChatActivity
                String chatId = myUid.compareTo(ownerUid) < 0
                        ? myUid + "_" + ownerUid
                        : ownerUid + "_" + myUid;

                // Path MUST match ChatActivity's messagesRef: "messages/{chatId}"
                com.google.firebase.database.DatabaseReference messagesRef =
                        FirebaseUtils.db()
                            .getReference("messages")
                            .child(chatId);

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
                // Dedup check failed — write anyway (safe, user sees bubble;
                // worst case is one extra bubble, not a phantom thread since
                // the contacts gate above already confirmed a real relationship).
                String chatId = myUid.compareTo(ownerUid) < 0
                        ? myUid + "_" + ownerUid
                        : ownerUid + "_" + myUid;
                com.google.firebase.database.DatabaseReference messagesRef =
                        FirebaseUtils.db().getReference("messages").child(chatId);
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
