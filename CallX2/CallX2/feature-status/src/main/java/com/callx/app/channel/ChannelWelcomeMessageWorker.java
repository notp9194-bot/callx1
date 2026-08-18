package com.callx.app.channel;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.callx.app.repository.ChannelRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ChannelWelcomeMessageWorker — background WorkManager task that sends a welcome DM
 * to a newly-followed user if the channel has an auto-reply message configured.
 *
 * Enqueued by: ChannelRepository.followChannel() when followChannel succeeds.
 *
 * Input data:
 *   - channelId     : the channel being followed
 *   - newFollowerUid: the UID of the new follower to message
 *
 * Network policy: CONNECTED (retries automatically if offline).
 * Backoff: LINEAR, 30 seconds.
 */
public class ChannelWelcomeMessageWorker extends Worker {

    public static final String KEY_CHANNEL_ID       = "channelId";
    public static final String KEY_NEW_FOLLOWER_UID = "newFollowerUid";

    public ChannelWelcomeMessageWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull @Override
    public Result doWork() {
        String channelId       = getInputData().getString(KEY_CHANNEL_ID);
        String newFollowerUid  = getInputData().getString(KEY_NEW_FOLLOWER_UID);

        if (channelId == null || newFollowerUid == null) return Result.failure();

        ChannelRepository repo = ChannelRepository.getInstance(getApplicationContext());
        CountDownLatch latch   = new CountDownLatch(1);
        final boolean[] success = {false};

        repo.getWelcomeMessage(channelId, msg -> {
            if (msg == null || msg.isEmpty()) {
                // No welcome message configured — nothing to do
                success[0] = true;
                latch.countDown();
                return;
            }
            repo.sendWelcomeDm(channelId, newFollowerUid, msg, ok -> {
                success[0] = ok;
                latch.countDown();
            });
        });

        try { latch.await(15, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return Result.retry(); }

        return success[0] ? Result.success() : Result.retry();
    }

    /** Static helper to enqueue this worker. Call from ChannelRepository after followChannel(). */
    public static void enqueue(Context context, String channelId, String newFollowerUid) {
        Data data = new Data.Builder()
            .putString(KEY_CHANNEL_ID, channelId)
            .putString(KEY_NEW_FOLLOWER_UID, newFollowerUid)
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ChannelWelcomeMessageWorker.class)
            .setInputData(data)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag("channel_welcome_" + channelId)
            .build();

        WorkManager.getInstance(context).enqueue(req);
    }
}
