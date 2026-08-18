package com.callx.app.services;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.StatusDao;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * StatusScheduledPostWorker — WorkManager Worker that auto-publishes scheduled
 * statuses when their scheduled time has arrived.
 *
 * Runs as a PeriodicWorkRequest every 15 minutes (same cadence as
 * ChannelScheduledPostWorker). For each overdue scheduled status found in the
 * local Room cache (only the signed-in user's own scheduled statuses are ever
 * cached, so this is safe to run per-device):
 *   1. Copies the full item from statusScheduled/{uid}/{id} to status/{uid}/{id}
 *      on Firebase (clearing scheduledAt, stamping the real publish timestamp)
 *   2. Removes the statusScheduled/{uid}/{id} node
 *   3. Flips the row live in Room via StatusDao.publishScheduledStatus()
 *
 * Register on app start:
 *   StatusScheduledPostWorker.schedulePeriodicWork(context);
 */
public class StatusScheduledPostWorker extends Worker {

    private static final String TAG = "status_scheduled_posts";

    public StatusScheduledPostWorker(@NonNull Context context,
                                      @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            StatusDao dao = AppDatabase.getInstance(getApplicationContext()).statusDao();
            long now = System.currentTimeMillis();

            List<StatusEntity> due = dao.getStatusesDueForPublishing(now);
            if (due == null || due.isEmpty()) return Result.success();

            for (StatusEntity status : due) {
                publishStatus(status, now);
                // Flip Room live immediately — Firebase write above is async,
                // but Room is the source of truth the UI observes.
                dao.publishScheduledStatus(status.id, now);
            }

            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private void publishStatus(StatusEntity status, long now) {
        if (status.ownerUid == null || status.id == null) return;

        // Block briefly for the single-value read so the batch of Firebase
        // writes this worker run makes all land before doWork() returns —
        // WorkManager expects a synchronous result from doWork().
        CountDownLatch latch = new CountDownLatch(1);

        FirebaseUtils.getUserStatusScheduledRef(status.ownerUid).child(status.id)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    StatusItem item = snap.getValue(StatusItem.class);
                    if (item == null) { latch.countDown(); return; }
                    item.scheduledAt = 0;
                    item.timestamp   = now;

                    Map<String, Object> updates = new HashMap<>();
                    updates.put("status/" + status.ownerUid + "/" + status.id, item.toMap());
                    updates.put("statusScheduled/" + status.ownerUid + "/" + status.id, null);

                    FirebaseUtils.db().getReference().updateChildren(updates, (err, ref) -> {
                        if (err != null) {
                            android.util.Log.w("StatusScheduledWorker",
                                "Failed to publish scheduled status " + status.id + ": " + err.getMessage());
                        }
                        latch.countDown();
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { latch.countDown(); }
            });

        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    // ── Static helpers ────────────────────────────────────────────────────

    /**
     * Schedule periodic work to auto-publish scheduled statuses.
     * Safe to call multiple times — uses KEEP policy to avoid duplicates.
     * Call this from Application.onCreate().
     */
    public static void schedulePeriodicWork(@NonNull Context ctx) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                StatusScheduledPostWorker.class,
                15, TimeUnit.MINUTES)
                .addTag(TAG)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();

        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        TAG,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request);
    }

    /** Cancel the periodic work (e.g., when user signs out). */
    public static void cancelWork(@NonNull Context ctx) {
        WorkManager.getInstance(ctx.getApplicationContext()).cancelAllWorkByTag(TAG);
    }
}
