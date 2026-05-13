package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.utils.Constants;
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

/**
 * ReelCommentNotifWorker — Background/killed-safe WorkManager task.
 *
 * Handles two notification types (KEY_TYPE):
 *   "comment" — someone commented on your reel
 *   "reply"   — someone replied to your comment on a reel
 *   "like"    — someone liked your comment on a reel
 *
 * Each type sends:
 *   1. FCM push via PushNotify  →  delivered even when owner's app is killed.
 *   2. In-app notification entry in Firebase  →  reel_notifications/{ownerUid}.
 *   3. Queue fallback entry  →  reelNotifQueue/{ownerUid}/{type}/{id}.
 */
public class ReelCommentNotifWorker extends Worker {

    public static final String KEY_TYPE            = "type";
    public static final String KEY_REEL_ID         = "reel_id";
    public static final String KEY_REEL_OWNER_UID  = "reel_owner_uid";
    public static final String KEY_COMMENTER_UID   = "commenter_uid";
    public static final String KEY_COMMENTER_NAME  = "commenter_name";
    public static final String KEY_COMMENT_ID      = "comment_id";
    public static final String KEY_COMMENT_TEXT    = "comment_text";
    public static final String KEY_PARENT_COMMENT_OWNER_UID = "parent_comment_owner_uid";

    public static final String TYPE_COMMENT = "comment";
    public static final String TYPE_REPLY   = "reply";
    public static final String TYPE_LIKE    = "like";

    public ReelCommentNotifWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String type            = getInputData().getString(KEY_TYPE);
        String reelId          = getInputData().getString(KEY_REEL_ID);
        String ownerUid        = getInputData().getString(KEY_REEL_OWNER_UID);
        String commenterUid    = getInputData().getString(KEY_COMMENTER_UID);
        String commenterName   = getInputData().getString(KEY_COMMENTER_NAME);
        String commentId       = getInputData().getString(KEY_COMMENT_ID);
        String commentText     = getInputData().getString(KEY_COMMENT_TEXT);

        if (reelId == null || ownerUid == null || ownerUid.isEmpty()) return Result.failure();
        if (commenterUid == null || commenterUid.equals(ownerUid))    return Result.success();
        if (type == null) type = TYPE_COMMENT;

        try {
            FirebaseDatabase db   = FirebaseDatabase.getInstance(Constants.DB_URL);
            long now              = System.currentTimeMillis();
            String name           = commenterName != null ? commenterName : "Someone";
            String text           = commentText   != null ? commentText   : "";
            String cid            = commentId     != null ? commentId     : "";

            String message;
            switch (type) {
                case TYPE_REPLY:
                    message = name + " replied: " + text;
                    break;
                case TYPE_LIKE:
                    message = name + " liked your comment";
                    break;
                default:
                    message = name + " commented: " + text;
                    break;
            }

            // 1. FCM push
            PushNotify.notifyReelComment(ownerUid, commenterUid, name, reelId, "", cid, message);

            // Fetch commenter's thumbUrl to include as senderPhoto
            String senderPhoto = "";
            try {
                com.google.android.gms.tasks.Task<com.google.firebase.database.DataSnapshot> task =
                    FirebaseDatabase.getInstance(Constants.DB_URL)
                        .getReference("users").child(commenterUid).child("thumbUrl").get();
                com.google.android.gms.tasks.Tasks.await(task, 3, java.util.concurrent.TimeUnit.SECONDS);
                if (task.isSuccessful() && task.getResult().getValue(String.class) != null) {
                    senderPhoto = task.getResult().getValue(String.class);
                }
            } catch (Exception ignored) {}

            // 2. In-app notification entry
            Map<String, Object> inApp = new HashMap<>();
            inApp.put("type",        type);
            inApp.put("senderUid",   commenterUid);
            inApp.put("senderName",  name);
            inApp.put("senderPhoto", senderPhoto);
            inApp.put("reel_id",     reelId);
            inApp.put("comment_id",  cid);
            inApp.put("message",     message);
            inApp.put("timestamp",   now);
            inApp.put("read",        false);
            db.getReference("reel_notifications").child(ownerUid).push().setValue(inApp);

            // 3. Queue fallback
            Map<String, Object> queue = new HashMap<>();
            queue.put("commenterUid",  commenterUid);
            queue.put("commenterName", name);
            queue.put("reelId",        reelId);
            queue.put("commentId",     cid);
            queue.put("text",          text);
            queue.put("type",          type);
            queue.put("timestamp",     now);
            db.getReference("reelNotifQueue")
              .child(ownerUid).child(type + "s").child(cid + "_" + now).setValue(queue);

            return Result.success();

        } catch (Exception e) {
            return Result.retry();
        }
    }

    /** Enqueue a new-comment notification. */
    public static void enqueue(Context ctx, String reelId, String reelOwnerUid,
                               String commenterUid, String commenterName,
                               String commentId,    String commentText) {
        enqueueInternal(ctx, TYPE_COMMENT, reelId, reelOwnerUid,
            commenterUid, commenterName, commentId, commentText);
    }

    /** Enqueue a reply notification to the parent comment's author. */
    public static void enqueueReply(Context ctx, String reelId, String parentCommentOwnerUid,
                                    String replierUid, String replierName,
                                    String replyId,    String replyText) {
        enqueueInternal(ctx, TYPE_REPLY, reelId, parentCommentOwnerUid,
            replierUid, replierName, replyId, replyText);
    }

    /** Enqueue a like notification to the comment author. */
    public static void enqueueLike(Context ctx, String reelId, String commentOwnerUid,
                                   String likerUid, String likerName, String commentId) {
        enqueueInternal(ctx, TYPE_LIKE, reelId, commentOwnerUid,
            likerUid, likerName, commentId, "");
    }

    private static void enqueueInternal(Context ctx, String type,
                                        String reelId, String ownerUid,
                                        String actorUid, String actorName,
                                        String commentId, String text) {
        if (ctx == null || reelId == null || ownerUid == null
                || ownerUid.isEmpty() || actorUid == null) return;
        if (actorUid.equals(ownerUid)) return;

        Data data = new Data.Builder()
            .putString(KEY_TYPE,            type)
            .putString(KEY_REEL_ID,         reelId)
            .putString(KEY_REEL_OWNER_UID,  ownerUid)
            .putString(KEY_COMMENTER_UID,   actorUid)
            .putString(KEY_COMMENTER_NAME,  actorName  != null ? actorName  : "Someone")
            .putString(KEY_COMMENT_ID,      commentId  != null ? commentId  : "")
            .putString(KEY_COMMENT_TEXT,    text       != null ? text       : "")
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ReelCommentNotifWorker.class)
            .setInputData(data)
            .addTag("reel_notif_" + type + "_" + reelId)
            .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            type + "_notif_" + reelId + "_" + commentId,
            androidx.work.ExistingWorkPolicy.KEEP,
            req);
    }
}
