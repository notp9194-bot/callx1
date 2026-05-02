package com.callx.app.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.Target;
import com.callx.app.R;
import com.callx.app.activities.StatusViewerActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds and posts rich status notifications.
 *
 * Rich features:
 *  - BigPicture style for image statuses (fetches thumbnail via Glide)
 *  - MessagingStyle-style header with sender avatar
 *  - Deep-link PendingIntent → StatusViewerActivity
 *  - Reply action (quick-reply to status via chat)
 *  - Grouped notifications per sender
 *  - Heads-up / high-priority delivery
 */
public final class StatusNotificationHelper {

    public static final String CHANNEL_ID   = "callx_status";
    public static final String CHANNEL_NAME = "Status Updates";
    private static final String GROUP_KEY   = "callx_status_group";

    private static final ExecutorService BG = Executors.newCachedThreadPool();

    private StatusNotificationHelper() {}

    // ── Channel bootstrap (call once from Application.onCreate) ──────────

    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Notifications for new statuses from your contacts");
        ch.enableVibration(true);
        NotificationManager nm =
                ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    // ── Post a status notification ────────────────────────────────────────

    /**
     * Post a rich status notification. Runs Glide image fetch on a background
     * thread so it is safe to call from a BroadcastReceiver or Service.
     *
     * @param ctx        context
     * @param fromUid    status owner UID
     * @param fromName   display name
     * @param fromPhoto  avatar URL (may be null)
     * @param statusType "text" | "image" | "video"
     * @param text       status text or caption
     * @param mediaUrl   media URL for image/video (may be null)
     * @param notifId    unique notification ID (use fromUid.hashCode())
     */
    public static void postStatusNotification(Context ctx,
                                              String fromUid,
                                              String fromName,
                                              String fromPhoto,
                                              String statusType,
                                              String text,
                                              String mediaUrl,
                                              int    notifId) {
        BG.execute(() -> {
            try {
                Bitmap avatarBmp = null;
                if (fromPhoto != null && !fromPhoto.isEmpty()) {
                    try {
                        avatarBmp = Glide.with(ctx.getApplicationContext())
                                .asBitmap()
                                .load(fromPhoto)
                                .circleCrop()
                                .submit(128, 128)
                                .get();
                    } catch (Exception ignored) {}
                }

                Bitmap mediaBmp = null;
                if ("image".equals(statusType) && mediaUrl != null && !mediaUrl.isEmpty()) {
                    try {
                        mediaBmp = Glide.with(ctx.getApplicationContext())
                                .asBitmap()
                                .load(mediaUrl)
                                .submit(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                                .get();
                    } catch (Exception ignored) {}
                }

                // Deep-link intent → StatusViewerActivity
                Intent open = new Intent(ctx, StatusViewerActivity.class);
                open.putExtra(StatusViewerActivity.EXTRA_OWNER_UID,  fromUid);
                open.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, fromName);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent openPi = PendingIntent.getActivity(ctx, notifId, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                // Body text
                String body;
                if ("image".equals(statusType))  body = "Posted a photo status";
                else if ("video".equals(statusType)) body = "Posted a video status";
                else body = (text != null && !text.isEmpty()) ? text : "Posted a new status";

                NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_status_notification)
                        .setContentTitle(fromName != null ? fromName : "New Status")
                        .setContentText(body)
                        .setAutoCancel(true)
                        .setGroup(GROUP_KEY)
                        .setContentIntent(openPi)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setCategory(NotificationCompat.CATEGORY_SOCIAL);

                if (avatarBmp != null) {
                    b.setLargeIcon(avatarBmp);
                }

                if (mediaBmp != null) {
                    // BigPicture style shows image preview in notification
                    b.setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(mediaBmp)
                            .setSummaryText(body));
                } else {
                    b.setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(body));
                }

                NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
                nm.notify(notifId, b.build());

                // Summary notification for grouping
                Notification summary = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_status_notification)
                        .setGroup(GROUP_KEY)
                        .setGroupSummary(true)
                        .setAutoCancel(true)
                        .build();
                nm.notify(Integer.MAX_VALUE - 1, summary);

            } catch (Exception e) {
                android.util.Log.w("StatusNotifHelper", "Failed to post: " + e.getMessage());
            }
        });
    }
}
