package com.callx.app.utils;

import com.callx.app.feed.ReelPlayerFragment;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ReelSeenTracker
 *
 * Jab koi viewer (A) kisi owner (B) ka reel dekhta hai, B ke saath A ke
 * 1-on-1 chat mein ek "🎬 Watched your reel" bubble dikhta hai — bilkul
 * status_seen bubble jaisa.
 *
 * BATCHING (IMPORTANT — read before changing):
 *   Pehle har ek reel view apna khud ka chat row + 3-4 Firebase calls
 *   (contact check + dedup + profile read + msg write) create karta tha.
 *   Ek user 40 reels dekh le to 40 alag bubbles + 40x Firebase writes ban
 *   jaate the — chat spam + unnecessary cost.
 *
 *   Ab ek WINDOW_MS (15 min) session-window ke andar sab reel views EK
 *   single combined bubble mein collect hote hain:
 *     "Watched your reel"          → count == 1
 *     "Watched 5 of your reels"    → count  > 1
 *
 *   Window state ek single node par rakha jaata hai:
 *     reelSeenBatch/{chatId} = { messageId, windowStart, count,
 *                                 lastReelId, lastReelThumb,
 *                                 ownerUid, viewerUid }
 *
 *   Naya reel dekhne par:
 *     - window abhi bhi open (< 15 min purana)  → us ek chat-message row
 *       ko UPDATE karte hain (text/thumb/timestamp), koi naya row nahi.
 *     - window expire ho chuka / pehli baar     → naya chat-message row
 *       banate hain aur naya window shuru karte hain.
 *
 *   Count/window mutation ek Firebase runTransaction() ke through hota hai
 *   — isse fast reel-swiping (back-to-back views within ms) mein bhi count
 *   race-safe rehta hai (do parallel views ek dusre ka increment overwrite
 *   nahi karte).
 *
 * Firebase path: messages/{chatId}/{msgId}
 *   (same node jo ChatActivity sun raha hai — "messages/{chatId}")
 *
 * Chat message schema (type = "reel_seen"):
 *   id            — push key
 *   senderId      — viewer UID (A — who watched)
 *   senderName    — viewer display name
 *   senderPhoto   — viewer photoUrl (for circular avatar in bubble)
 *   text          — "Watched your reel" / "Watched N of your reels"
 *                    (drives the bubble label directly — see
 *                    MessagePagingAdapter's seenLabelOverride wiring)
 *   type          — "reel_seen"
 *   reelId        — MOST RECENT reel's Firebase ID (for tap-to-open)
 *   reelThumbUrl  — MOST RECENT reel's thumbnail URL (shown in bubble)
 *   timestamp     — ServerValue.TIMESTAMP (bumped on every view in-window,
 *                    so the bubble surfaces to "now" instead of staying
 *                    stuck at the first view's time)
 *   seen          — false  (no unread badge — system event)
 *   reelOwnerUid  — owner UID (B) — used by chat adapters to gate visibility
 *
 * GATING (IMPORTANT — read before changing):
 *   We only write a bubble if the viewer (A) and the owner (B) already have
 *   an existing contact/chat relationship — i.e. contacts/{A}/{B} exists.
 *   Without this gate, casually scrolling through reels from creators you've
 *   never messaged would silently create a brand-new phantom chat thread in
 *   Firebase for every single reel watched — pure spam, since that chat
 *   would never even surface in the viewer's or owner's inbox (ChatsFragment
 *   reads from contacts/{uid}/{partnerUid}, which ReelSeenTracker never
 *   touches).
 *
 *   We check contacts/{A}/{B} (the VIEWER's own subtree) rather than
 *   contacts/{B}/{A} (the owner's) because it's always readable — it's the
 *   logged-in user's own data — regardless of how Firebase rules restrict
 *   reads of other users' contact lists. Contacts are written bidirectionally
 *   by ChatMessageSender on first real message, so checking either side's
 *   copy is equivalent.
 *
 * PER-REEL DEDUP (IMPORTANT — read before changing):
 *   Still uses one direct key: reelSeenDedup/{viewerUid}/{reelId} →
 *   last-bubbled-at ms. Window: 1 hour per reelId per viewer — prevents the
 *   SAME reel (rewound/re-opened) from bumping the batch count repeatedly.
 *   This is separate from and runs BEFORE the 15-min batch window above —
 *   it gates "is this a genuinely new reel view" while the batch window
 *   gates "does this need its own bubble or fold into the existing one".
 */
public final class ReelSeenTracker {

    /** Per-reel dedup: same reel rewound within 1h doesn't re-count. */
    private static final long DEDUP_WINDOW_MS = 60 * 60 * 1000L;

    /** Batch window: all distinct reel views inside this span fold into
     *  one combined chat bubble. 15 min — long enough to cover a normal
     *  reel-browsing session, short enough that the bubble still reads as
     *  "recent activity" rather than a stale running total. */
    private static final long BATCH_WINDOW_MS = 15 * 60 * 1000L;

    // ── ADVANCED OPT: in-process session caches ──────────────────────────
    // A single reel-browsing session can fire this method dozens of times
    // in seconds. Before this cache layer, EVERY view paid ~5 Firebase
    // round-trips (contact read, dedup read+write, batch transaction,
    // message write) even when nothing about the outcome had actually
    // changed since the last view. These caches make the steady-state
    // (viewing many reels from someone already known-contact, inside an
    // already-open batch window) cost ONE fire-and-forget write — no reads.
    //
    // Tradeoff (accepted, cosmetic feature): count/window state is trusted
    // from memory once warm, not re-verified against the server on every
    // view. On a second concurrent device the two caches could drift for
    // one batch window — worst case is a slightly-off count on a "watched
    // your reel" bubble, not data loss or a crash. Cleared on process death,
    // so every fresh app-open re-verifies once (cache miss) before trusting
    // memory again.
    private static final Map<String, Boolean> CONTACT_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> DEDUP_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, BatchState> BATCH_CACHE = new ConcurrentHashMap<>();

    private static final class BatchState {
        final String messageId;
        long windowStart;
        long count;
        BatchState(String messageId, long windowStart, long count) {
            this.messageId = messageId;
            this.windowStart = windowStart;
            this.count = count;
        }
    }

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

        // FAST PATH: contact relationship already confirmed this session —
        // zero Firebase reads, straight to dedup.
        Boolean cachedContact = CONTACT_CACHE.get(ownerUid);
        if (cachedContact != null) {
            if (!cachedContact) return; // cached "not a contact" — skip, no writes
            checkDedupThenBatch(myUid, ownerUid, reelId, finalThumb);
            return;
        }

        // COLD PATH: first time we've seen this owner this session — verify
        // once against Firebase, then cache the answer for every later view.
        FirebaseUtils.getContactsRef(myUid).child(ownerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot contactSnap) {
                    boolean isContact = contactSnap.exists();
                    CONTACT_CACHE.put(ownerUid, isContact);
                    if (!isContact) return; // never chatted — skip, no writes at all
                    checkDedupThenBatch(myUid, ownerUid, reelId, finalThumb);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    // Can't confirm the relationship — fail closed (skip, don't
                    // cache) so a transient read error never turns into phantom-
                    // thread spam and gets a fair retry on the next reel view.
                }
            });
    }

    /** Step 1: per-reel dedup — memory-first, Firebase only on a cache miss. */
    private static void checkDedupThenBatch(
            String myUid, String ownerUid, String reelId, String finalThumb) {

        long now = System.currentTimeMillis();
        Long cachedLastSeen = DEDUP_CACHE.get(reelId);
        if (cachedLastSeen != null) {
            if ((now - cachedLastSeen) < DEDUP_WINDOW_MS) return; // warm cache: skip, zero calls
            DEDUP_CACHE.put(reelId, now);
            runBatchTransaction(myUid, ownerUid, reelId, finalThumb); // no read/write needed, trust memory
            return;
        }

        // Cold (first time this reelId is seen this session) — verify against
        // Firebase once so a rewatch from a PREVIOUS session still dedupes
        // correctly, then warm the cache for every later view in this one.
        DatabaseReference dedupRef =
                FirebaseUtils.db().getReference("reelSeenDedup").child(myUid).child(reelId);

        dedupRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                long now = System.currentTimeMillis();
                Long lastTs = snap.getValue(Long.class);
                DEDUP_CACHE.put(reelId, now);
                if (lastTs != null && (now - lastTs) < DEDUP_WINDOW_MS) {
                    return; // same reel already counted within 1h — skip
                }
                dedupRef.setValue(now);
                runBatchTransaction(myUid, ownerUid, reelId, finalThumb);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                // Dedup check failed — proceed anyway (safe: worst case is one
                // extra count, not a phantom thread, since the contacts gate
                // above already confirmed a real relationship).
                runBatchTransaction(myUid, ownerUid, reelId, finalThumb);
            }
        });
    }

    /**
     * Entry point for step 2 — fast path first (trust warm BATCH_CACHE, one
     * fire-and-forget write, no reads), falling back to the authoritative
     * Firebase transaction only on a cold/expired cache.
     */
    private static void runBatchTransaction(
            String myUid, String ownerUid, String reelId, String finalThumb) {

        final String chatId = myUid.compareTo(ownerUid) < 0
                ? myUid + "_" + ownerUid
                : ownerUid + "_" + myUid;

        BatchState cached = BATCH_CACHE.get(chatId);
        long now = System.currentTimeMillis();
        if (cached != null) {
            synchronized (cached) {
                if ((now - cached.windowStart) < BATCH_WINDOW_MS) {
                    // FAST PATH: warm window, just increment in memory and
                    // fire a plain (non-transactional) write — no reads.
                    cached.count++;
                    long newCount = cached.count;
                    DatabaseReference messagesRef =
                            FirebaseUtils.db().getReference("messages").child(chatId);
                    DatabaseReference batchRef =
                            FirebaseUtils.db().getReference("reelSeenBatch").child(chatId);
                    updateExistingBubble(messagesRef, cached.messageId, reelId, finalThumb, newCount);
                    // Keep the server-side count in sync too (fire-and-forget,
                    // no read) so a second device warming its own cache later
                    // still lands on the right number.
                    Map<String, Object> batchUpdate = new HashMap<>();
                    batchUpdate.put("count", newCount);
                    batchUpdate.put("lastReelId", reelId);
                    batchUpdate.put("lastReelThumb", finalThumb);
                    batchRef.updateChildren(batchUpdate);
                    return;
                }
                // Expired — fall through to the transaction below, which will
                // also refresh/replace this cache entry once it resolves.
            }
        }
        runBatchTransactionCold(myUid, ownerUid, reelId, finalThumb, chatId);
    }

    /**
     * Cold/authoritative path: atomically decide "new window" vs "increment
     * existing window" on reelSeenBatch/{chatId} via a real transaction —
     * only reached on the first view of a session or once a window expires.
     */
    private static void runBatchTransactionCold(
            String myUid, String ownerUid, String reelId, String finalThumb, String chatId) {

        final DatabaseReference messagesRef =
                FirebaseUtils.db().getReference("messages").child(chatId);
        final DatabaseReference batchRef =
                FirebaseUtils.db().getReference("reelSeenBatch").child(chatId);

        // Generated locally (no server round-trip) — only actually used if
        // the transaction below decides this view starts a NEW window.
        final String candidateMsgId = messagesRef.push().getKey();
        if (candidateMsgId == null) return;

        batchRef.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData data) {
                Long windowStart = data.child("windowStart").getValue(Long.class);
                long now = System.currentTimeMillis();

                boolean expiredOrMissing = windowStart == null || (now - windowStart) >= BATCH_WINDOW_MS;
                if (expiredOrMissing) {
                    data.child("messageId").setValue(candidateMsgId);
                    data.child("windowStart").setValue(now);
                    data.child("count").setValue(1L);
                } else {
                    Long count = data.child("count").getValue(Long.class);
                    data.child("count").setValue((count == null ? 0L : count) + 1L);
                    // messageId/windowStart untouched — same bubble, same window
                }
                data.child("lastReelId").setValue(reelId);
                data.child("lastReelThumb").setValue(finalThumb);
                data.child("ownerUid").setValue(ownerUid);
                data.child("viewerUid").setValue(myUid);
                return Transaction.success(data);
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed,
                                    @Nullable DataSnapshot snapshot) {
                if (error != null || !committed || snapshot == null) return; // fail closed

                String resultMsgId = snapshot.child("messageId").getValue(String.class);
                Long resultCount = snapshot.child("count").getValue(Long.class);
                Long resultWindowStart = snapshot.child("windowStart").getValue(Long.class);
                long count = resultCount == null ? 1L : resultCount;
                long windowStart = resultWindowStart == null ? System.currentTimeMillis() : resultWindowStart;
                if (resultMsgId == null) return;

                // Warm the fast-path cache so every subsequent view in this
                // window skips straight past the transaction above.
                BATCH_CACHE.put(chatId, new BatchState(resultMsgId, windowStart, count));

                if (resultMsgId.equals(candidateMsgId)) {
                    // NEW WINDOW — this view started it, write the full row.
                    fetchProfileAndWriteNew(messagesRef, myUid, ownerUid, reelId, finalThumb, candidateMsgId);
                } else {
                    // EXISTING WINDOW — fold into the row already on screen.
                    updateExistingBubble(messagesRef, resultMsgId, reelId, finalThumb, count);
                }
            }
        });
    }

    /** New window: fetch viewer profile once, then push the first row. */
    private static void fetchProfileAndWriteNew(
            DatabaseReference messagesRef, String myUid, String ownerUid,
            String reelId, String finalThumb, String msgId) {

        FirebaseUtils.db().getReference("users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot userSnap) {
                    String viewerName  = userSnap.child("name").getValue(String.class);
                    String viewerPhoto = userSnap.child("photoUrl").getValue(String.class);
                    if (viewerPhoto == null) viewerPhoto = userSnap.child("thumbUrl").getValue(String.class);
                    doWriteNew(messagesRef, msgId, myUid,
                            viewerName  != null ? viewerName  : "Someone",
                            viewerPhoto != null ? viewerPhoto : "",
                            ownerUid, reelId, finalThumb);
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    doWriteNew(messagesRef, msgId, myUid, "Someone", "", ownerUid, reelId, finalThumb);
                }
            });
    }

    /** Push the FIRST reel_seen message node for a fresh batch window. */
    private static void doWriteNew(
            DatabaseReference messagesRef, String msgId,
            String viewerUid, String viewerName, String viewerPhoto,
            String ownerUid, String reelId, String reelThumbUrl) {

        Map<String, Object> msg = new HashMap<>();
        msg.put("id",           msgId);
        msg.put("senderId",     viewerUid);
        msg.put("senderName",   viewerName);
        msg.put("senderPhoto",  viewerPhoto);   // circular avatar in bubble
        msg.put("text",         "Watched your reel");
        msg.put("type",         "reel_seen");
        msg.put("reelId",       reelId);        // tap-to-open (most recent)
        msg.put("reelThumbUrl", reelThumbUrl);  // thumbnail shown in bubble
        msg.put("timestamp",    ServerValue.TIMESTAMP);
        msg.put("seen",         false);         // no unread badge
        msg.put("reelOwnerUid", ownerUid);

        messagesRef.child(msgId).setValue(msg);
    }

    /** Fold a subsequent same-window view into the existing bubble in place. */
    private static void updateExistingBubble(
            DatabaseReference messagesRef, String msgId,
            String reelId, String reelThumbUrl, long count) {

        Map<String, Object> updates = new HashMap<>();
        updates.put("text",         count > 1 ? ("Watched " + count + " of your reels") : "Watched your reel");
        updates.put("reelId",       reelId);
        updates.put("reelThumbUrl", reelThumbUrl);
        updates.put("timestamp",    ServerValue.TIMESTAMP); // bump to "now"

        messagesRef.child(msgId).updateChildren(updates);
    }

    private static String safeUid() {
        try {
            return FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            return null;
        }
    }
}
