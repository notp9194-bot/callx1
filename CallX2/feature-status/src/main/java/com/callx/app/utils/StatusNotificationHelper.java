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
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * StatusNotificationHelper — All status-related push and in-app notifications.
 *
 * Types handled:
 *  1. New status posted (by a contact you follow)
 *  2. Status viewed (someone saw your status)
 *  3. Status reaction (emoji reaction on your status)
 *  4. Status expiry reminder (2h before your status expires)
 *  5. Status reply (someone replied to your status in chat)
 *  6. Close-friends status posted (priority notification)
 */
public class StatusNotificationHelper {

    private static final String CHANNEL_STATUS      = "ch_status";
    private static final String CHANNEL_STATUS_REAC = "ch_status_reaction";
    private static final String CHANNEL_STATUS_EXP  = "ch_status_expiry";

    private static final int NOTIF_ID_BASE_STATUS  = 7000;
    private static final int NOTIF_ID_BASE_REACT   = 7500;
    private static final int NOTIF_ID_EXPIRY        = 7999;

    private static final ExecutorService bgEx =
        Executors.newCachedThreadPool();

    // ── Channel setup — call from Application.onCreate ────────────────────
    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
            (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Status updates channel
        NotificationChannel ch1 = new NotificationChannel(
            CHANNEL_STATUS, "Status Updates",
            NotificationManager.IMPORTANCE_DEFAULT);
        ch1.setDescription("New status posts from your contacts");
        ch1.enableVibration(true);

        // Reaction channel (higher importance — feels personal)
        NotificationChannel ch2 = new NotificationChannel(
            CHANNEL_STATUS_REAC, "Status Reactions",
            NotificationManager.IMPORTANCE_HIGH);
        ch2.setDescription("Reactions on your status");

        // Expiry reminder (low importance)
        NotificationChannel ch3 = new NotificationChannel(
            CHANNEL_STATUS_EXP, "Status Expiry Reminder",
            NotificationManager.IMPORTANCE_LOW);
        ch3.setDescription("Reminder 2 hours before your status expires");

        nm.createNotificationChannel(ch1);
        nm.createNotificationChannel(ch2);
        nm.createNotificationChannel(ch3);
    }

    // ── 1. New status posted ──────────────────────────────────────────────
    /**
     * Rich notification: poster name, avatar, status type.
     * Groups multiple new statuses from same contact into one notification.
     * Called from StatusBackgroundService when a new status is detected.
     */
    public static void notifyNewStatus(Context ctx,
                                       String fromUid,
                                       String fromName,
                                       String fromPhoto,
                                       String statusType,
                                       String mediaUrl,
                                       String statusText,
                                       String statusId) {
        bgEx.execute(() -> {
            Bitmap avatar = fetchBitmap(fromPhoto);

            // Deep-link intent → opens StatusViewerActivity directly
            Intent tapIntent = new Intent("com.callx.app.ACTION_OPEN_STATUS");
            tapIntent.putExtra("ownerUid",  fromUid);
            tapIntent.putExtra("statusId",  statusId);
            tapIntent.setPackage(ctx.getPackageName());
            PendingIntent pi = PendingIntent.getActivity(ctx,
                (fromUid + statusId).hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String body = buildStatusBody(statusType, statusText);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(fromName + " added to their status")
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setGroup("status_group_" + fromUid)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            if (avatar != null) {
                b.setLargeIcon(avatar);
                if ("image".equals(statusType) || "video".equals(statusType)) {
                    // BigPicture for image/video statuses
                    Bitmap bigPic = mediaUrl != null ? fetchBitmap(mediaUrl) : null;
                    if (bigPic != null) {
                        b.setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(bigPic)
                            .bigLargeIcon((Bitmap) null)
                            .setSummaryText(body));
                    }
                } else {
                    b.setStyle(new NotificationCompat.BigTextStyle().bigText(body));
                }
            }

            int notifId = NOTIF_ID_BASE_STATUS + Math.abs(fromUid.hashCode() % 400);
            NotificationManagerCompat.from(ctx).notify(notifId, b.build());
        });
    }

    // ── 2. Status viewed ──────────────────────────────────────────────────
    /**
     * Notifies status owner that viewerUid viewed their status.
     * Low-key notification — not every view, batched per ~10 new views.
     * Called from StatusSeenTracker.markSeenWithOwner.
     */
    public static void notifyStatusViewed(String ownerUid,
                                          String statusId,
                                          String viewerUid) {
        // Look up viewer name and fire FCM to owner via server
        FirebaseUtils.getUserRef(viewerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String viewerName  = snap.child("name").getValue(String.class);
                    String viewerPhoto = snap.child("photoUrl").getValue(String.class);
                    if (viewerName == null) viewerName = "Someone";
                    PushNotify.notifyStatusSeen(ownerUid, viewerUid, viewerName,
                        viewerPhoto != null ? viewerPhoto : "", statusId);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // ── 3. Status reaction ────────────────────────────────────────────────
    /**
     * Notifies status owner of an emoji reaction.
     * HIGH importance — feels personal like a DM.
     */
    public static void notifyStatusReaction(String ownerUid,
                                            String statusId,
                                            String reactorUid,
                                            String emoji) {
        FirebaseUtils.getUserRef(reactorUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String name  = snap.child("name").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    if (name == null) name = "Someone";
                    PushNotify.notifyStatusReaction(ownerUid, reactorUid, name,
                        photo != null ? photo : "", emoji, statusId);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // ── 4. Status expiry reminder (local) ────────────────────────────────
    /**
     * Shows a local notification 2h before a status expires.
     * Scheduled by StatusExpiryManager.scheduleExpiryReminder().
     */
    public static void showExpiryReminder(Context ctx,
                                          String statusId,
                                          String text,
                                          String type) {
        String body = "Your status (" + buildStatusBody(type, text) + ") expires in 2 hours.";

        Intent tapIntent = new Intent("com.callx.app.ACTION_OPEN_MY_STATUS");
        tapIntent.setPackage(ctx.getPackageName());
        PendingIntent pi = PendingIntent.getActivity(ctx, statusId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Action: Extend TTL
        Intent extendIntent = new Intent("com.callx.app.ACTION_EXTEND_STATUS");
        extendIntent.putExtra("statusId", statusId);
        extendIntent.setPackage(ctx.getPackageName());
        PendingIntent extendPi = PendingIntent.getBroadcast(ctx, ("extend_" + statusId).hashCode(),
            extendIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_STATUS_EXP)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Status expiring soon")
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "Extend 24h", extendPi)
            .setPriority(NotificationCompat.PRIORITY_LOW);

        NotificationManagerCompat.from(ctx).notify(NOTIF_ID_EXPIRY, b.build());
    }

    // ── 5. Status reply (in-app only — sends via chat) ────────────────────
    /**
     * When someone replies to your status from the viewer screen,
     * a regular chat message is sent. This triggers the normal chat notification.
     * No special handling needed here — handled by existing ChatNotificationHelper.
     */

    // ── Helper ────────────────────────────────────────────────────────────
    private static String buildStatusBody(String type, String text) {
        if (type == null) return text != null ? text : "Status update";
        switch (type) {
            case "image":   return "📷 Photo";
            case "video":   return "🎥 Video";
            case "gif":     return "GIF";
            case "poll":    return "📊 Poll";
            case "link":    return "🔗 Link";
            default:        return text != null && !text.isEmpty() ? text : "Status update";
        }
    }

    private static Bitmap fetchBitmap(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.connect();
            InputStream is = conn.getInputStream();
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            return null;
        }
    }
}
