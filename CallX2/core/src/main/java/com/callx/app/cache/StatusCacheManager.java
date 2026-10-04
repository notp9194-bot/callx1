package com.callx.app.cache;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.annotation.NonNull;

/**
 * StatusCacheManager — App-wide singleton cache for status data.
 *
 * Purpose:
 *   ✅ Status data sirf ek baar Firebase se fetch hota hai — pure app mein reuse hota hai.
 *   ✅ StatusFragment, CallHistoryAdapter, ReelPlayerFragment, ReelCommentsAdapter
 *      sab yahan se hasUnseen(uid) query karte hain — zero duplicate Firebase reads.
 *   ✅ Real-time Firebase listener se automatic refresh hota rehta hai.
 *   ✅ Seen map bhi cache mein — seen/unseen ring correctly dikhai deta hai.
 *
 * Usage:
 *   StatusCacheManager.getInstance(context).hasUnseen(uid)
 *   StatusCacheManager.getInstance(context).getStatuses(uid)
 *   StatusCacheManager.getInstance(context).startListening(myUid)
 */
public class StatusCacheManager {

    private static final String TAG = "StatusCacheManager";
    private static StatusCacheManager sInstance;

    // v46: application Context, captured on first getInstance() call, so
    // hasUnseen() can consult StorySeenState's local optimistic seen-marks
    // without every call site (7 files across reels/chat/calls) having to
    // change signature to pass one through.
    private Context appContext;

    // ── In-memory cache ────────────────────────────────────────────────────
    /** ownerUid → active (non-expired, non-deleted) StatusItems */
    private final Map<String, List<StatusItem>> statusMap = new LinkedHashMap<>();

    /** ownerUid → set of statusIds the current user has seen */
    private final Map<String, Set<String>> seenMap = new HashMap<>();

    /** Observers — adapter/fragments jo refresh chahte hain jab data aata hai */
    private final List<StatusDataObserver> observers = new ArrayList<>();

    // ── Firebase listeners (held to detach on cleanup) ─────────────────────
    private ValueEventListener statusListener;
    private ValueEventListener seenListener;
    private String attachedMyUid;
    private boolean listening = false;

    public interface StatusDataObserver {
        void onStatusDataUpdated();
    }

    private StatusCacheManager() {}

    public static synchronized StatusCacheManager getInstance(Context ctx) {
        if (sInstance == null) sInstance = new StatusCacheManager();
        if (sInstance.appContext == null && ctx != null) {
            sInstance.appContext = ctx.getApplicationContext();
        }
        return sInstance;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Kya uid ke koi unseen status hain?
     * Avatar ring show karne ke liye use karo.
     */
    public boolean hasUnseen(String uid) {
        if (uid == null) return false;
        List<StatusItem> items = statusMap.get(uid);
        if (items == null || items.isEmpty()) return false;
        final long now = System.currentTimeMillis();
        Set<String> seen = seenMap.getOrDefault(uid, Collections.emptySet());
        // v46: this uid's latest local optimistic "seen at" mark (0 if never
        // marked locally) — StatusSeenTracker.markSeen() stamps this the
        // instant the user views a story, well before Firebase's seenBy
        // write round-trips back through our listener into `seen` above.
        long locallySeenAt = com.callx.app.utils.StorySeenState.getSeenAt(appContext, uid);
        for (StatusItem item : items) {
            // FIX: expired story pe ring nahi — cache sirf Firebase event pe
            // filter hota tha, time guzarne pe nahi.
            if (!isLive(item, now)) continue;
            if (seen.contains(item.id)) continue;
            // Firebase hasn't confirmed this item as seen yet, but if our
            // local mark covers this item's timestamp, treat it as seen —
            // this is what actually makes the ring flip instantly instead
            // of waiting on the network round-trip.
            if (locallySeenAt > 0 && item.timestamp != null && item.timestamp <= locallySeenAt) continue;
            return true;
        }
        return false;
    }

    /**
     * Kya uid ka koi bhi active status hai?
     * (Seen/unseen ke bina — sirf existence check)
     */
    public boolean hasStatus(String uid) {
        if (uid == null) return false;
        List<StatusItem> items = statusMap.get(uid);
        if (items == null || items.isEmpty()) return false;
        final long now = System.currentTimeMillis();
        for (StatusItem item : items) {
            if (isLive(item, now)) return true;
        }
        return false;
    }

    /** Story abhi bhi active hai? (deleted nahi + expiresAt guzra nahi) */
    private static boolean isLive(StatusItem item, long now) {
        if (item == null) return false;
        if (Boolean.TRUE.equals(item.deleted)) return false;
        return item.expiresAt == null || item.expiresAt >= now;
    }

    /** uid ke saare active StatusItems return karo */
    public List<StatusItem> getStatuses(String uid) {
        if (uid == null) return new ArrayList<>();
        List<StatusItem> items = statusMap.get(uid);
        if (items == null) return new ArrayList<>();
        final long now = System.currentTimeMillis();
        List<StatusItem> live = new ArrayList<>(items.size());
        for (StatusItem item : items) if (isLive(item, now)) live.add(item);
        return live;
    }

    /**
     * Unseen count return karo — badge number ke liye useful
     */
    public int getUnseenCount(String uid) {
        if (uid == null) return 0;
        List<StatusItem> items = statusMap.get(uid);
        if (items == null || items.isEmpty()) return 0;
        Set<String> seen = seenMap.getOrDefault(uid, Collections.emptySet());
        final long now = System.currentTimeMillis();
        int count = 0;
        for (StatusItem item : items) {
            if (!isLive(item, now)) continue;
            if (!seen.contains(item.id)) count++;
        }
        return count;
    }

    /** Saara status map return karo (StatusFragment ke liye) */
    public Map<String, List<StatusItem>> getAllStatuses() {
        final long now = System.currentTimeMillis();
        Map<String, List<StatusItem>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<StatusItem>> e : statusMap.entrySet()) {
            List<StatusItem> live = new ArrayList<>(e.getValue().size());
            for (StatusItem item : e.getValue()) if (isLive(item, now)) live.add(item);
            if (!live.isEmpty()) out.put(e.getKey(), live);
        }
        return Collections.unmodifiableMap(out);
    }

    /** Seen map return karo (StatusFragment ke liye) */
    public Map<String, Set<String>> getSeenMap() {
        return Collections.unmodifiableMap(seenMap);
    }

    // ── Observer pattern ───────────────────────────────────────────────────

    public void addObserver(StatusDataObserver observer) {
        if (!observers.contains(observer)) observers.add(observer);
    }

    public void removeObserver(StatusDataObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers() {
        scheduleNextExpirySweep();
        for (StatusDataObserver o : new ArrayList<>(observers)) {
            try { o.onStatusDataUpdated(); } catch (Exception ignored) {}
        }
    }

    // ── Auto expiry sweep ──────────────────────────────────────────────────
    // FIX: story expire hone pe ring sirf tab hatti jab Firebase me koi write
    // aata tha. Ab agli expiry ke exact time pe cache khud prune hota hai aur
    // observers (StoryRingRegistry -> har screen ki ring) ko notify karta hai.
    private final Handler expiryHandler = new Handler(Looper.getMainLooper());
    private final Runnable expiryRunnable = this::sweepExpired;

    private void scheduleNextExpirySweep() {
        expiryHandler.removeCallbacks(expiryRunnable);
        final long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (List<StatusItem> items : statusMap.values()) {
            for (StatusItem item : items) {
                if (item.expiresAt == null || item.expiresAt < now) continue;
                if (item.expiresAt < next) next = item.expiresAt;
            }
        }
        if (next == Long.MAX_VALUE) return;
        long delay = Math.max(1_000L, next - now + 500L);
        // Handler delay bohot lamba na ho (24h+) — max 10 min me re-evaluate.
        expiryHandler.postDelayed(expiryRunnable, Math.min(delay, 10 * 60_000L));
    }

    private void sweepExpired() {
        final long now = System.currentTimeMillis();
        boolean changed = false;
        for (java.util.Iterator<Map.Entry<String, List<StatusItem>>> it = statusMap.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<StatusItem>> e = it.next();
            List<StatusItem> items = e.getValue();
            for (java.util.Iterator<StatusItem> ii = items.iterator(); ii.hasNext(); ) {
                if (!isLive(ii.next(), now)) { ii.remove(); changed = true; }
            }
            if (items.isEmpty()) it.remove();
        }
        if (changed) notifyObservers(); else scheduleNextExpirySweep();
    }

    // ── Firebase listener management ───────────────────────────────────────

    /**
     * Firebase listener start karo.
     * Ek baar call karo — jab tak app chalta hai tab tak active rehta hai.
     * Multiple calls safe hain (idempotent).
     */
    public void startListening(String myUid) {
        if (myUid == null) return;
        if (listening && myUid.equals(attachedMyUid)) return; // already listening

        stopListening(); // cleanup old listeners if uid changed
        attachedMyUid = myUid;
        listening = true;

        Log.d(TAG, "Starting status cache listener for uid=" + myUid);
        attachStatusListener(myUid);
        attachSeenListener(myUid);
    }

    public void stopListening() {
        expiryHandler.removeCallbacks(expiryRunnable);
        if (statusListener != null && FirebaseUtils.getStatusRef() != null) {
            FirebaseUtils.getStatusRef().removeEventListener(statusListener);
            statusListener = null;
        }
        if (seenListener != null && attachedMyUid != null) {
            FirebaseUtils.db()
                .getReference("statusSeen")
                .child(attachedMyUid)
                .removeEventListener(seenListener);
            seenListener = null;
        }
        listening = false;
    }

    // ── Internal Firebase attachment ───────────────────────────────────────

    private void attachStatusListener(final String myUid) {
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                long now = System.currentTimeMillis();
                statusMap.clear();

                for (DataSnapshot userSnap : snap.getChildren()) {
                    String uid = userSnap.getKey();
                    if (uid == null) continue;
                    // Optional: filter only contacts — left open so all statuses are available
                    List<StatusItem> items = new ArrayList<>();
                    for (DataSnapshot stSnap : userSnap.getChildren()) {
                        try {
                            StatusItem item = stSnap.getValue(StatusItem.class);
                            if (item == null || Boolean.TRUE.equals(item.deleted)) continue;
                            if (item.expiresAt != null && item.expiresAt < now) continue;
                            items.add(item);
                        } catch (Exception ignored) {}
                    }
                    items.sort((a, b) -> {
                        long ta = a.timestamp == null ? 0 : a.timestamp;
                        long tb = b.timestamp == null ? 0 : b.timestamp;
                        return Long.compare(ta, tb);
                    });
                    if (!items.isEmpty()) statusMap.put(uid, items);
                }

                Log.d(TAG, "Status cache updated: " + statusMap.size() + " users with active statuses");
                notifyObservers();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                Log.w(TAG, "Status listener cancelled: " + e.getMessage());
            }
        };
        FirebaseUtils.getStatusRef().addValueEventListener(statusListener);
    }

    private void attachSeenListener(final String myUid) {
        seenListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                seenMap.clear();
                for (DataSnapshot ownerSnap : snap.getChildren()) {
                    String ownerUid = ownerSnap.getKey();
                    if (ownerUid == null) continue;
                    Set<String> ids = new HashSet<>();
                    for (DataSnapshot idSnap : ownerSnap.getChildren()) {
                        if (idSnap.getKey() != null) ids.add(idSnap.getKey());
                    }
                    seenMap.put(ownerUid, ids);
                }
                Log.d(TAG, "Seen cache updated");
                notifyObservers();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                Log.w(TAG, "Seen listener cancelled: " + e.getMessage());
            }
        };
        FirebaseUtils.db()
            .getReference("statusSeen")
            .child(myUid)
            .addValueEventListener(seenListener);
    }
}
