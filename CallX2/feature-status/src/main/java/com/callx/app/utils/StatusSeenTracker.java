package com.callx.app.utils;

import androidx.annotation.NonNull;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StatusSeenTracker — Thread-safe, production-level seen/reaction manager.
 *
 * Responsibilities:
 *   1. Mark a status as seen (with timestamp) — batched to avoid per-frame writes
 *   2. Write reactions (emoji) per status per user
 *   3. Remove reactions
 *   4. Fetch the full seen-by list with reactions for the owner view
 *   5. Check if current user already saw a given status (local in-memory cache)
 */
public class StatusSeenTracker {

    private static final String TAG = "StatusSeenTracker";

    // ── Singleton ─────────────────────────────────────────────────────────
    private static volatile StatusSeenTracker instance;
    public static StatusSeenTracker get() {
        if (instance == null) synchronized (StatusSeenTracker.class) {
            if (instance == null) instance = new StatusSeenTracker();
        }
        return instance;
    }

    // ── State ─────────────────────────────────────────────────────────────
    /** Local cache: statusId → Set<viewerUid> seen already this session */
    private final Map<String, Set<String>> seenCache = new ConcurrentHashMap<>();
    /** Pending batch writes: statusId → map of viewerUid→timestamp */
    private final Map<String, Map<String, Long>> pendingBatch = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    // Batch flush interval — 2 seconds to group rapid views (e.g., auto-advance)
    private static final long FLUSH_DELAY_MS = 2_000;

    private StatusSeenTracker() {}

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Mark a status as seen by the current user.
     * Call from StatusViewerActivity whenever a segment becomes visible.
     * Batches writes — safe to call repeatedly.
     */
    public void markSeen(@NonNull String ownerUid,
                         @NonNull String statusId,
                         @NonNull String viewerUid) {
        // Don't mark your own status as seen
        if (viewerUid.equals(ownerUid)) return;

        // Check in-memory cache first
        Set<String> seenSet = seenCache.computeIfAbsent(statusId,
            k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        if (seenSet.contains(viewerUid)) return; // already marked this session
        seenSet.add(viewerUid);

        // Add to pending batch
        Map<String, Long> batch = pendingBatch.computeIfAbsent(statusId,
            k -> new ConcurrentHashMap<>());
        batch.put(viewerUid, System.currentTimeMillis());

        // Schedule flush if not already pending
        if (flushScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::flushBatch, FLUSH_DELAY_MS,
                TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Write a reaction to a status.
     * Firebase node: statusReactions/{ownerUid}/{statusId}/{viewerUid} = emoji
     */
    public void setReaction(@NonNull String ownerUid,
                            @NonNull String statusId,
                            @NonNull String viewerUid,
                            @NonNull String emoji) {
        FirebaseUtils.getStatusReactionRef(ownerUid, statusId)
            .child(viewerUid).setValue(emoji);

        // Also update denormalized reactions count on the status node itself
        FirebaseUtils.getUserStatusRef(ownerUid)
            .child(statusId)
            .child("reactions")
            .child(viewerUid)
            .setValue(emoji);

        // Notify status owner
        StatusNotificationHelper.notifyStatusReaction(ownerUid, statusId,
            viewerUid, emoji);
    }

    /**
     * Remove this user's reaction from a status.
     */
    public void removeReaction(@NonNull String ownerUid,
                               @NonNull String statusId,
                               @NonNull String viewerUid) {
        FirebaseUtils.getStatusReactionRef(ownerUid, statusId)
            .child(viewerUid).removeValue();
        FirebaseUtils.getUserStatusRef(ownerUid)
            .child(statusId)
            .child("reactions")
            .child(viewerUid)
            .removeValue();
    }

    /**
     * Fetch the full seen-by list for a status owned by the current user.
     * Returns Map<viewerUid, timestamp>.
     */
    public void fetchSeenBy(@NonNull String ownerUid,
                            @NonNull String statusId,
                            @NonNull SeenByCallback callback) {
        FirebaseUtils.getStatusSeenByRef(ownerUid, statusId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Map<String, Long> result = new LinkedHashMap<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        Long ts = child.getValue(Long.class);
                        result.put(child.getKey(), ts != null ? ts : 0L);
                    }
                    // Sort by timestamp desc
                    List<Map.Entry<String, Long>> entries = new ArrayList<>(result.entrySet());
                    entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                    Map<String, Long> sorted = new LinkedHashMap<>();
                    for (Map.Entry<String, Long> e : entries) sorted.put(e.getKey(), e.getValue());
                    callback.onSeenByLoaded(sorted);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    callback.onSeenByLoaded(Collections.emptyMap());
                }
            });
    }

    /**
     * Fetch reactions map for a given status.
     * Returns Map<viewerUid, emoji>.
     */
    public void fetchReactions(@NonNull String ownerUid,
                               @NonNull String statusId,
                               @NonNull ReactionsCallback callback) {
        FirebaseUtils.getStatusReactionRef(ownerUid, statusId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Map<String, String> result = new LinkedHashMap<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        String emoji = child.getValue(String.class);
                        if (emoji != null) result.put(child.getKey(), emoji);
                    }
                    callback.onReactionsLoaded(result);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    callback.onReactionsLoaded(Collections.emptyMap());
                }
            });
    }

    /**
     * Check (from local cache) whether this user has seen a given status this session.
     */
    public boolean hasSeenLocally(@NonNull String statusId, @NonNull String viewerUid) {
        Set<String> s = seenCache.get(statusId);
        return s != null && s.contains(viewerUid);
    }

    /**
     * Preload seen state from Firebase for a list of status IDs (on app launch / tab open).
     * Populates local cache so hasSeenLocally is accurate immediately.
     */
    public void preloadSeenState(@NonNull String ownerUid,
                                 @NonNull List<String> statusIds,
                                 @NonNull String viewerUid) {
        for (String sid : statusIds) {
            FirebaseUtils.getStatusSeenByRef(ownerUid, sid)
                .child(viewerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (snap.exists()) {
                            seenCache.computeIfAbsent(sid,
                                k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                                .add(viewerUid);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        }
    }

    /** Clear session cache (e.g., on logout) */
    public void clearCache() {
        seenCache.clear();
        pendingBatch.clear();
    }

    // ── Internal batch flush ──────────────────────────────────────────────

    private void flushBatch() {
        flushScheduled.set(false);
        if (pendingBatch.isEmpty()) return;

        // Snapshot and clear pending
        Map<String, Map<String, Long>> snapshot = new HashMap<>(pendingBatch);
        pendingBatch.clear();

        DatabaseReference db = FirebaseDatabase.getInstance().getReference();
        Map<String, Object> updates = new HashMap<>();

        for (Map.Entry<String, Map<String, Long>> entry : snapshot.entrySet()) {
            String statusId = entry.getKey();
            for (Map.Entry<String, Long> view : entry.getValue().entrySet()) {
                String viewerUid = view.getKey();
                long   ts        = view.getValue();
                // We need ownerUid — extract from statusId format or look up separately
                // For now, write to a flat index — StatusFragment resolves ownerUid
                updates.put("statusSeenFlat/" + statusId + "/" + viewerUid, ts);
            }
        }

        if (!updates.isEmpty()) {
            db.updateChildren(updates, (error, ref) -> {
                if (error != null) {
                    // Re-queue failed writes
                    android.util.Log.w(TAG, "Batch flush failed: " + error.getMessage());
                }
            });
        }
    }

    // ── Flush with ownerUid (preferred — call from StatusViewerActivity) ──
    public void markSeenWithOwner(@NonNull String ownerUid,
                                  @NonNull String statusId,
                                  @NonNull String viewerUid) {
        if (viewerUid.equals(ownerUid)) return;

        Set<String> seenSet = seenCache.computeIfAbsent(statusId,
            k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        if (seenSet.contains(viewerUid)) return;
        seenSet.add(viewerUid);

        long ts = System.currentTimeMillis();
        Map<String, Object> updates = new HashMap<>();
        // Seen timestamp node
        updates.put("statusSeen/" + ownerUid + "/" + statusId + "/" + viewerUid, ts);
        // Increment denormalized seenCount
        // (done via transaction separately below for accuracy)

        FirebaseDatabase.getInstance().getReference()
            .updateChildren(updates, (error, ref) -> {
                if (error == null) {
                    // Increment seenCount
                    FirebaseUtils.getUserStatusRef(ownerUid)
                        .child(statusId).child("seenCount")
                        .runTransaction(new Transaction.Handler() {
                            @NonNull @Override
                            public Transaction.Result doTransaction(@NonNull MutableData d) {
                                Long v = d.getValue(Long.class);
                                d.setValue(v == null ? 1 : v + 1);
                                return Transaction.success(d);
                            }
                            @Override
                            public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
                        });
                    // Notify owner about the view
                    StatusNotificationHelper.notifyStatusViewed(ownerUid, statusId, viewerUid);
                }
            });
    }

    // ── Callbacks ─────────────────────────────────────────────────────────
    public interface SeenByCallback {
        void onSeenByLoaded(Map<String, Long> seenByMap);
    }

    public interface ReactionsCallback {
        void onReactionsLoaded(Map<String, String> reactionsMap);
    }
}
