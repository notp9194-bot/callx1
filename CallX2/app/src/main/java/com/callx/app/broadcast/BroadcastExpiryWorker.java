package com.callx.app.broadcast;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;

import java.util.concurrent.TimeUnit;

/**
 * BroadcastExpiryWorker — periodic background job that hard-deletes broadcast
 * messages whose {@link BroadcastMessage#expiresAt} has passed.
 *
 * Runs every 6 hours when the device has network. Scans
 * broadcast_messages/{myUid}/{listId}/{msgId} for any node where
 * expiresAt > 0 AND expiresAt <= now, then removes the entire node.
 *
 * Schedule: call {@link #schedule(Context)} once on app start (e.g. MainActivity).
 * WorkManager de-duplicates the periodic job so it is safe to call on every launch.
 */
public class BroadcastExpiryWorker extends Worker {

    private static final String TAG       = "BroadcastExpiry";
    private static final String WORK_NAME = "broadcast_expiry_sweep";

    public BroadcastExpiryWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    /** Schedule the periodic expiry sweep (safe to call on every app start). */
    public static void schedule(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                BroadcastExpiryWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) return Result.success(); // not signed in

        long now = System.currentTimeMillis();
        try {
            // Scan all lists owned by this user
            DataSnapshot listsSnap = Tasks.await(
                    FirebaseDatabase.getInstance()
                            .getReference("broadcast_messages").child(myUid)
                            .get(), 30, TimeUnit.SECONDS);

            int deleted = 0;
            for (DataSnapshot listSnap : listsSnap.getChildren()) {
                String listId = listSnap.getKey();
                if (listId == null) continue;

                for (DataSnapshot msgSnap : listSnap.getChildren()) {
                    Long expiresAt = msgSnap.child("expiresAt").getValue(Long.class);
                    if (expiresAt != null && expiresAt > 0 && now >= expiresAt) {
                        // Delete expired message node
                        msgSnap.getRef().removeValue();
                        deleted++;
                        Log.d(TAG, "Deleted expired message " + msgSnap.getKey()
                                + " from list " + listId);
                    }
                }
            }
            Log.d(TAG, "Expiry sweep complete — deleted " + deleted + " expired messages");
            return Result.success();

        } catch (Exception e) {
            Log.w(TAG, "Expiry sweep failed: " + e.getMessage());
            return Result.retry();
        }
    }
}
