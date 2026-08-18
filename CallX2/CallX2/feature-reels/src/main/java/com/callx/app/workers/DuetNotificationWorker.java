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
 * DuetNotificationWorker v3 — WorkManager job that notifies the original reel
 * creator when someone duets their reel.
 *
 * ── Architecture (matches ReelCommentNotifWorker exactly) ────────────────────
 *
 * Step 1 — FCM push via PushNotify.notifyReelDuet()
 *   → POST Constants.SERVER_URL/notify/reel  {type: "duet", ...}
 *   → Server reads owner's FCM token, sends data-only FCM payload
 *   → Payload contains reel_notif_type: "duet"
 *   → Device wakes up (background/killed safe ✅)
 *   → CallxMessagingService.onMessageReceived()
 *       detects reel_notif_type key
 *       → ReelFCMNotificationHandler.handle()
 *           TYPE_DUET case → Executor → avatar download + thumb download
 *           → showNotifDirect() → BigPicture notification with reel thumb ✅
 *
 * Step 2 — In-app notification entry
 *   → reel_notifications/{ownerUid}/{pushKey}
 *   → Shown in ReelNotificationsActivity / NotificationCenterActivity
 *
 * Step 3 — Queue fallback entry
 *   → reelNotifQueue/{ownerUid}/duets/{id}
 *   → ReelNotificationWorker polls this every 15 min
 *   → Catches pushes FCM may have dropped (background process limits etc.)
 *
 * ── What was WRONG in v2 ─────────────────────────────────────────────────────
 * v2 tried to:
 *   (a) read users/{ownerUid}/fcmToken from RTDB directly on device
 *   (b) POST to https://fcm.googleapis.com/fcm/send using a server key in strings.xml
 * This is completely wrong for this app — the server key must NEVER be on device,
 * and it bypasses CallxMessagingService / ReelFCMNotificationHandler entirely,
 * so background/killed-state notifications would not work properly.
 *
 * ── No setup required ────────────────────────────────────────────────────────
 * No strings.xml changes needed.
 * No FCM token reading on device.
 * Works automatically as long as Constants.SERVER_URL is reachable.
 */
public class DuetNotificationWorker extends Worker {

    private static final String TAG = "DuetNotifWorker";

    public static final String KEY_REEL_ID    = "reel_id";
    public static final String KEY_FROM_UID   = "from_uid";
    public static final String KEY_FROM_NAME  = "from_name";
    public static final String KEY_FROM_PHOTO = "from_photo";
    public static final String KEY_OWNER_UID  = "owner_uid";
    public static final String KEY_REEL_THUMB = "reel_thumb";

    public DuetNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
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
        // Don't notify self (creator dueted their own reel somehow)
        if (fromUid.equals(ownerUid)) return Result.success();

        String name  = fromName  != null ? fromName  : "Someone";
        String photo = fromPhoto != null ? fromPhoto : "";
        String thumb = reelThumb != null ? reelThumb : "";

        try {
            FirebaseDatabase db = FirebaseDatabase.getInstance(Constants.DB_URL);
            long now = System.currentTimeMillis();

            // ── Step 1: FCM push via app's own server ─────────────────────────
            // Server reads owner's FCM token → sends reel_notif_type:"duet" payload
            // → CallxMessagingService → ReelFCMNotificationHandler → TYPE_DUET
            // Works in foreground, background, AND killed state ✅
            PushNotify.notifyReelDuet(ownerUid, fromUid, name, photo, reelId, thumb);

            // ── Step 2: In-app notification entry ─────────────────────────────
            // Shown in ReelNotificationsActivity and NotificationCenterActivity
            Map<String, Object> inApp = new HashMap<>();
            inApp.put("type",        "duet");
            inApp.put("senderUid",   fromUid);
            inApp.put("senderName",  name);
            inApp.put("senderPhoto", photo);
            inApp.put("reel_id",     reelId);
            inApp.put("reel_thumb",  thumb);
            inApp.put("message",     name + " dueted your reel 🔀");
            inApp.put("timestamp",   now);
            inApp.put("read",        false);
            db.getReference("reel_notifications")
              .child(ownerUid)
              .push()
              .setValue(inApp);

            // ── Step 3: Queue fallback ────────────────────────────────────────
            // ReelNotificationWorker polls reelNotifQueue every 15 min.
            // If FCM was dropped (Doze, app-standby), this catches it.
            String queueId = fromUid + "_" + reelId;
            Map<String, Object> queue = new HashMap<>();
            queue.put("fromUid",    fromUid);
            queue.put("name",       name);
            queue.put("photo",      photo);
            queue.put("reelId",     reelId);
            queue.put("reelThumb",  thumb);
            queue.put("type",       "duet");
            queue.put("timestamp",  now);
            db.getReference("reelNotifQueue")
              .child(ownerUid)
              .child("duets")
              .child(queueId)
              .setValue(queue);

            Log.i(TAG, "Duet notification sent: " + fromUid + " → " + ownerUid
                + " [reel=" + reelId + "]");
            return Result.success();

        } catch (Exception e) {
            Log.w(TAG, "doWork failed — will retry: " + e.getMessage());
            return Result.retry();
        }
    }

    // ── Enqueue helpers ───────────────────────────────────────────────────────

    /**
     * Basic enqueue — use when photo and thumb are not available.
     */
    public static void enqueue(Context context,
                               String reelId,
                               String fromUid,
                               String fromName,
                               String ownerUid) {
        enqueue(context, reelId, fromUid, fromName, null, ownerUid, null);
    }

    /**
     * Rich enqueue — includes sender photo and reel thumbnail.
     *
     * @param context    Application or Activity context
     * @param reelId     ID of the original reel that was dueted
     * @param fromUid    UID of the user who made the duet
     * @param fromName   Display name of the duet creator
     * @param fromPhoto  Avatar URL of the duet creator (nullable)
     * @param ownerUid   UID of the original reel's owner (notification recipient)
     * @param reelThumb  Thumbnail URL of the original reel (nullable)
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

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DuetNotificationWorker.class)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .addTag("duet_notif_" + reelId)
            .build();

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "duet_notif_" + fromUid + "_" + reelId,
                ExistingWorkPolicy.KEEP,
                req);
    }
}
