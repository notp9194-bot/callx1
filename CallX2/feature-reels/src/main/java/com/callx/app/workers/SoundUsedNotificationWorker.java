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
 * SoundUsedNotificationWorker — WorkManager job that notifies a sound's
 * owner/creator when someone else's reel gets linked to their sound.
 *
 * ✅ NEW (plan item #2 — per-use sound notification): covers BOTH ways a
 * reel can end up linked to someone else's sound:
 *   1. Explicit "Use this sound" pick from a sound page / trending list.
 *   2. An automatic fingerprint match — the uploader's raw audio matched
 *      an existing sound byte-for-byte (or via speed/offset hypothesis)
 *      even though they never explicitly picked it.
 * Both paths converge on registerOrLinkSound()'s usingExistingSound branch,
 * which is where this is enqueued from — see ReelUploadActivity.
 *
 * Mirrors StitchNotificationWorker/DuetNotificationWorker exactly, just
 * with "sound_used" as the type and a soundTitle/soundId payload instead
 * of a plain reel reference.
 *
 * Architecture (same as DuetNotificationWorker/StitchNotificationWorker):
 *
 * Step 1 — FCM push via PushNotify.notifyReelSoundUsed()
 *   → POST Constants.SERVER_URL/notify/reel  {type: "sound_used", ...}
 *   → Server reads owner's FCM token, sends data-only FCM payload
 *   → Payload contains reel_notif_type: "sound_used"
 *   → Device wakes up (background/killed safe ✅)
 *   → CallxMessagingService → ReelFCMNotificationHandler TYPE_SOUND_USED
 *
 * Step 2 — In-app notification entry
 *   → reel_notifications/{ownerUid}/{pushKey}
 *
 * Step 3 — Queue fallback entry
 *   → reelNotifQueue/{ownerUid}/sound_uses/{id}
 */
public class SoundUsedNotificationWorker extends Worker {

    private static final String TAG = "SoundUsedNotifWorker";

    public static final String KEY_REEL_ID      = "reel_id";
    public static final String KEY_FROM_UID     = "from_uid";
    public static final String KEY_FROM_NAME    = "from_name";
    public static final String KEY_FROM_PHOTO   = "from_photo";
    public static final String KEY_OWNER_UID    = "owner_uid";
    public static final String KEY_REEL_THUMB   = "reel_thumb";
    public static final String KEY_SOUND_ID     = "sound_id";
    public static final String KEY_SOUND_TITLE  = "sound_title";

    public SoundUsedNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String reelId     = getInputData().getString(KEY_REEL_ID);
        String fromUid    = getInputData().getString(KEY_FROM_UID);
        String fromName   = getInputData().getString(KEY_FROM_NAME);
        String fromPhoto  = getInputData().getString(KEY_FROM_PHOTO);
        String ownerUid   = getInputData().getString(KEY_OWNER_UID);
        String reelThumb  = getInputData().getString(KEY_REEL_THUMB);
        String soundId    = getInputData().getString(KEY_SOUND_ID);
        String soundTitle = getInputData().getString(KEY_SOUND_TITLE);

        if (reelId == null || fromUid == null || ownerUid == null || ownerUid.isEmpty()) {
            Log.w(TAG, "Missing required data — skipping");
            return Result.failure();
        }
        if (fromUid.equals(ownerUid)) return Result.success(); // don't notify self (own sound reused by self)

        String name  = fromName   != null ? fromName   : "Someone";
        String photo = fromPhoto  != null ? fromPhoto  : "";
        String thumb = reelThumb  != null ? reelThumb  : "";
        String sid   = soundId    != null ? soundId    : "";
        String title = (soundTitle != null && !soundTitle.isEmpty()) ? soundTitle : "your sound";

        try {
            FirebaseDatabase db = FirebaseDatabase.getInstance(Constants.DB_URL);
            long now = System.currentTimeMillis();

            // ── Step 1: FCM push via app's own server ─────────────────────
            PushNotify.notifyReelSoundUsed(ownerUid, fromUid, name, photo, reelId, thumb, title, sid);

            // ── Step 2: In-app notification entry ─────────────────────────
            Map<String, Object> inApp = new HashMap<>();
            inApp.put("type",        "sound_used");
            inApp.put("senderUid",   fromUid);
            inApp.put("senderName",  name);
            inApp.put("senderPhoto", photo);
            inApp.put("reel_id",     reelId);
            inApp.put("reel_thumb",  thumb);
            inApp.put("sound_id",    sid);
            inApp.put("message",     name + " used " + title + " in their reel 🎵");
            inApp.put("timestamp",   now);
            inApp.put("read",        false);
            db.getReference("reel_notifications")
              .child(ownerUid)
              .push()
              .setValue(inApp);

            // ── Step 3: Queue fallback ─────────────────────────────────────
            String queueId = fromUid + "_" + reelId;
            Map<String, Object> queue = new HashMap<>();
            queue.put("fromUid",    fromUid);
            queue.put("name",       name);
            queue.put("photo",      photo);
            queue.put("reelId",     reelId);
            queue.put("reelThumb",  thumb);
            queue.put("soundId",    sid);
            queue.put("soundTitle", title);
            queue.put("type",       "sound_used");
            queue.put("timestamp",  now);
            db.getReference("reelNotifQueue")
              .child(ownerUid)
              .child("sound_uses")
              .child(queueId)
              .setValue(queue);

            Log.i(TAG, "Sound-used notification sent: " + fromUid + " → " + ownerUid
                + " [sound=" + sid + " reel=" + reelId + "]");
            return Result.success();

        } catch (Exception e) {
            Log.w(TAG, "doWork failed — will retry: " + e.getMessage());
            return Result.retry();
        }
    }

    /**
     * Enqueue a sound-used notification. Idempotent per (fromUid, reelId) —
     * a given reel only ever links to one sound, so this can't double-fire
     * for the same reel.
     */
    public static void enqueue(Context context,
                               String reelId,
                               String fromUid,
                               String fromName,
                               String fromPhoto,
                               String ownerUid,
                               String reelThumb,
                               String soundId,
                               String soundTitle) {
        if (context == null || reelId == null || fromUid == null
                || ownerUid == null || ownerUid.isEmpty()) return;
        if (fromUid.equals(ownerUid)) return;

        Data data = new Data.Builder()
            .putString(KEY_REEL_ID,     reelId)
            .putString(KEY_FROM_UID,    fromUid)
            .putString(KEY_FROM_NAME,   fromName   != null ? fromName   : "Someone")
            .putString(KEY_FROM_PHOTO,  fromPhoto  != null ? fromPhoto  : "")
            .putString(KEY_OWNER_UID,   ownerUid)
            .putString(KEY_REEL_THUMB,  reelThumb  != null ? reelThumb  : "")
            .putString(KEY_SOUND_ID,    soundId    != null ? soundId    : "")
            .putString(KEY_SOUND_TITLE, soundTitle != null ? soundTitle : "")
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SoundUsedNotificationWorker.class)
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .addTag("sound_used_notif_" + reelId)
            .build();

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "sound_used_notif_" + fromUid + "_" + reelId,
                ExistingWorkPolicy.KEEP,
                req);
    }
}
