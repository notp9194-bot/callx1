package com.callx.app.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.StatusDao;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * StatusRepository — Single source of truth for Status/Stories data.
 *
 * Architecture:
 *   StatusFragment → StatusViewModel → StatusRepository → (Room + Firebase)
 *
 * Offline-first:
 *   - Room serves the UI immediately (no blank screen on app open)
 *   - Firebase listener pushes live updates → Room → LiveData → UI
 *   - Expired statuses auto-pruned from Room
 *
 * Replaces all raw Firebase + Room calls currently scattered in StatusFragment.
 */
public class StatusRepository {

    private static volatile StatusRepository sInstance;

    private final StatusDao       dao;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private StatusRepository(Context ctx) {
        this.dao = AppDatabase.getInstance(ctx).statusDao();
    }

    public static StatusRepository getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (StatusRepository.class) {
                if (sInstance == null)
                    sInstance = new StatusRepository(ctx.getApplicationContext());
            }
        }
        return sInstance;
    }

    // ── READ — Room LiveData ──────────────────────────────────────────────

    /**
     * Active (non-expired) statuses as Room LiveData.
     * UI observes this — no polling required.
     */
    public LiveData<List<StatusEntity>> getActiveStatuses() {
        return dao.getActiveStatusesLive(System.currentTimeMillis());
    }

    /** My own statuses. */
    public LiveData<List<StatusEntity>> getMyStatuses(String myUid) {
        return dao.getStatusesByOwner(myUid);
    }

    /** My scheduled (not yet published) statuses — for the "Scheduled statuses" screen. */
    public LiveData<List<StatusEntity>> getScheduledStatuses(String myUid) {
        return dao.getScheduledStatuses(myUid);
    }

    // ── WRITE — Post status ───────────────────────────────────────────────

    public interface Result { void onDone(boolean success); }

    /**
     * Post a new status: write to Firebase, then cache in Room.
     * Optimistic: insert in Room first so it shows instantly.
     */
    public void postStatus(StatusItem item, Result cb) {
        if (item.ownerUid == null || item.ownerUid.isEmpty()) {
            if (cb != null) mainHandler.post(() -> cb.onDone(false));
            return;
        }
        // Optimistic Room insert
        StatusEntity entity = itemToEntity(item);
        executor.execute(() -> dao.insertStatuses(java.util.Collections.singletonList(entity)));

        // Firebase write
        FirebaseUtils.getUserStatusRef(item.ownerUid).child(item.id)
            .setValue(item, (e, ref) -> {
                if (cb != null) mainHandler.post(() -> cb.onDone(e == null));
            });
    }

    /**
     * Delete a status: remove from Firebase + soft-remove from Room.
     */
    public void deleteStatus(String ownerUid, String statusId, Result cb) {
        executor.execute(() -> dao.pruneExpired(0)); // prune everything first
        FirebaseUtils.getUserStatusRef(ownerUid).child(statusId)
            .removeValue((e, ref) -> {
                if (cb != null) mainHandler.post(() -> cb.onDone(e == null));
            });
    }

    // ── WRITE — Scheduled status posting ────────────────────────────────

    /**
     * Schedule a status for future auto-publish: write it to the
     * statusScheduled/{ownerUid}/{id} Firebase branch (kept out of the
     * live "status" tree so nobody sees it early), then cache it locally
     * in Room (scheduledAt > 0) so the "Scheduled statuses" screen and the
     * publish worker can find it — same pattern as channel scheduled posts.
     */
    public void scheduleStatus(StatusItem item, long scheduledAt, Result cb) {
        if (item.ownerUid == null || item.ownerUid.isEmpty() || scheduledAt <= 0) {
            if (cb != null) mainHandler.post(() -> cb.onDone(false));
            return;
        }
        item.scheduledAt = scheduledAt;
        item.timestamp   = scheduledAt; // will read correctly once published

        StatusEntity entity = itemToEntity(item);
        executor.execute(() -> dao.insertStatuses(java.util.Collections.singletonList(entity)));

        FirebaseUtils.getUserStatusScheduledRef(item.ownerUid).child(item.id)
            .setValue(item.toMap(), (e, ref) -> {
                if (cb != null) mainHandler.post(() -> cb.onDone(e == null));
            });
    }

    /**
     * Publish a scheduled status right now: move it from statusScheduled →
     * status on Firebase, and flip it live in Room.
     */
    public void publishScheduledStatus(String ownerUid, String statusId, Result cb) {
        long now = System.currentTimeMillis();
        executor.execute(() -> {
            StatusEntity entity = dao.getStatusByIdSync(statusId);
            if (entity == null) { mainHandler.post(() -> { if (cb != null) cb.onDone(false); }); return; }

            java.util.Map<String, Object> updates = new java.util.HashMap<>();
            updates.put("statusScheduled/" + ownerUid + "/" + statusId, null);
            updates.put("status/" + ownerUid + "/" + statusId + "/scheduledAt", 0);
            updates.put("status/" + ownerUid + "/" + statusId + "/timestamp", now);

            // Re-read the scheduled node so the full item (media/text/privacy/etc.)
            // is copied into the live "status" tree, not just patched in place —
            // Firebase multi-path updates don't move a subtree on their own.
            FirebaseUtils.getUserStatusScheduledRef(ownerUid).child(statusId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        StatusItem item = snap.getValue(StatusItem.class);
                        if (item == null) item = entityToItem(entity);
                        item.scheduledAt = 0;
                        item.timestamp   = now;
                        java.util.Map<String, Object> finalUpdates = new java.util.HashMap<>();
                        finalUpdates.put("status/" + ownerUid + "/" + statusId, item.toMap());
                        finalUpdates.put("statusScheduled/" + ownerUid + "/" + statusId, null);
                        FirebaseUtils.db().getReference().updateChildren(finalUpdates, (e, ref) ->
                            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        mainHandler.post(() -> { if (cb != null) cb.onDone(false); });
                    }
                });

            dao.publishScheduledStatus(statusId, now);
        });
    }

    /**
     * Cancel/delete a scheduled status before it goes live.
     */
    public void deleteScheduledStatus(String ownerUid, String statusId, Result cb) {
        FirebaseUtils.getUserStatusScheduledRef(ownerUid).child(statusId)
            .removeValue((e, ref) -> {
                if (e == null) executor.execute(() -> dao.deleteScheduledStatus(statusId));
                if (cb != null) mainHandler.post(() -> cb.onDone(e == null));
            });
    }

    // ── SYNC — Pull from Firebase ─────────────────────────────────────────

    /**
     * Sync statuses for a set of UIDs from Firebase into Room.
     * Call from StatusViewModel during startup / tab-visible.
     */
    public void syncStatuses(List<String> uids) {
        if (uids == null || uids.isEmpty()) return;
        long now = System.currentTimeMillis();

        for (String uid : uids) {
            FirebaseUtils.getUserStatusRef(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        List<StatusEntity> toInsert = new ArrayList<>();
                        for (DataSnapshot ds : snap.getChildren()) {
                            StatusItem item = ds.getValue(StatusItem.class);
                            if (item == null || Boolean.TRUE.equals(item.deleted)) continue;
                            if (item.expiresAt != null && item.expiresAt < now) continue;
                            item.ownerUid = uid;
                            if (item.id == null) item.id = ds.getKey();
                            toInsert.add(itemToEntity(item));
                        }
                        if (!toInsert.isEmpty())
                            executor.execute(() -> dao.insertStatuses(toInsert));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        }
    }

    // ── MAINTENANCE ───────────────────────────────────────────────────────

    /** Prune expired statuses — safe to call from any thread. */
    public void pruneExpired() {
        executor.execute(() -> dao.pruneExpired(System.currentTimeMillis()));
    }

    // ── Mark seen ────────────────────────────────────────────────────────

    public void markSeen(String viewerUid, String ownerUid, String statusId) {
        // Firebase seen record
        FirebaseUtils.getStatusSeenRef(viewerUid)
            .child(ownerUid).child(statusId).setValue(true);
        // seenBy on the status itself
        FirebaseUtils.getStatusSeenByRef(ownerUid, statusId)
            .child(viewerUid).setValue(System.currentTimeMillis());
    }

    public void addReaction(String reactorUid, String ownerUid, String statusId, String emoji) {
        FirebaseUtils.getStatusReactionRef(ownerUid, statusId, reactorUid).setValue(emoji);
    }

    // ── Sync — scheduled statuses ───────────────────────────────────────

    /**
     * Pull my own scheduled statuses from Firebase into Room. Call from
     * StatusViewModel on startup / whenever the Scheduled screen is opened
     * so a status scheduled on another device still shows up here.
     */
    public void syncScheduledStatuses(String myUid) {
        if (myUid == null || myUid.isEmpty()) return;
        FirebaseUtils.getUserStatusScheduledRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<StatusEntity> toInsert = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        StatusItem item = ds.getValue(StatusItem.class);
                        if (item == null) continue;
                        item.ownerUid = myUid;
                        if (item.id == null) item.id = ds.getKey();
                        toInsert.add(itemToEntity(item));
                    }
                    if (!toInsert.isEmpty())
                        executor.execute(() -> dao.insertStatuses(toInsert));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Converter ─────────────────────────────────────────────────────────

    private StatusEntity itemToEntity(StatusItem si) {
        StatusEntity e = new StatusEntity();
        e.id          = si.id != null ? si.id : "";
        e.ownerUid    = si.ownerUid;
        e.ownerName   = si.ownerName;
        e.ownerPhoto  = si.ownerPhoto;
        e.type        = si.type;
        e.text        = si.text;
        e.mediaUrl    = si.mediaUrl;
        e.thumbnailUrl= si.thumbnailUrl;
        e.bgColor     = si.bgColor;
        e.fontStyle   = si.fontStyle;
        e.textColor   = si.textColor;
        e.timestamp   = si.timestamp;
        e.expiresAt   = si.expiresAt;
        e.deleted     = si.deleted;
        e.scheduledAt = si.scheduledAt;
        return e;
    }

    private StatusItem entityToItem(StatusEntity e) {
        StatusItem si    = new StatusItem();
        si.id             = e.id;
        si.ownerUid       = e.ownerUid;
        si.ownerName      = e.ownerName;
        si.ownerPhoto     = e.ownerPhoto;
        si.type           = e.type;
        si.text           = e.text;
        si.mediaUrl       = e.mediaUrl;
        si.thumbnailUrl   = e.thumbnailUrl;
        si.bgColor        = e.bgColor;
        si.fontStyle      = e.fontStyle;
        si.textColor      = e.textColor;
        si.timestamp      = e.timestamp;
        si.expiresAt      = e.expiresAt;
        si.deleted        = e.deleted;
        si.scheduledAt    = e.scheduledAt;
        return si;
    }
}
