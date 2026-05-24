package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager worker that fans out new-video notifications to all subscribers
 * when a video is uploaded. Called from YouTubeUploadActivity after upload.
 *
 * Input data:
 *   video_id      String — the newly uploaded video ID
 *   uploader_uid  String — uploader's UID
 *   uploader_name String — uploader's display name
 *   video_title   String — video title
 *   thumbnail_url String — video thumbnail
 */
public class YouTubeUploadNotificationWorker extends Worker {

    public YouTubeUploadNotificationWorker(@NonNull Context ctx,
                                            @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull @Override
    public Result doWork() {
        String videoId      = getInputData().getString("video_id");
        String uploaderUid  = getInputData().getString("uploader_uid");
        String uploaderName = getInputData().getString("uploader_name");
        String videoTitle   = getInputData().getString("video_title");
        String thumbUrl     = getInputData().getString("thumbnail_url");

        if (videoId == null || uploaderUid == null) return Result.failure();

        CountDownLatch latch = new CountDownLatch(1);
        final Result[] outcome = {Result.success()};

        YouTubeFirebaseUtils.subscribersRef(uploaderUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    try {
                        for (DataSnapshot ds : snap.getChildren()) {
                            String subUid = ds.getKey();
                            if (subUid == null) continue;

                            // Check notification tier for this subscriber
                            YouTubeFirebaseUtils.notifTierRef(subUid, uploaderUid)
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(@NonNull DataSnapshot tierSnap) {
                                        String tier = tierSnap.getValue(String.class);
                                        if ("none".equals(tier)) return;

                                        String notifId = YouTubeFirebaseUtils
                                            .notificationsRef(subUid).push().getKey();
                                        if (notifId == null) return;

                                        YouTubeNotification n = new YouTubeNotification(
                                            notifId, subUid, uploaderUid,
                                            uploaderName, null,
                                            "new_video", videoId,
                                            videoTitle, thumbUrl);
                                        YouTubeFirebaseUtils.notificationsRef(subUid)
                                            .child(notifId).setValue(n);
                                    }
                                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                                });
                        }
                    } finally {
                        latch.countDown();
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    outcome[0] = Result.retry();
                    latch.countDown();
                }
            });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }

        return outcome[0];
    }
}
