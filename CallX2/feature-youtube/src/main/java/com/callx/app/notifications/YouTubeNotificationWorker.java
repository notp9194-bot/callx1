package com.callx.app.notifications;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.concurrent.TimeUnit;

/**
 * YouTubeNotificationWorker — Background WorkManager worker.
 *
 * Polls Firebase every 15 minutes for unread YouTube notifications and posts
 * OS notifications even when the app is fully killed.
 * Mirrors the pattern used by XNotificationWorker and ReelNotificationWorker.
 */
public class YouTubeNotificationWorker extends Worker {

    private static final String WORK_TAG = "yt_notification_poll";

    public YouTubeNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    /** Schedule (or keep existing schedule) for the periodic poll. */
    public static void schedule(Context ctx) {
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                YouTubeNotificationWorker.class, 15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build();

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            req);
    }

    /** Cancel any running schedule (e.g. after sign-out). */
    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(WORK_TAG);
    }

    @NonNull @Override
    public Result doWork() {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid == null) return Result.success();

        final Object lock = new Object();
        final boolean[] done = {false};

        YouTubeFirebaseUtils.notificationsRef(uid)
            .orderByChild("notified")
            .equalTo(false)
            .limitToLast(20)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ds : snap.getChildren()) {
                        String type        = ds.child("type").getValue(String.class);
                        String fromName    = ds.child("fromName").getValue(String.class);
                        String fromPhoto   = ds.child("fromPhotoUrl").getValue(String.class);
                        String videoId     = ds.child("videoId").getValue(String.class);
                        String videoTitle  = ds.child("videoTitle").getValue(String.class);
                        String thumbUrl    = ds.child("thumbnailUrl").getValue(String.class);
                        String commentText = ds.child("commentText").getValue(String.class);

                        if (fromName == null) fromName = "Someone";
                        if (videoTitle == null) videoTitle = "";

                        Context ctx = getApplicationContext();

                        switch (type != null ? type : "") {
                            case "new_video":
                                YouTubeNotificationHelper.postNewVideo(
                                    ctx, fromName, videoTitle, thumbUrl, videoId);
                                break;
                            case "comment":
                                YouTubeNotificationHelper.postComment(
                                    ctx, fromName, videoId, videoTitle,
                                    commentText != null ? commentText : "");
                                break;
                            case "reply":
                                YouTubeNotificationHelper.postReply(
                                    ctx, fromName, videoId,
                                    commentText != null ? commentText : "");
                                break;
                            case "subscribe":
                                YouTubeNotificationHelper.postSubscribe(
                                    ctx, fromName, fromPhoto);
                                break;
                            case "live":
                                YouTubeNotificationHelper.postLive(
                                    ctx, fromName, videoId, videoTitle);
                                break;
                            default:
                                break;
                        }

                        ds.getRef().child("notified").setValue(true);
                    }
                    synchronized (lock) { done[0] = true; lock.notifyAll(); }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    synchronized (lock) { done[0] = true; lock.notifyAll(); }
                }
            });

        synchronized (lock) {
            if (!done[0]) {
                try { lock.wait(10_000); } catch (InterruptedException ignored) {}
            }
        }
        return Result.success();
    }
}
