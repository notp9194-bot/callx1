package com.callx.app.library;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.HashMap;
import java.util.Map;

/**
 * WatchHistoryManager — Singleton manager for recording + querying watch history
 *
 * Firebase path: watchHistory/{uid}/{reelId}
 *
 * Features:
 *  ✅ Record watch — upsert (creates or updates existing entry)
 *  ✅ Atomic increment of watchCount via runTransaction
 *  ✅ Update percentWatched on each watch
 *  ✅ Delete single entry
 *  ✅ Clear all history
 *  ✅ Check if a reel is in history
 *  ✅ Privacy-safe: only current user can read/write their own history
 *
 * Usage:
 *   WatchHistoryManager.get().record(reelModel, 75);  // 75% watched
 *   WatchHistoryManager.get().delete(reelId);
 *   WatchHistoryManager.get().clearAll();
 */
public class WatchHistoryManager {

    private static final String TAG            = "WatchHistory";
    private static final int    MAX_HISTORY    = 500; // max entries kept per user
    private static final String HISTORY_ROOT   = "watchHistory";

    private static volatile WatchHistoryManager instance;

    public static WatchHistoryManager get() {
        if (instance == null) {
            synchronized (WatchHistoryManager.class) {
                if (instance == null) instance = new WatchHistoryManager();
            }
        }
        return instance;
    }

    private WatchHistoryManager() {}

    // ── Record / upsert ───────────────────────────────────────────────────────

    /**
     * Record that the current user watched a reel.
     * @param reel           The reel that was watched
     * @param percentWatched How much was watched: 0–100
     */
    public void record(@NonNull ReelModel reel, int percentWatched) {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty() || reel.reelId == null) return;

        DatabaseReference ref = historyRef(uid).child(reel.reelId);

        // Upsert: if entry exists, update watchedAtMs + watchCount + percent;
        // if new, create full entry
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                Map<String, Object> updates = new HashMap<>();
                updates.put("watchedAtMs",   System.currentTimeMillis());
                updates.put("percentWatched", percentWatched);

                if (snap.exists()) {
                    // Increment watchCount atomically
                    ref.child("watchCount").runTransaction(new Transaction.Handler() {
                        @NonNull @Override
                        public Transaction.Result doTransaction(@NonNull MutableData data) {
                            Integer count = data.getValue(Integer.class);
                            data.setValue(count == null ? 2 : count + 1);
                            return Transaction.success(data);
                        }
                        @Override
                        public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
                    });
                    ref.updateChildren(updates);
                } else {
                    // New entry — write full model
                    WatchHistoryItem item = new WatchHistoryItem(
                        reel.reelId, reel.uid, reel.ownerName, reel.ownerPhoto,
                        reel.thumbUrl != null ? reel.thumbUrl : reel.thumbnailUrl,
                        reel.caption, reel.mediaType, reel.duration);
                    item.percentWatched = percentWatched;
                    ref.setValue(item);

                    // Enforce max history limit
                    trimHistory(uid);
                }
            }

            @Override public void onCancelled(@NonNull DatabaseError e) {
                Log.w(TAG, "record cancelled: " + e.getMessage());
            }
        });
    }

    // ── Delete single entry ───────────────────────────────────────────────────

    public void delete(@NonNull String reelId) {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty()) return;
        historyRef(uid).child(reelId).removeValue()
            .addOnFailureListener(e -> Log.e(TAG, "delete failed: " + e.getMessage()));
    }

    // ── Clear all history ─────────────────────────────────────────────────────

    public void clearAll(@NonNull OnClearListener listener) {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty()) { listener.onComplete(false); return; }
        historyRef(uid).removeValue((error, ref) -> {
            if (error != null) {
                Log.e(TAG, "clearAll failed: " + error.getMessage());
                listener.onComplete(false);
            } else {
                listener.onComplete(true);
            }
        });
    }

    // ── Check if in history ───────────────────────────────────────────────────

    public void isInHistory(@NonNull String reelId, @NonNull OnCheckListener listener) {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty()) { listener.onResult(false); return; }
        historyRef(uid).child(reelId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                listener.onResult(s.exists());
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                listener.onResult(false);
            }
        });
    }

    // ── Get single item ───────────────────────────────────────────────────────

    public DatabaseReference historyRef(String uid) {
        return FirebaseUtils.db().getReference(HISTORY_ROOT).child(uid);
    }

    public DatabaseReference currentUserHistoryRef() {
        return historyRef(FirebaseUtils.getCurrentUid());
    }

    // ── Trim old entries (keep newest MAX_HISTORY) ────────────────────────────

    private void trimHistory(String uid) {
        historyRef(uid).orderByChild("watchedAtMs")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    long count = snap.getChildrenCount();
                    if (count <= MAX_HISTORY) return;
                    long toDelete = count - MAX_HISTORY;
                    for (DataSnapshot s : snap.getChildren()) {
                        if (toDelete-- <= 0) break;
                        s.getRef().removeValue();
                    }
                    Log.d(TAG, "Trimmed " + (count - MAX_HISTORY) + " old entries");
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public interface OnClearListener { void onComplete(boolean success); }
    public interface OnCheckListener { void onResult(boolean inHistory); }
}
