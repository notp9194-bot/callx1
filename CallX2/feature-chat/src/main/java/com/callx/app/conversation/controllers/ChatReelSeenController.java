package com.callx.app.conversation.controllers;

import android.os.Handler;
import android.os.Looper;

import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatReelSeenController — v21
 *
 * Listens to reelSeenEvents/{myUid}/{partnerUid} (the side-tree written by
 * ReelSeenTracker) and injects synthetic reel_seen Message objects into the
 * MessagePagingAdapter at display-time.
 *
 * KEY PROPERTIES:
 *   • Zero writes to messages/{chatId} — Room / pagination / sync untouched.
 *   • Viewer side never sees the events (path is keyed by ownerUid first,
 *     so viewerUid listens to reelSeenEvents/{viewerUid}/... which is empty
 *     for events they sent — those live under reelSeenEvents/{ownerUid}/...).
 *   • Keeps up to MAX_EVENTS synthetic rows in memory only; no Room insert.
 *   • Detaches and clears on destroy — no leaks.
 *
 * LIFECYCLE:
 *   ChatActivity calls attach() in onStart() and detach() in onStop() /
 *   onDestroy(). The controller is constructed once and reused across
 *   onStart/onStop pairs.
 *
 * INTEGRATION WITH ADAPTER:
 *   After inject/remove the controller calls adapter.notifyReelSeenChanged()
 *   which triggers a lightweight DiffUtil pass on only the synthetic rows.
 */
public class ChatReelSeenController {

    // Maximum synthetic reel_seen rows kept per chat session.
    private static final int MAX_EVENTS   = 20;
    private static final int LIMIT_QUERY  = 20;

    private final ChatActivityDelegate   delegate;
    private final Handler                mainHandler = new Handler(Looper.getMainLooper());

    // In-memory list of synthetic reel_seen Message objects for THIS session.
    private final List<Message> syntheticRows = new ArrayList<>();

    private Query           query;
    private ChildEventListener listener;
    private boolean         attached = false;

    public ChatReelSeenController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Start listening to reelSeenEvents/{myUid}/{partnerUid}.
     * Safe to call multiple times — detaches old listener first.
     */
    public void attach() {
        if (attached) detach();

        String myUid      = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();
        if (myUid == null || partnerUid == null) return;

        // As owner: listen for events the partner (viewer) sent about MY reels.
        DatabaseReference eventsRef = FirebaseUtils.db()
                .getReference("reelSeenEvents")
                .child(myUid)
                .child(partnerUid);

        query    = eventsRef.orderByChild("timestamp").limitToLast(LIMIT_QUERY);
        listener = buildListener();
        query.addChildEventListener(listener);
        attached = true;
    }

    /** Stop listening and clear synthetic rows. */
    public void detach() {
        if (query != null && listener != null) {
            query.removeEventListener(listener);
        }
        query    = null;
        listener = null;
        attached = false;
        syntheticRows.clear();
        // Notify adapter that synthetic rows are gone (e.g. on ChatActivity stop).
        notifyAdapter();
    }

    // ── Snapshot → synthetic Message ──────────────────────────────────────

    private ChildEventListener buildListener() {
        return new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snap, String prev) {
                Message m = snapshotToMessage(snap);
                if (m == null) return;
                mainHandler.post(() -> {
                    // Avoid duplicates (Firebase may re-deliver on re-attach).
                    for (Message existing : syntheticRows) {
                        if (m.id.equals(existing.id)) return;
                    }
                    syntheticRows.add(m);
                    // Keep cap.
                    while (syntheticRows.size() > MAX_EVENTS) {
                        syntheticRows.remove(0);
                    }
                    notifyAdapter();
                });
            }

            @Override
            public void onChildRemoved(DataSnapshot snap) {
                String removedId = snap.getKey();
                if (removedId == null) return;
                mainHandler.post(() -> {
                    syntheticRows.removeIf(m -> removedId.equals(m.id));
                    notifyAdapter();
                });
            }

            // reel_seen events are immutable — no child changes expected.
            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildMoved(DataSnapshot s, String p)   {}
            @Override public void onCancelled(DatabaseError e)             {}
        };
    }

    private static Message snapshotToMessage(DataSnapshot snap) {
        if (snap == null || snap.getKey() == null) return null;

        Message m = new Message();
        m.id           = snap.getKey();
        m.messageId    = m.id;
        m.type         = "reel_seen";
        m.senderId     = snap.child("senderId").getValue(String.class);
        m.senderName   = snap.child("senderName").getValue(String.class);
        m.senderPhoto  = snap.child("senderPhoto").getValue(String.class);
        m.reelId       = snap.child("reelId").getValue(String.class);
        m.reelThumbUrl = snap.child("reelThumbUrl").getValue(String.class);

        Long ts = snap.child("timestamp").getValue(Long.class);
        m.timestamp   = ts != null ? ts : System.currentTimeMillis();

        // reelOwnerUid = current user (the chat owner listening to this node).
        // The adapter checks currentUid.equals(m.reelOwnerUid) to gate display.
        // Since we're reading from reelSeenEvents/{myUid}/... myUid IS the owner.
        // We set this so the existing adapter gating logic works unchanged.
        m.reelOwnerUid = null; // set by adapter accessor — see getSyntheticRows()

        return m;
    }

    // ── Adapter callback ──────────────────────────────────────────────────

    private void notifyAdapter() {
        com.callx.app.conversation.MessagePagingAdapter adapter =
                delegate.getPagingAdapter();
        if (adapter != null) {
            adapter.setSyntheticReelSeenRows(getRowsWithOwner());
        }
    }

    /**
     * Returns a snapshot of synthetic rows with reelOwnerUid pre-filled to
     * the current user so the adapter's existing gating check passes.
     */
    private List<Message> getRowsWithOwner() {
        String myUid = delegate.getCurrentUid();
        List<Message> out = new ArrayList<>(syntheticRows.size());
        for (Message m : syntheticRows) {
            // Shallow-copy to avoid mutating the stored object.
            Message copy = shallowCopy(m);
            copy.reelOwnerUid = myUid;
            out.add(copy);
        }
        return out;
    }

    private static Message shallowCopy(Message src) {
        Message dst = new Message();
        dst.id           = src.id;
        dst.messageId    = src.messageId;
        dst.type         = src.type;
        dst.senderId     = src.senderId;
        dst.senderName   = src.senderName;
        dst.senderPhoto  = src.senderPhoto;
        dst.reelId       = src.reelId;
        dst.reelThumbUrl = src.reelThumbUrl;
        dst.timestamp    = src.timestamp;
        dst.reelOwnerUid = src.reelOwnerUid;
        return dst;
    }
}
