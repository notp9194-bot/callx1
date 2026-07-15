package com.callx.app.community.worker;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.repository.CommunityRepository;

/**
 * v31: WorkManager Worker that publishes any community scheduled posts whose
 * scheduledAt time has passed.
 *
 * Scheduling: enqueued as a periodic 15-minute task from the app's main
 * Application class or Activity:
 *
 *   PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
 *           ScheduledPostPublishWorker.class, 15, TimeUnit.MINUTES)
 *           .build();
 *   WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
 *           "community_scheduled_posts",
 *           ExistingPeriodicWorkPolicy.KEEP,
 *           work);
 */
public class ScheduledPostPublishWorker extends Worker {

    private static final String TAG = "ScheduledPostWorker";

    public ScheduledPostPublishWorker(@NonNull Context context,
                                      @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Checking for due scheduled posts…");
        try {
            CommunityRepository repo = CommunityRepository.getInstance(getApplicationContext());
            repo.publishDueScheduledPosts();
            Log.d(TAG, "publishDueScheduledPosts completed");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error publishing scheduled posts", e);
            return Result.retry();
        }
    }
}
