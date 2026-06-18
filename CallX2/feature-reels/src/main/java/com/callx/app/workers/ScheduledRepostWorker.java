package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.callx.app.repost.RepostManager;
import java.util.concurrent.TimeUnit;

/**
 * ScheduledRepostWorker — repost a reel at a scheduled future time.
 * Enqueued with an initial delay computed from scheduledAt timestamp.
 *
 * Usage:
 *   long delayMs = scheduledAt - System.currentTimeMillis();
 *   ScheduledRepostWorker.schedule(ctx, reelId, ownerUid, caption, type, delayMs);
 */
public class ScheduledRepostWorker extends Worker {

    private static final String KEY_REEL_ID    = "reelId";
    private static final String KEY_OWNER_UID  = "ownerUid";
    private static final String KEY_FROM_UID   = "fromUid";
    private static final String KEY_FROM_NAME  = "fromName";
    private static final String KEY_FROM_PHOTO = "fromPhoto";
    private static final String KEY_CAPTION    = "caption";
    private static final String KEY_TYPE       = "type";
    private static final String KEY_REEL_THUMB = "reelThumb";

    public ScheduledRepostWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    public static void schedule(Context ctx, String reelId, String ownerUid,
                                String fromUid, String fromName, String fromPhoto,
                                String caption, String type, String reelThumb,
                                long delayMs) {
        if (delayMs <= 0) delayMs = 1000;

        Data data = new Data.Builder()
            .putString(KEY_REEL_ID,    reelId)
            .putString(KEY_OWNER_UID,  ownerUid)
            .putString(KEY_FROM_UID,   fromUid)
            .putString(KEY_FROM_NAME,  fromName)
            .putString(KEY_FROM_PHOTO, fromPhoto != null ? fromPhoto : "")
            .putString(KEY_CAPTION,    caption   != null ? caption   : "")
            .putString(KEY_TYPE,       type      != null ? type      : "simple")
            .putString(KEY_REEL_THUMB, reelThumb != null ? reelThumb : "")
            .build();

        String tag = "scheduled_repost_" + reelId;
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ScheduledRepostWorker.class)
            .setInputData(data)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(tag)
            .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            tag, ExistingWorkPolicy.REPLACE, req);
    }

    public static void cancel(Context ctx, String reelId) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag("scheduled_repost_" + reelId);
    }

    @NonNull @Override
    public Result doWork() {
        String reelId    = getInputData().getString(KEY_REEL_ID);
        String ownerUid  = getInputData().getString(KEY_OWNER_UID);
        String fromUid   = getInputData().getString(KEY_FROM_UID);
        String fromName  = getInputData().getString(KEY_FROM_NAME);
        String fromPhoto = getInputData().getString(KEY_FROM_PHOTO);
        String caption   = getInputData().getString(KEY_CAPTION);
        String type      = getInputData().getString(KEY_TYPE);
        String reelThumb = getInputData().getString(KEY_REEL_THUMB);

        RepostManager mgr = new RepostManager(fromUid, fromName, fromPhoto);
        final boolean[] done = {false};
        mgr.doRepost(reelId, ownerUid, caption, type, (ok, err) -> {
            done[0] = true;
            if (ok) {
                RepostNotificationWorker.enqueue(getApplicationContext(),
                        reelId, ownerUid, fromUid, fromName, fromPhoto, reelThumb, caption);
            }
        });
        // Wait briefly for async Firebase
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        return Result.success();
    }
}
