package com.callx.app.cache;

import android.content.Context;
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
        Set<String> seen = seenMap.getOrDefault(uid, Collections.emptySet());
        for (StatusItem item : items) {
            if (!seen.contains(item.id)) return true;
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
        return items != null && !items.isEmpty();
    }

    /** uid ke saare active StatusItems return karo */
    public List<StatusItem> getStatuses(String uid) {
        if (uid == null) return new ArrayList<>();
        List<StatusItem> items = statusMap.get(uid);
        return items != null ? items : new ArrayList<>();
    }

    /**
     * Unseen count return karo — badge number ke liye useful
     */
    public int getUnseenCount(String uid) {
        if (uid == null) return 0;
        List<StatusItem> items = statusMap.get(uid);
        if (items == null || items.isEmpty()) return 0;
        Set<String> seen = seenMap.getOrDefault(uid, Collections.emptySet());
        int count = 0;
        for (StatusItem item : items) {
            if (!seen.contains(item.id)) count++;
        }
        return count;
    }

    /** Saara status map return karo (StatusFragment ke liye) */
    public Map<String, List<StatusItem>> getAllStatuses() {
        return Collections.unmodifiableMap(statusMap);
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
        for (StatusDataObserver o : observers) {
            try { o.onStatusDataUpdated(); } catch (Exception ignored) {}
        }
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
                            if (item == null || item.deleted) continue;
                            if (item.expiresAt != null && item.expiresAt < now) continue;
                            items.add(item);
                        } catch (Exception ignored) {}
                    }
                    items.sort((a, b) -> {
                        long ta = (a.timestamp instanceof Long) ? (Long) a.timestamp : 0L;
                        long tb = (b.timestamp instanceof Long) ? (Long) b.timestamp : 0L;
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
