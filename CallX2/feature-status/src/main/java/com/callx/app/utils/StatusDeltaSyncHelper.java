package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.callx.app.models.StatusItem;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * StatusDeltaSyncHelper v26 — Fetch only NEW statuses since last sync.
 * Uses Firebase orderByChild("timestamp").startAfter(lastSyncTs).
 * Reduces data usage vs full re-fetch on every open.
 */
public final class StatusDeltaSyncHelper {
    private static final String PREFS      = "status_delta_sync";
    private static final String KEY_LAST   = "last_sync_ts";
    private StatusDeltaSyncHelper() {}

    public interface DeltaCallback {
        void onNewStatuses(List<StatusItem> items);
        void onError(String msg);
    }

    public static void fetchDelta(Context ctx, String ownerUid, DeltaCallback cb) {
        if (ownerUid == null || ctx == null) return;
        long lastTs = getLastSyncTs(ctx, ownerUid);
        Query query = FirebaseUtils.getStatusRef()
                .child(ownerUid)
                .orderByChild("timestamp")
                .startAfter(lastTs);
        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                List<StatusItem> newItems = new ArrayList<>();
                long latest = lastTs;
                for (DataSnapshot c : snap.getChildren()) {
                    StatusItem item = c.getValue(StatusItem.class);
                    if (item == null || item.deleted) continue;
                    newItems.add(item);
                    if (item.timestamp != null && item.timestamp > latest) latest = item.timestamp;
                }
                if (latest > lastTs) saveLastSyncTs(ctx, ownerUid, latest);
                if (cb != null) cb.onNewStatuses(newItems);
            }
            @Override public void onCancelled(DatabaseError e) {
                if (cb != null) cb.onError(e.getMessage());
            }
        });
    }

    public static long getLastSyncTs(Context ctx, String uid) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(uid + "_ts", 0);
    }
    public static void saveLastSyncTs(Context ctx, String uid, long ts) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(uid + "_ts", ts).apply();
    }
    public static void resetSync(Context ctx, String uid) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(uid + "_ts").apply();
    }
}
