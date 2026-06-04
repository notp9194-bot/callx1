package com.callx.app.youtube.notifications;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.callx.app.youtube.core.utils.YouTubeFirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * YouTubeNotificationWorker — Background WorkManager worker.
 *
 * Polls Firebase every 15 minutes for unread YouTube notifications.
 * Runs even when the app is fully killed (WorkManager survives process death).
 *
 * FIX: Ab YouTubeFCMNotificationHandler.handle() use karta hai (X system jaisa pattern):
 *   → Avatar / thumbnail properly download hota hai Executor thread pe
 *   → fromName, fromPhoto, videoTitle, commentText sab dikh te hain notification me
 */
public class YouTubeNotificationWorker extends Worker {

    private static final String WORK_TAG = "yt_notification_poll";

    public YouTubeNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

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

                        String type         = safeStr(ds, "type");
                        String fromName     = safeStr(ds, "fromName");
                        String fromPhoto    = safeStr(ds, "fromPhotoUrl");
                        String fromUid      = safeStr(ds, "fromUid");
                        String videoId      = safeStr(ds, "videoId");
                        String videoTitle   = safeStr(ds, "videoTitle");
                        String thumbnailUrl = safeStr(ds, "thumbnailUrl");
                        String commentText  = safeStr(ds, "commentText");
                        String likeCount    = safeStr(ds, "likeCount");

                        if (fromName.isEmpty()) fromName = "Someone";

                        Map<String, String> data = new HashMap<>();
                        data.put("yt_notif_type",  type);
                        data.put("fromName",        fromName);
                        data.put("fromPhoto",       fromPhoto);
                        data.put("fromUid",         fromUid);
                        data.put("videoId",         videoId);
                        data.put("videoTitle",      videoTitle);
                        data.put("thumbnailUrl",    thumbnailUrl);
                        data.put("commentText",     commentText);
                        data.put("likeCount",       likeCount);

                        YouTubeFCMNotificationHandler.handle(getApplicationContext(), data);

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
                try { lock.wait(12_000); } catch (InterruptedException ignored) {}
            }
        }
        return Result.success();
    }

    private static String safeStr(DataSnapshot ds, String key) {
        String v = ds.child(key).getValue(String.class);
        return v != null ? v : "";
    }
}
