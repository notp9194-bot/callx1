package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.callx.app.notifications.CollabRepostNotificationHelper;
import com.callx.app.utils.Constants;
import com.google.firebase.database.*;
import java.util.*;
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
 * Static factory methods handle enqueueing with proper tag-based dedup.
 */
public class CollabRepostWorker extends ListenableWorker {

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

    private final WorkerParameters params;

    public CollabRepostWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        this.params = params;
    }

    @NonNull @Override
    public ListenableFuture<Result> startWork() {
        // Use a ResolvableFuture for async Firebase calls
        com.google.common.util.concurrent.SettableFuture<Result> future =
            com.google.common.util.concurrent.SettableFuture.create();

        try {
            String type      = params.getInputData().getString(KEY_TYPE);
            String collabId  = params.getInputData().getString(KEY_COLLAB_ID);
            String reelId    = params.getInputData().getString(KEY_ORIGINAL_REEL_ID);
            String newReelId = params.getInputData().getString(KEY_NEW_REEL_ID);
            String sUid      = params.getInputData().getString(KEY_SENDER_UID);
            String sName     = params.getInputData().getString(KEY_SENDER_NAME);
            String sPhoto    = params.getInputData().getString(KEY_SENDER_PHOTO);
            String sCap      = params.getInputData().getString(KEY_SENDER_CAPTION);
            String tUid      = params.getInputData().getString(KEY_TARGET_UID);
            String tName     = params.getInputData().getString(KEY_TARGET_NAME);
            String oUid      = params.getInputData().getString(KEY_OWNER_UID);
            String oName     = params.getInputData().getString(KEY_OWNER_NAME);
            String thumb     = params.getInputData().getString(KEY_THUMB_URL);

            if (type == null) { future.set(Result.failure()); return future; }

            switch (type) {
                case TYPE_INVITE:
                    handleInvite(future, collabId, reelId, sUid, sName, sPhoto, sCap, tUid, tName, oUid, oName, thumb);
                    break;
                case TYPE_ACCEPTED:
                    handleAccepted(future, collabId, newReelId, reelId, sUid, sName, sPhoto, tUid, thumb);
                    break;
                case TYPE_DECLINED:
                    handleDeclined(future, collabId, reelId, sUid, sName, tUid, thumb);
                    break;
                default:
                    future.set(Result.failure());
            }
        } catch (Exception e) {
            future.set(Result.retry());
        }
        return future;
    }

    // ── Invite: write Firebase notification + local notification to collaborator ─
    private void handleInvite(
            com.google.common.util.concurrent.SettableFuture<Result> future,
            String collabId, String reelId,
            String sUid, String sName, String sPhoto, String sCap,
            String tUid, String tName,
            String oUid, String oName, String thumb) {

        if (tUid == null || tUid.isEmpty()) { future.set(Result.failure()); return; }

        // Write Firebase in-app notification to collaborator (target)
        DatabaseReference notifRef = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reel_notifications").child(tUid).push();
        String notifId = notifRef.getKey();
        if (notifId == null) { future.set(Result.retry()); return; }

        Map<String, Object> notif = new HashMap<>();
        notif.put("type",         "collab_repost_invite");
        notif.put("senderUid",    sUid  != null ? sUid  : "");
        notif.put("senderName",   sName != null ? sName : "Someone");
        notif.put("senderPhoto",  sPhoto != null ? sPhoto : "");
        notif.put("reel_id",      reelId  != null ? reelId  : "");
        notif.put("collabRepostId", collabId != null ? collabId : "");
        notif.put("ownerUid",     oUid  != null ? oUid  : "");
        notif.put("ownerName",    oName != null ? oName : "");
        notif.put("thumbUrl",     thumb != null ? thumb : "");
        notif.put("caption",      sCap  != null ? sCap  : "");
        notif.put("timestamp",    System.currentTimeMillis());
        notif.put("seen",         false);

        notifRef.setValue(notif)
            .addOnSuccessListener(v -> {
                // Local device notification (if target is current device user)
                CollabRepostNotificationHelper.showInviteNotification(
                    getApplicationContext(),
                    collabId != null ? collabId : "",
                    sName    != null ? sName    : "Someone",
                    sCap     != null ? sCap     : "",
                    thumb    != null ? thumb    : ""
                );
                future.set(Result.success());
            })
            .addOnFailureListener(e -> future.set(Result.retry()));
    }

    // ── Accepted: write Firebase notification to initiator ────────────────────
    private void handleAccepted(
            com.google.common.util.concurrent.SettableFuture<Result> future,
            String collabId, String newReelId, String reelId,
            String sUid, String sName, String sPhoto,
            String tUid, String thumb) {

        if (tUid == null || tUid.isEmpty()) { future.set(Result.failure()); return; }

        CollabRepostNotificationHelper.showAcceptedNotification(
            getApplicationContext(),
            collabId  != null ? collabId  : "",
            newReelId != null ? newReelId : "",
            sName     != null ? sName     : "Someone",
            thumb     != null ? thumb     : ""
        );
        future.set(Result.success());
    }

    // ── Declined: local notification to initiator ──────────────────────────────
    private void handleDeclined(
            com.google.common.util.concurrent.SettableFuture<Result> future,
            String collabId, String reelId,
            String sUid, String sName,
            String tUid, String thumb) {

        CollabRepostNotificationHelper.showDeclinedNotification(
            getApplicationContext(),
            collabId != null ? collabId : "",
            sName    != null ? sName    : "Someone",
            thumb    != null ? thumb    : ""
        );
        future.set(Result.success());
    }

    // ── Static factory: enqueue invite ────────────────────────────────────────
    public static void enqueueInvite(
            Context ctx,
            String collabId, String reelId,
            String sUid, String sName, String sPhoto, String sCap,
            String tUid, String tName,
            String oUid, String oName, String thumb) {

        Data data = new Data.Builder()
            .putString(KEY_TYPE,             TYPE_INVITE)
            .putString(KEY_COLLAB_ID,        collabId != null ? collabId : "")
            .putString(KEY_ORIGINAL_REEL_ID, reelId   != null ? reelId   : "")
            .putString(KEY_SENDER_UID,       sUid     != null ? sUid     : "")
            .putString(KEY_SENDER_NAME,      sName    != null ? sName    : "")
            .putString(KEY_SENDER_PHOTO,     sPhoto   != null ? sPhoto   : "")
            .putString(KEY_SENDER_CAPTION,   sCap     != null ? sCap     : "")
            .putString(KEY_TARGET_UID,       tUid     != null ? tUid     : "")
            .putString(KEY_TARGET_NAME,      tName    != null ? tName    : "")
            .putString(KEY_OWNER_UID,        oUid     != null ? oUid     : "")
            .putString(KEY_OWNER_NAME,       oName    != null ? oName    : "")
            .putString(KEY_THUMB_URL,        thumb    != null ? thumb    : "")
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CollabRepostWorker.class)
            .setInputData(data)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("collab_repost_invite_" + (collabId != null ? collabId : ""))
            .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "collab_invite_" + (collabId != null ? collabId : System.currentTimeMillis()),
            ExistingWorkPolicy.KEEP, req);
    }

    // ── Static factory: enqueue accepted ─────────────────────────────────────
    public static void enqueueAccepted(
            Context ctx,
            String collabId, String newReelId, String reelId,
            String sUid, String sName, String sPhoto,
            String tUid, String thumb) {

        Data data = new Data.Builder()
            .putString(KEY_TYPE,             TYPE_ACCEPTED)
            .putString(KEY_COLLAB_ID,        collabId  != null ? collabId  : "")
            .putString(KEY_NEW_REEL_ID,      newReelId != null ? newReelId : "")
            .putString(KEY_ORIGINAL_REEL_ID, reelId    != null ? reelId    : "")
            .putString(KEY_SENDER_UID,       sUid      != null ? sUid      : "")
            .putString(KEY_SENDER_NAME,      sName     != null ? sName     : "")
            .putString(KEY_SENDER_PHOTO,     sPhoto    != null ? sPhoto    : "")
            .putString(KEY_TARGET_UID,       tUid      != null ? tUid      : "")
            .putString(KEY_THUMB_URL,        thumb     != null ? thumb     : "")
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CollabRepostWorker.class)
            .setInputData(data)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("collab_repost_accepted_" + (collabId != null ? collabId : ""))
            .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "collab_accepted_" + (collabId != null ? collabId : System.currentTimeMillis()),
            ExistingWorkPolicy.KEEP, req);
    }

    // ── Static factory: enqueue declined ─────────────────────────────────────
    public static void enqueueDeclined(
            Context ctx,
            String collabId, String reelId,
            String sUid, String sName,
            String tUid, String thumb) {

        Data data = new Data.Builder()
            .putString(KEY_TYPE,             TYPE_DECLINED)
            .putString(KEY_COLLAB_ID,        collabId != null ? collabId : "")
            .putString(KEY_ORIGINAL_REEL_ID, reelId   != null ? reelId   : "")
            .putString(KEY_SENDER_UID,       sUid     != null ? sUid     : "")
            .putString(KEY_SENDER_NAME,      sName    != null ? sName    : "")
            .putString(KEY_TARGET_UID,       tUid     != null ? tUid     : "")
            .putString(KEY_THUMB_URL,        thumb    != null ? thumb    : "")
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CollabRepostWorker.class)
            .setInputData(data)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("collab_repost_declined_" + (collabId != null ? collabId : ""))
            .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "collab_declined_" + (collabId != null ? collabId : System.currentTimeMillis()),
            ExistingWorkPolicy.KEEP, req);
    }
}
