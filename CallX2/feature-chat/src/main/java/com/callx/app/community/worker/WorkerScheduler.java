package com.callx.app.community.worker;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * v31: Helper to enqueue the scheduled-post publishing worker at startup.
 *
 * Call WorkerScheduler.schedule(ctx) from your Application.onCreate() or
 * MainActivity.onCreate() once:
 *
 *   WorkerScheduler.schedule(this);
 */
public final class WorkerScheduler {

    private static final String WORK_NAME_SCHEDULED_POSTS = "community_scheduled_post_publisher";

    private WorkerScheduler() {}

    public static void schedule(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                ScheduledPostPublishWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        WORK_NAME_SCHEDULED_POSTS,
                        ExistingPeriodicWorkPolicy.KEEP,
                        work);
    }
}
