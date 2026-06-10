package com.callx.app.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.*;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DuetNotificationWorker — WorkManager job that notifies the original reel
 * creator when someone duets their reel.
 *
 * Mirrors the pattern used by ReelRepostWorker for reliability:
 *  - Enqueued immediately after duet is recorded
 *  - Survives process death / background kills
 *  - Writes a notification node under notifications/{ownerUid}
 *  - Respects duplicate suppression (one duet notification per user per reel)
 *
 * Firebase notification node written:
 *   notifications/{ownerUid}/{notifId}
 *     type:       "duet"
 *     reelId:     "{originalReelId}"
 *     fromUid:    "{dueterUid}"
 *     fromName:   "{dueterName}"
 *     timestamp:  ServerValue.TIMESTAMP
 *     seen:       false
 */
public class DuetNotificationWorker extends Worker {

    private static final String TAG = "DuetNotifWorker";

    static final String KEY_REEL_ID    = "reel_id";
    static final String KEY_FROM_UID   = "from_uid";
    static final String KEY_FROM_NAME  = "from_name";
    static final String KEY_OWNER_UID  = "owner_uid";

    public DuetNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String reelId   = getInputData().getString(KEY_REEL_ID);
        String fromUid  = getInputData().getString(KEY_FROM_UID);
        String fromName = getInputData().getString(KEY_FROM_NAME);
        String ownerUid = getInputData().getString(KEY_OWNER_UID);

        if (reelId == null || fromUid == null || ownerUid == null) {
            Log.w(TAG, "Missing required data — skipping");
            return Result.failure();
        }

        // Don't notify self
        if (fromUid.equals(ownerUid)) return Result.success();

        try {
            String notifId = "duet_" + fromUid + "_" + reelId;
            DatabaseReference notifRef = FirebaseUtils.db()
                .getReference("notifications")
                .child(ownerUid)
                .child(notifId);

            Map<String, Object> notif = new HashMap<>();
            notif.put("type",      "duet");
            notif.put("reelId",    reelId);
            notif.put("fromUid",   fromUid);
            notif.put("fromName",  fromName != null ? fromName : "Someone");
            notif.put("timestamp", ServerValue.TIMESTAMP);
            notif.put("seen",      false);

            // Synchronous-style write using Task blocking (WorkManager runs on background thread)
            final boolean[] done = {false};
            final boolean[] ok   = {false};

            notifRef.setValue(notif).addOnSuccessListener(v -> {
                ok[0] = true; done[0] = true;
            }).addOnFailureListener(e -> {
                Log.e(TAG, "Firebase write failed: " + e.getMessage());
                done[0] = true;
            });

            // Busy-wait up to 8 seconds for Firebase callback
            long deadline = System.currentTimeMillis() + 8_000;
            while (!done[0] && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            if (!ok[0]) {
                Log.w(TAG, "Firebase write timed out or failed — retrying");
                return Result.retry();
            }

            Log.i(TAG, "Duet notification written → notifications/" + ownerUid + "/" + notifId);
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "doWork() exception: " + e.getMessage(), e);
            return Result.retry();
        }
    }

    /**
     * Enqueue a DuetNotificationWorker.
     * Safe to call from the main thread — WorkManager handles the rest.
     *
     * @param context    Application or Activity context
     * @param reelId     ID of the original reel that was dueted
     * @param fromUid    UID of the user who made the duet
     * @param fromName   Display name of the user who made the duet
     * @param ownerUid   UID of the original reel's creator (notification recipient)
     */
    public static void enqueue(Context context,
                               String reelId,
                               String fromUid,
                               String fromName,
                               String ownerUid) {
        Data data = new Data.Builder()
            .putString(KEY_REEL_ID,   reelId)
            .putString(KEY_FROM_UID,  fromUid)
            .putString(KEY_FROM_NAME, fromName != null ? fromName : "Someone")
            .putString(KEY_OWNER_UID, ownerUid)
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DuetNotificationWorker.class)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build();

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "duet_notif_" + fromUid + "_" + reelId,
                ExistingWorkPolicy.KEEP,
                req);
    }
}
