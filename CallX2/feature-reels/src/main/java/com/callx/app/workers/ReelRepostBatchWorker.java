package com.callx.app.workers;

import com.callx.app.reels.R;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.*;
import com.callx.app.notifications.ReelNotificationsActivity;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.notifications.ReelNotificationChannelManager;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.concurrent.TimeUnit;

/**
 * ReelRepostBatchWorker — Batches multiple reposts into ONE grouped notification.
 *
 * Problem: 10 people repost a reel in 30 seconds → 10 separate push notifications.
 * Solution: Each repost enqueues this worker with a 30s delay + REPLACE policy.
 * The delay resets on each new repost. Only after 30s of silence does the
 * notification fire, showing "N people reposted your reel" with the exact count.
 *
 * Firebase read:  reelReposts/{reelId} — count children for accurate total
 * Notification:  Shows count-aware copy + "View Reposters" action button
 *
 * Usage (after each repost):
 *   ReelRepostBatchWorker.enqueue(ctx, reelId, ownerUid, thumbUrl);
 */
public class ReelRepostBatchWorker extends Worker {

    public static final String KEY_REEL_ID   = "batch_reel_id";
    public static final String KEY_OWNER_UID = "batch_owner_uid";
    public static final String KEY_THUMB_URL = "batch_thumb_url";

    public ReelRepostBatchWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    @NonNull @Override
    public Result doWork() {
        String reelId   = getInputData().getString(KEY_REEL_ID);
        String ownerUid = getInputData().getString(KEY_OWNER_UID);
        if (reelId == null || ownerUid == null) return Result.failure();

        try {
            final long[] countHolder = {0};
            final boolean[] done     = {false};

            // Count total reposters via Firebase
            FirebaseUtils.db().getReference("reelReposts").child(reelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        countHolder[0] = snap.getChildrenCount();
                        done[0] = true;
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { done[0] = true; }
                });

            // Poll up to 5s for Firebase
            long waited = 0;
            while (!done[0] && waited < 5000) { Thread.sleep(100); waited += 100; }

            long n = countHolder[0];
            if (n == 0) return Result.success();

            String title = buildTitle(n);
            String body  = buildBody(n);
            Context ctx = getApplicationContext();

            Intent tapIntent = new Intent(ctx, SingleReelPlayerActivity.class);
            tapIntent.putExtra("reel_id", reelId);
            tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent tapPi = PendingIntent.getActivity(ctx,
                ("batch_tap_" + reelId).hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent allIntent = new Intent(ctx, ReelNotificationsActivity.class);
            allIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent allPi = PendingIntent.getActivity(ctx,
                ("batch_all_" + reelId).hashCode(), allIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx,
                    ReelNotificationChannelManager.CHANNEL_REEL_REPOSTS)
                .setSmallIcon(R.drawable.ic_repost)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(tapPi)
                .setColor(0xFF4CAF50)
                .addAction(R.drawable.ic_repost, "View Reposters", tapPi)
                .addAction(R.drawable.ic_repost, "All Activity", allPi)
                .setGroup("repost_group_" + reelId)
                .setGroupSummary(true);

            NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(("batch_notif_" + reelId).hashCode(), nb.build());

            return Result.success();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private static String buildTitle(long n) {
        if (n == 1)  return "1 person reposted your reel \uD83D\uDD01";
        if (n <= 5)  return n + " people reposted your reel \uD83D\uDD01";
        if (n <= 20) return n + " people reposted your reel \uD83D\uDD25";
        return n + " people reposted your reel \uD83D\uDE80";
    }

    private static String buildBody(long n) {
        if (n == 1)  return "Tap to see who reposted it";
        if (n <= 5)  return "Your reel is getting shared!";
        if (n <= 20) return "Your reel is going viral!";
        return "Your reel is blowing up \u2014 " + n + " reposts!";
    }

    /**
     * Enqueue with 30s debounce delay.
     * REPLACE policy resets the 30s window on each new repost — notification
     * only fires after 30s of no new repost activity.
     */
    public static void enqueue(@NonNull Context ctx,
                                @NonNull String reelId,
                                @NonNull String ownerUid,
                                String thumbUrl) {
        Data data = new Data.Builder()
            .putString(KEY_REEL_ID,   reelId)
            .putString(KEY_OWNER_UID, ownerUid)
            .putString(KEY_THUMB_URL, thumbUrl != null ? thumbUrl : "")
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ReelRepostBatchWorker.class)
            .setInputData(data)
            .setInitialDelay(30, TimeUnit.SECONDS)
            .addTag("repost_batch_" + reelId)
            .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "repost_batch_owner_" + ownerUid + "_" + reelId,
            ExistingWorkPolicy.REPLACE,
            req);
    }
}
