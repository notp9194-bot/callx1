package com.callx.app.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.*;

import com.callx.app.utils.Constants;
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * StitchNotificationWorker — WorkManager job that notifies the original reel
 * creator when someone stitches their reel.
 *
 * ✅ FIX (GAP #2): Mirrors DuetNotificationWorker exactly but uses "stitch" type.
 *
 * Architecture (same as DuetNotificationWorker):
 *
 * Step 1 — FCM push via PushNotify.notifyReelStitch()
 *   → POST Constants.SERVER_URL/notify/reel  {type: "stitch", ...}
 *   → Server reads owner's FCM token, sends data-only FCM payload
 *   → Payload contains reel_notif_type: "stitch"
 *   → Device wakes up (background/killed safe ✅)
 *   → CallxMessagingService → ReelFCMNotificationHandler TYPE_STITCH
 *
 * Step 2 — In-app notification entry
 *   → reel_notifications/{ownerUid}/{pushKey}
 *
 * Step 3 — Queue fallback entry
 *   → reelNotifQueue/{ownerUid}/stitches/{id}
 */
public class StitchNotificationWorker extends Worker {

    private static final String TAG = "StitchNotifWorker";

    public static final String KEY_REEL_ID    = "reel_id";
    public static final String KEY_FROM_UID   = "from_uid";
    public static final String KEY_FROM_NAME  = "from_name";
    public static final String KEY_FROM_PHOTO = "from_photo";
    public static final String KEY_OWNER_UID  = "owner_uid";
    public static final String KEY_REEL_THUMB = "reel_thumb";

    public StitchNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String reelId    = getInputData().getString(KEY_REEL_ID);
        String fromUid   = getInputData().getString(KEY_FROM_UID);
        String fromName  = getInputData().getString(KEY_FROM_NAME);
        String fromPhoto = getInputData().getString(KEY_FROM_PHOTO);
        String ownerUid  = getInputData().getString(KEY_OWNER_UID);
        String reelThumb = getInputData().getString(KEY_REEL_THUMB);

        if (reelId == null || fromUid == null || ownerUid == null || ownerUid.isEmpty()) {
            Log.w(TAG, "Missing required data — skipping");
            return Result.failure();
        }
        if (fromUid.equals(ownerUid)) return Result.success(); // don't notify self

        String name  = fromName  != null ? fromName  : "Someone";
        String photo = fromPhoto != null ? fromPhoto : "";
        String thumb = reelThumb != null ? reelThumb : "";

        try {
            FirebaseDatabase db = FirebaseDatabase.getInstance(Constants.DB_URL);
            long now = System.currentTimeMillis();

            // ── Step 1: FCM push via app's own server ─────────────────────
            PushNotify.notifyReelStitch(ownerUid, fromUid, name, photo, reelId, thumb);

            // ── Step 2: In-app notification entry ─────────────────────────
            Map<String, Object> inApp = new HashMap<>();
            inApp.put("type",        "stitch");
            inApp.put("senderUid",   fromUid);
            inApp.put("senderName",  name);
            inApp.put("senderPhoto", photo);
            inApp.put("reel_id",     reelId);
            inApp.put("reel_thumb",  thumb);
            inApp.put("message",     name + " stitched your reel ✂️");
            inApp.put("timestamp",   now);
            inApp.put("read",        false);
            db.getReference("reel_notifications")
              .child(ownerUid)
              .push()
              .setValue(inApp);

            // ── Step 3: Queue fallback ─────────────────────────────────────
            String queueId = fromUid + "_" + reelId;
            Map<String, Object> queue = new HashMap<>();
            queue.put("fromUid",   fromUid);
            queue.put("name",      name);
            queue.put("photo",     photo);
            queue.put("reelId",    reelId);
            queue.put("reelThumb", thumb);
            queue.put("type",      "stitch");
            queue.put("timestamp", now);
            db.getReference("reelNotifQueue")
              .child(ownerUid)
              .child("stitches")
              .child(queueId)
              .setValue(queue);

            Log.i(TAG, "Stitch notification sent: " + fromUid + " → " + ownerUid
                + " [reel=" + reelId + "]");
            return Result.success();

        } catch (Exception e) {
            Log.w(TAG, "doWork failed — will retry: " + e.getMessage());
            return Result.retry();
        }
    }

    /**
     * Enqueue a stitch notification (rich version with photo + thumb).
     */
    public static void enqueue(Context context,
                               String reelId,
                               String fromUid,
                               String fromName,
                               String fromPhoto,
                               String ownerUid,
                               String reelThumb) {
        if (context == null || reelId == null || fromUid == null
                || ownerUid == null || ownerUid.isEmpty()) return;
        if (fromUid.equals(ownerUid)) return;

        Data data = new Data.Builder()
            .putString(KEY_REEL_ID,    reelId)
            .putString(KEY_FROM_UID,   fromUid)
            .putString(KEY_FROM_NAME,  fromName  != null ? fromName  : "Someone")
            .putString(KEY_FROM_PHOTO, fromPhoto != null ? fromPhoto : "")
            .putString(KEY_OWNER_UID,  ownerUid)
            .putString(KEY_REEL_THUMB, reelThumb != null ? reelThumb : "")
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(StitchNotificationWorker.class)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .addTag("stitch_notif_" + reelId)
            .build();

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "stitch_notif_" + fromUid + "_" + reelId,
                ExistingWorkPolicy.KEEP,
                req);
    }
}
