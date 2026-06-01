package com.callx.app.repository;

import android.content.Context;
import android.util.Log;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.CallLogDao;
import com.callx.app.db.entity.CallLogEntity;
import com.callx.app.models.CallLog;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CallLogRepository — Offline-first call log sync.
 *
 * Strategy:
 *  1. Serve local Room cache immediately (zero-latency)
 *  2. Fetch Firebase in background, merge & save
 *  3. Write always hits Room first (optimistic local write)
 *     then Firebase (eventual consistency)
 *
 * Public API:
 *   loadLogs(uid, callback)         → offline-first load
 *   insertLog(entity)               → local write only (e.g. on call end)
 *   syncFromFirebase(uid, callback) → pull from Firebase, save to Room
 *   deleteLog(uid, logId)           → delete from both stores
 */
public class CallLogRepository {

    private static final String TAG = "CallLogRepository";

    private static volatile CallLogRepository sInstance;

    private final CallLogDao     dao;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    public interface LoadCallback {
        void onLoaded(List<CallLog> logs);
    }

    private CallLogRepository(Context context) {
        dao = AppDatabase.getInstance(context.getApplicationContext()).callLogDao();
    }

    public static CallLogRepository getInstance(Context context) {
        if (sInstance == null) {
            synchronized (CallLogRepository.class) {
                if (sInstance == null) sInstance = new CallLogRepository(context);
            }
        }
        return sInstance;
    }

    // ── Load (offline-first) ──────────────────────────────────────────────

    /**
     * Returns locally cached logs first via callback,
     * then kicks off a Firebase sync in the background.
     */
    public void loadLogs(String uid, LoadCallback callback) {
        if (uid == null || uid.isEmpty()) return;
        exec.execute(() -> {
            List<CallLogEntity> entities = dao.getAllCallLogsSync();
            List<CallLog>       cached  = toModelList(entities);
            if (callback != null) mainThread(callback, cached);
            // Fire-and-forget Firebase sync
            syncFromFirebase(uid, synced -> {
                if (callback != null && !synced.isEmpty()) mainThread(callback, synced);
            });
        });
    }

    // ── Write ─────────────────────────────────────────────────────────────

    /** Insert or replace a single call log into Room (background thread safe). */
    public void insertLog(CallLogEntity entity) {
        exec.execute(() -> {
            try {
                if (entity.id == null || entity.id.isEmpty())
                    entity.id = UUID.randomUUID().toString();
                dao.insertCallLog(entity);
            } catch (Exception e) {
                Log.w(TAG, "insertLog: " + e.getMessage());
            }
        });
    }

    /** Delete a log from Room AND Firebase. */
    public void deleteLog(String uid, String logId) {
        if (uid == null || logId == null) return;
        exec.execute(() -> {
            try { dao.deleteCallLog(logId); } catch (Exception e) {
                Log.w(TAG, "deleteLog Room: " + e.getMessage());
            }
        });
        try {
            FirebaseUtils.getCallsRef(uid).child(logId).removeValue();
        } catch (Exception e) {
            Log.w(TAG, "deleteLog Firebase: " + e.getMessage());
        }
    }

    // ── Firebase sync ─────────────────────────────────────────────────────

    public void syncFromFirebase(String uid, LoadCallback callback) {
        if (uid == null || uid.isEmpty()) return;
        try {
            FirebaseUtils.getCallsRef(uid)
                .orderByChild("timestamp")
                .limitToLast(200)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        List<CallLogEntity> toSave = new ArrayList<>();
                        List<CallLog>       models = new ArrayList<>();
                        for (DataSnapshot child : snap.getChildren()) {
                            try {
                                CallLogEntity e = new CallLogEntity();
                                e.id          = child.getKey();
                                e.partnerUid  = child.child("partnerUid").getValue(String.class);
                                e.partnerName = child.child("partnerName").getValue(String.class);
                                e.direction   = child.child("direction").getValue(String.class);
                                e.mediaType   = child.child("mediaType").getValue(String.class);
                                Long ts  = child.child("timestamp").getValue(Long.class);
                                Long dur = child.child("duration").getValue(Long.class);
                                e.timestamp = ts  != null ? ts  : 0L;
                                e.duration  = dur != null ? dur : 0L;
                                toSave.add(e);
                                models.add(toModel(e));
                            } catch (Exception ex) {
                                Log.w(TAG, "parse log: " + ex.getMessage());
                            }
                        }
                        exec.execute(() -> {
                            try { if (!toSave.isEmpty()) dao.insertCallLogs(toSave); }
                            catch (Exception ex) { Log.w(TAG, "save sync: " + ex.getMessage()); }
                        });
                        if (callback != null) mainThread(callback, models);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        Log.w(TAG, "Firebase sync cancelled: " + e.getMessage());
                    }
                });
        } catch (Exception e) {
            Log.w(TAG, "syncFromFirebase: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private List<CallLog> toModelList(List<CallLogEntity> entities) {
        List<CallLog> list = new ArrayList<>();
        for (CallLogEntity e : entities) list.add(toModel(e));
        return list;
    }

    private CallLog toModel(CallLogEntity e) {
        CallLog m = new CallLog();
        m.id          = e.id;
        m.partnerUid  = e.partnerUid;
        m.partnerName = e.partnerName;
        m.direction   = e.direction;
        m.mediaType   = e.mediaType;
        m.timestamp   = e.timestamp;
        m.duration    = e.duration;
        return m;
    }

    private void mainThread(LoadCallback cb, List<CallLog> data) {
        new android.os.Handler(android.os.Looper.getMainLooper())
            .post(() -> cb.onLoaded(data));
    }
}
