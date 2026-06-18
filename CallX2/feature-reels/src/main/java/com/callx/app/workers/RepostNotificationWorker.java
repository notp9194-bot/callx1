package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.google.firebase.database.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RepostNotificationWorker — sends FCM + in-app notification to reel owner
 * when someone reposts their reel. Also writes to reel_notifications node.
 */
public class RepostNotificationWorker extends Worker {

    private static final String KEY_REEL_ID      = "reelId";
    private static final String KEY_OWNER_UID    = "ownerUid";
    private static final String KEY_FROM_UID     = "fromUid";
    private static final String KEY_FROM_NAME    = "fromName";
    private static final String KEY_FROM_PHOTO   = "fromPhoto";
    private static final String KEY_REEL_THUMB   = "reelThumb";
    private static final String KEY_CAPTION      = "caption";

    public RepostNotificationWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    public static void enqueue(Context ctx, String reelId, String ownerUid,
                               String fromUid, String fromName, String fromPhoto,
                               String reelThumb, String caption) {
        Data data = new Data.Builder()
            .putString(KEY_REEL_ID,    reelId)
            .putString(KEY_OWNER_UID,  ownerUid)
            .putString(KEY_FROM_UID,   fromUid)
            .putString(KEY_FROM_NAME,  fromName)
            .putString(KEY_FROM_PHOTO, fromPhoto != null ? fromPhoto : "")
            .putString(KEY_REEL_THUMB, reelThumb != null ? reelThumb : "")
            .putString(KEY_CAPTION,    caption   != null ? caption   : "")
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(RepostNotificationWorker.class)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build();
        WorkManager.getInstance(ctx).enqueue(req);
    }

    @NonNull @Override
    public Result doWork() {
        String reelId    = getInputData().getString(KEY_REEL_ID);
        String ownerUid  = getInputData().getString(KEY_OWNER_UID);
        String fromUid   = getInputData().getString(KEY_FROM_UID);
        String fromName  = getInputData().getString(KEY_FROM_NAME);
        String fromPhoto = getInputData().getString(KEY_FROM_PHOTO);
        String reelThumb = getInputData().getString(KEY_REEL_THUMB);
        String caption   = getInputData().getString(KEY_CAPTION);

        // Step 1: In-app notification → reel_notifications/{ownerUid}
        writeInAppNotification(reelId, ownerUid, fromUid, fromName, fromPhoto, reelThumb, caption);

        // Step 2: FCM push via server
        sendFcmPush(reelId, ownerUid, fromUid, fromName, fromPhoto, reelThumb, caption);

        // Step 3: Queue fallback
        writeNotifQueue(reelId, ownerUid, fromUid, fromName, reelThumb, caption);

        return Result.success();
    }

    private void writeInAppNotification(String reelId, String ownerUid, String fromUid,
                                         String fromName, String fromPhoto,
                                         String reelThumb, String caption) {
        DatabaseReference ref = FirebaseDatabase.getInstance()
            .getReference("reel_notifications").child(ownerUid).push();
        Map<String, Object> n = new HashMap<>();
        n.put("type",       "repost");
        n.put("senderUid",  fromUid);
        n.put("senderName", fromName);
        n.put("senderPhoto",fromPhoto);
        n.put("reel_id",    reelId);
        n.put("reel_thumb", reelThumb);
        n.put("caption",    caption);
        n.put("timestamp",  System.currentTimeMillis());
        n.put("read",       false);
        ref.updateChildren(n);
    }

    private void sendFcmPush(String reelId, String ownerUid, String fromUid,
                              String fromName, String fromPhoto,
                              String reelThumb, String caption) {
        try {
            com.callx.app.utils.PushNotify.notifyReelRepost(
                ownerUid, fromUid, fromName, fromPhoto, reelId, reelThumb, caption);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writeNotifQueue(String reelId, String ownerUid, String fromUid,
                                  String fromName, String reelThumb, String caption) {
        DatabaseReference qRef = FirebaseDatabase.getInstance()
            .getReference("reelNotifQueue").child(ownerUid).child("reposts").push();
        Map<String, Object> q = new HashMap<>();
        q.put("reelId",    reelId);
        q.put("fromUid",   fromUid);
        q.put("fromName",  fromName);
        q.put("reelThumb", reelThumb);
        q.put("caption",   caption);
        q.put("queued",    System.currentTimeMillis());
        qRef.updateChildren(q);
    }
}
