package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.callx.app.notifications.CollabRepostNotificationHelper;
import com.callx.app.utils.Constants;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * CollabRepostWorker — WorkManager worker for Collab Repost notifications.
 *
 * Handles three event types (driven by INPUT_TYPE key):
 *  • "invite"   — fired when User A sends an invite to User B
 *  • "accepted" — fired when User B accepts; notifies User A
 *  • "declined" — fired when User B declines; notifies User A
 *
 * Why WorkManager:
 *  • Survives app kills / process death during network delay
 *  • Automatic retries with exponential back-off (up to 3 attempts)
 *  • Constraints: NetworkType.CONNECTED ensures delivery only when online
 *
 * Uses androidx.work.Worker (synchronous doWork + CountDownLatch) so that
 * no Guava ListenableFuture dependency is required on the classpath.
 *
 * Static factory methods handle enqueueing with proper tag-based dedup.
 */
public class CollabRepostWorker extends Worker {

    // ── Input keys ────────────────────────────────────────────────────────────
    public static final String KEY_TYPE             = "type";
    public static final String KEY_COLLAB_ID        = "collab_repost_id";
    public static final String KEY_ORIGINAL_REEL_ID = "original_reel_id";
    public static final String KEY_NEW_REEL_ID      = "new_reel_id";
    public static final String KEY_SENDER_UID       = "sender_uid";
    public static final String KEY_SENDER_NAME      = "sender_name";
    public static final String KEY_SENDER_PHOTO     = "sender_photo";
    public static final String KEY_SENDER_CAPTION   = "sender_caption";
    public static final String KEY_TARGET_UID       = "target_uid";
    public static final String KEY_TARGET_NAME      = "target_name";
    public static final String KEY_OWNER_UID        = "owner_uid";
    public static final String KEY_OWNER_NAME       = "owner_name";
    public static final String KEY_THUMB_URL        = "thumb_url";

    public static final String TYPE_INVITE   = "invite";
    public static final String TYPE_ACCEPTED = "accepted";
    public static final String TYPE_DECLINED = "declined";

    private static final long FIREBASE_TIMEOUT_SECONDS = 15L;

    public CollabRepostWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String type      = getInputData().getString(KEY_TYPE);
        String collabId  = getInputData().getString(KEY_COLLAB_ID);
        String reelId    = getInputData().getString(KEY_ORIGINAL_REEL_ID);
        String newReelId = getInputData().getString(KEY_NEW_REEL_ID);
        String sUid      = getInputData().getString(KEY_SENDER_UID);
        String sName     = getInputData().getString(KEY_SENDER_NAME);
        String sPhoto    = getInputData().getString(KEY_SENDER_PHOTO);
        String sCap      = getInputData().getString(KEY_SENDER_CAPTION);
        String tUid      = getInputData().getString(KEY_TARGET_UID);
        String tName     = getInputData().getString(KEY_TARGET_NAME);
        String oUid      = getInputData().getString(KEY_OWNER_UID);
        String oName     = getInputData().getString(KEY_OWNER_NAME);
        String thumb     = getInputData().getString(KEY_THUMB_URL);

        if (type == null) return Result.failure();

        try {
            switch (type) {
                case TYPE_INVITE:
                    return handleInvite(collabId, reelId, sUid, sName, sPhoto, sCap,
                                        tUid, tName, oUid, oName, thumb);
                case TYPE_ACCEPTED:
                    return handleAccepted(collabId, newReelId, reelId,
                                          sUid, sName, sPhoto, tUid, thumb);
                case TYPE_DECLINED:
                    return handleDeclined(collabId, reelId, sUid, sName, tUid, thumb);
                default:
                    return Result.failure();
            }
        } catch (Exception e) {
            return Result.retry();
        }
    }

    // ── Invite: write Firebase in-app notification to collaborator ────────────
    private Result handleInvite(
            String collabId, String reelId,
            String sUid, String sName, String sPhoto, String sCap,
            String tUid, String tName,
            String oUid, String oName, String thumb) throws InterruptedException {

        if (tUid == null || tUid.isEmpty()) return Result.failure();

        DatabaseReference notifRef = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reel_notifications").child(tUid).push();
        String notifId = notifRef.getKey();
        if (notifId == null) return Result.retry();

        Map<String, Object> notif = new HashMap<>();
        notif.put("type",           "collab_repost_invite");
        notif.put("senderUid",      safe(sUid));
        notif.put("senderName",     safe(sName));
        notif.put("senderPhoto",    safe(sPhoto));
        notif.put("reel_id",        safe(reelId));
        notif.put("collabRepostId", safe(collabId));
        notif.put("ownerUid",       safe(oUid));
        notif.put("ownerName",      safe(oName));
        notif.put("thumbUrl",       safe(thumb));
        notif.put("caption",        safe(sCap));
        notif.put("timestamp",      System.currentTimeMillis());
        notif.put("seen",           false);

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};

        notifRef.setValue(notif)
            .addOnSuccessListener(v -> { success[0] = true;  latch.countDown(); })
            .addOnFailureListener(e -> {                     latch.countDown(); });

        boolean finished = latch.await(FIREBASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished || !success[0]) return Result.retry();

        // Local device notification (if this device belongs to the target user)
        CollabRepostNotificationHelper.showInviteNotification(
            getApplicationContext(),
            safe(collabId), safe(sName), safe(sCap), safe(thumb));

        return Result.success();
    }

    // ── Accepted ──────────────────────────────────────────────────────────────
    private Result handleAccepted(
            String collabId, String newReelId, String reelId,
            String sUid, String sName, String sPhoto,
            String tUid, String thumb) {

        CollabRepostNotificationHelper.showAcceptedNotification(
            getApplicationContext(),
            safe(collabId), safe(newReelId), safe(sName), safe(thumb));
        return Result.success();
    }

    // ── Declined ──────────────────────────────────────────────────────────────
    private Result handleDeclined(
            String collabId, String reelId,
            String sUid, String sName,
            String tUid, String thumb) {

        CollabRepostNotificationHelper.showDeclinedNotification(
            getApplicationContext(),
            safe(collabId), safe(sName), safe(thumb));
        return Result.success();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private static String safe(String s) { return s != null ? s : ""; }

    // ── Static factory: enqueue invite ────────────────────────────────────────
    public static void enqueueInvite(
            Context ctx,
            String collabId, String reelId,
            String sUid, String sName, String sPhoto, String sCap,
            String tUid, String tName,
            String oUid, String oName, String thumb) {

        Data data = new Data.Builder()
            .putString(KEY_TYPE,             TYPE_INVITE)
            .putString(KEY_COLLAB_ID,        safe(collabId))
            .putString(KEY_ORIGINAL_REEL_ID, safe(reelId))
            .putString(KEY_SENDER_UID,       safe(sUid))
            .putString(KEY_SENDER_NAME,      safe(sName))
            .putString(KEY_SENDER_PHOTO,     safe(sPhoto))
            .putString(KEY_SENDER_CAPTION,   safe(sCap))
            .putString(KEY_TARGET_UID,       safe(tUid))
            .putString(KEY_TARGET_NAME,      safe(tName))
            .putString(KEY_OWNER_UID,        safe(oUid))
            .putString(KEY_OWNER_NAME,       safe(oName))
            .putString(KEY_THUMB_URL,        safe(thumb))
            .build();

        enqueue(ctx, "collab_invite_" + safe(collabId), data);
    }

    // ── Static factory: enqueue accepted ─────────────────────────────────────
    public static void enqueueAccepted(
            Context ctx,
            String collabId, String newReelId, String reelId,
            String sUid, String sName, String sPhoto,
            String tUid, String thumb) {

        Data data = new Data.Builder()
            .putString(KEY_TYPE,             TYPE_ACCEPTED)
            .putString(KEY_COLLAB_ID,        safe(collabId))
            .putString(KEY_NEW_REEL_ID,      safe(newReelId))
            .putString(KEY_ORIGINAL_REEL_ID, safe(reelId))
            .putString(KEY_SENDER_UID,       safe(sUid))
            .putString(KEY_SENDER_NAME,      safe(sName))
            .putString(KEY_SENDER_PHOTO,     safe(sPhoto))
            .putString(KEY_TARGET_UID,       safe(tUid))
            .putString(KEY_THUMB_URL,        safe(thumb))
            .build();

        enqueue(ctx, "collab_accepted_" + safe(collabId), data);
    }

    // ── Static factory: enqueue declined ─────────────────────────────────────
    public static void enqueueDeclined(
            Context ctx,
            String collabId, String reelId,
            String sUid, String sName,
            String tUid, String thumb) {

        Data data = new Data.Builder()
            .putString(KEY_TYPE,             TYPE_DECLINED)
            .putString(KEY_COLLAB_ID,        safe(collabId))
            .putString(KEY_ORIGINAL_REEL_ID, safe(reelId))
            .putString(KEY_SENDER_UID,       safe(sUid))
            .putString(KEY_SENDER_NAME,      safe(sName))
            .putString(KEY_TARGET_UID,       safe(tUid))
            .putString(KEY_THUMB_URL,        safe(thumb))
            .build();

        enqueue(ctx, "collab_declined_" + safe(collabId), data);
    }

    private static void enqueue(Context ctx, String uniqueName, Data data) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CollabRepostWorker.class)
            .setInputData(data)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.KEEP,
            req);
    }
}
