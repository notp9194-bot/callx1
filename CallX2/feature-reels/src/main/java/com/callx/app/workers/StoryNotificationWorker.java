package com.callx.app.workers;

import com.callx.app.reels.R;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.utils.Constants;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * StoryNotificationWorker — Background-kill-safe WorkManager task for story/status notifications.
 *
 * When a contact posts a new status:
 *  1. This worker is enqueued immediately (guaranteed to run even if app is killed).
 *  2. It fetches the story owner's name + photo from Firebase.
 *  3. It calls StatusNotificationHelper.postStatusNotification() via reflection
 *     so feature-reels has no compile dependency on feature-status.
 *
 * Enqueue from any Firebase listener that detects a new status from a contact:
 *   StoryNotificationWorker.enqueue(ctx, ownerUid, ownerName, ownerPhoto,
 *                                    statusType, statusText, mediaUrl);
 *
 * The worker uses KEEP conflict policy so rapid status posts don't spam notifications.
 * A grace delay (5 seconds) prevents notification if the user opens the app immediately.
 */
public class StoryNotificationWorker extends Worker {

    private static final String TAG = "StoryNotifWorker";

    public static final String KEY_OWNER_UID   = "owner_uid";
    public static final String KEY_OWNER_NAME  = "owner_name";
    public static final String KEY_OWNER_PHOTO = "owner_photo";
    public static final String KEY_STATUS_TYPE = "status_type";
    public static final String KEY_STATUS_TEXT = "status_text";
    public static final String KEY_MEDIA_URL   = "media_url";

    public StoryNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String ownerUid   = getInputData().getString(KEY_OWNER_UID);
        String ownerName  = getInputData().getString(KEY_OWNER_NAME);
        String ownerPhoto = getInputData().getString(KEY_OWNER_PHOTO);
        String statusType = getInputData().getString(KEY_STATUS_TYPE);
        String statusText = getInputData().getString(KEY_STATUS_TEXT);
        String mediaUrl   = getInputData().getString(KEY_MEDIA_URL);

        if (ownerUid == null) {
            Log.w(TAG, "ownerUid is null, skipping");
            return Result.failure();
        }

        // If name/photo not provided, fetch from Firebase
        if (ownerName == null || ownerName.isEmpty() || ownerPhoto == null) {
            String[] fetched = fetchUserDetails(ownerUid);
            if (ownerName  == null || ownerName.isEmpty())  ownerName  = fetched[0];
            if (ownerPhoto == null || ownerPhoto.isEmpty()) ownerPhoto = fetched[1];
        }

        int notifId = ownerUid.hashCode();

        // Call StatusNotificationHelper.postStatusNotification() via reflection
        // so feature-reels doesn't need a compile-time dep on feature-status.
        try {
            Class<?> helperClass = Class.forName("com.callx.app.utils.StatusNotificationHelper");
            Method postMethod = helperClass.getMethod(
                "postStatusNotification",
                Context.class,  // ctx
                String.class,   // fromUid
                String.class,   // fromName
                String.class,   // fromPhoto
                String.class,   // statusType
                String.class,   // text
                String.class,   // mediaUrl
                int.class       // notifId
            );
            postMethod.invoke(null,
                getApplicationContext(),
                ownerUid,
                ownerName  != null ? ownerName  : "A contact",
                ownerPhoto != null ? ownerPhoto : "",
                statusType != null ? statusType : "text",
                statusText != null ? statusText : "",
                mediaUrl   != null ? mediaUrl   : "",
                notifId
            );
            Log.d(TAG, "Story notification posted for uid=" + ownerUid);
            return Result.success();

        } catch (ClassNotFoundException e) {
            // StatusNotificationHelper not in APK — feature-status module absent
            Log.w(TAG, "StatusNotificationHelper not found, falling back to raw notification");
            postFallbackNotification(ownerName, statusType, statusText, notifId);
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Reflection error posting story notification: " + e.getMessage());
            return Result.retry();
        }
    }

    /**
     * Fetches the user's display name and photo URL synchronously via Firebase.
     * Returns ["", ""] if the fetch fails or times out.
     */
    private String[] fetchUserDetails(String uid) {
        final String[] result = {"", ""};
        CountDownLatch latch = new CountDownLatch(1);
        try {
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("users")
                .child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String n = snap.child("name").getValue(String.class);
                        String p = snap.child("photoUrl").getValue(String.class);
                        result[0] = n != null ? n : "";
                        result[1] = p != null ? p : "";
                        latch.countDown();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        latch.countDown();
                    }
                });
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch user details: " + e.getMessage());
        }
        return result;
    }

    /** Minimal fallback notification when StatusNotificationHelper is not reachable. */
    private void postFallbackNotification(String fromName, String statusType,
                                           String text, int notifId) {
        try {
            Context ctx = getApplicationContext();
            String channelId = "callx_status";

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationManager nm =
                    ctx.getSystemService(android.app.NotificationManager.class);
                if (nm != null && nm.getNotificationChannel(channelId) == null) {
                    android.app.NotificationChannel ch =
                        new android.app.NotificationChannel(
                            channelId, "Status Updates",
                            android.app.NotificationManager.IMPORTANCE_DEFAULT);
                    nm.createNotificationChannel(ch);
                }
            }

            String title = fromName != null && !fromName.isEmpty()
                ? fromName : "A contact";
            String body;
            if ("image".equals(statusType))       body = title + " posted a photo status";
            else if ("video".equals(statusType))  body = title + " posted a video status";
            else body = (text != null && !text.isEmpty()) ? text : title + " posted a new status";

            androidx.core.app.NotificationCompat.Builder builder =
                new androidx.core.app.NotificationCompat.Builder(ctx, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT);

            androidx.core.app.NotificationManagerCompat.from(ctx).notify(notifId, builder.build());
        } catch (Exception e) {
            Log.w(TAG, "Fallback notification failed: " + e.getMessage());
        }
    }

    // ── Static enqueue helper ─────────────────────────────────────────────

    /**
     * Enqueue a story notification worker. Safe to call from any thread.
     * Uses KEEP policy — if a worker for this owner is already pending, it is not replaced.
     *
     * @param ctx        application context
     * @param ownerUid   UID of the status poster
     * @param ownerName  display name (pass null to fetch from Firebase)
     * @param ownerPhoto avatar URL  (pass null to fetch from Firebase)
     * @param statusType "text" | "image" | "video"
     * @param statusText text content or caption
     * @param mediaUrl   media URL for image/video (may be null)
     */
    public static void enqueue(Context ctx,
                                String ownerUid,
                                String ownerName,
                                String ownerPhoto,
                                String statusType,
                                String statusText,
                                String mediaUrl) {
        Data inputData = new Data.Builder()
            .putString(KEY_OWNER_UID,   ownerUid)
            .putString(KEY_OWNER_NAME,  ownerName  != null ? ownerName  : "")
            .putString(KEY_OWNER_PHOTO, ownerPhoto != null ? ownerPhoto : "")
            .putString(KEY_STATUS_TYPE, statusType != null ? statusType : "text")
            .putString(KEY_STATUS_TEXT, statusText != null ? statusText : "")
            .putString(KEY_MEDIA_URL,   mediaUrl   != null ? mediaUrl   : "")
            .build();

        // Small initial delay to avoid notifying if user is actively using the app
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(StoryNotificationWorker.class)
            .setInputData(inputData)
            .setInitialDelay(5, TimeUnit.SECONDS)
            .addTag("story_notif_" + ownerUid)
            .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "story_notif_" + ownerUid,
            androidx.work.ExistingWorkPolicy.KEEP,
            request
        );
    }
}
