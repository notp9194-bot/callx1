package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CollabNotificationWorker — sends notifications for all collab events:
 *  collab_invite, collab_accepted, collab_rejected, collab_cancelled,
 *  live_collab_invite, live_collab_ended.
 */
public class CollabNotificationWorker extends Worker {

    private static final String KEY_TYPE        = "type";
    private static final String KEY_TARGET_UID  = "targetUid";
    private static final String KEY_FROM_UID    = "fromUid";
    private static final String KEY_FROM_NAME   = "fromName";
    private static final String KEY_FROM_PHOTO  = "fromPhoto";
    private static final String KEY_REEL_ID     = "reelId";
    private static final String KEY_COLLAB_ID   = "collabId";
    private static final String KEY_REEL_THUMB  = "reelThumb";

    public CollabNotificationWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    public static void enqueue(Context ctx, String type, String targetUid,
                               String fromUid, String fromName, String fromPhoto,
                               String reelId, String collabId, String reelThumb) {
        Data data = new Data.Builder()
            .putString(KEY_TYPE,       type)
            .putString(KEY_TARGET_UID, targetUid)
            .putString(KEY_FROM_UID,   fromUid)
            .putString(KEY_FROM_NAME,  fromName)
            .putString(KEY_FROM_PHOTO, fromPhoto != null ? fromPhoto : "")
            .putString(KEY_REEL_ID,    reelId)
            .putString(KEY_COLLAB_ID,  collabId != null ? collabId : "")
            .putString(KEY_REEL_THUMB, reelThumb != null ? reelThumb : "")
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CollabNotificationWorker.class)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build();
        WorkManager.getInstance(ctx).enqueue(req);
    }

    @NonNull @Override
    public Result doWork() {
        String type      = getInputData().getString(KEY_TYPE);
        String targetUid = getInputData().getString(KEY_TARGET_UID);
        String fromUid   = getInputData().getString(KEY_FROM_UID);
        String fromName  = getInputData().getString(KEY_FROM_NAME);
        String fromPhoto = getInputData().getString(KEY_FROM_PHOTO);
        String reelId    = getInputData().getString(KEY_REEL_ID);
        String collabId  = getInputData().getString(KEY_COLLAB_ID);
        String reelThumb = getInputData().getString(KEY_REEL_THUMB);

        writeInAppNotif(type, targetUid, fromUid, fromName, fromPhoto, reelId, collabId, reelThumb);
        sendFcmPush(type, targetUid, fromUid, fromName, fromPhoto, reelId, collabId, reelThumb);
        return Result.success();
    }

    private void writeInAppNotif(String type, String targetUid, String fromUid,
                                  String fromName, String fromPhoto, String reelId,
                                  String collabId, String reelThumb) {
        DatabaseReference ref = FirebaseDatabase.getInstance()
            .getReference("reel_notifications").child(targetUid).push();
        Map<String, Object> n = new HashMap<>();
        n.put("type",       type);
        n.put("senderUid",  fromUid);
        n.put("senderName", fromName);
        n.put("senderPhoto",fromPhoto);
        n.put("reel_id",    reelId);
        n.put("collab_id",  collabId);
        n.put("reel_thumb", reelThumb);
        n.put("timestamp",  System.currentTimeMillis());
        n.put("read",       false);
        ref.updateChildren(n);
    }

    private void sendFcmPush(String type, String targetUid, String fromUid,
                              String fromName, String fromPhoto, String reelId,
                              String collabId, String reelThumb) {
        // PushNotify.notifyCollab(type, targetUid, fromUid, fromName, fromPhoto,
        //                         reelId, collabId, reelThumb);
        // Maps to POST /notify/reel  { type, ownerUid, fromUid, ... }
    }
}
