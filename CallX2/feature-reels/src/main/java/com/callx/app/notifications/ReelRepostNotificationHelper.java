package com.callx.app.notifications;

import com.callx.app.social.ReelRepostListActivity;
import com.callx.app.workers.ReelRepostWorker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.callx.app.reels.R;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.notifications.ReelNotificationsActivity;

/**
 * ReelRepostNotificationHelper
 *
 * Handles posting local repost-related notifications that survive background kill.
 * Uses WorkManager (via ReelRepostWorker) to queue the actual Firebase write + FCM
 * dispatch, so the notification is delivered even if the app is killed mid-repost.
 *
 * Channel: "reel_repost" — importance HIGH so it shows in the status bar.
 */
public class ReelRepostNotificationHelper {

    public static final String CHANNEL_ID   = "reel_repost";
    public static final String CHANNEL_NAME = "Reel Reposts";
    public static final int    NOTIF_ID_BASE = 9000;

    /** Call once on app start (Application.onCreate). */
    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Notifications for reel reposts");
        ch.enableVibration(true);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    /**
     * Show a local notification confirming the repost was queued.
     * Safe to call from any thread.
     *
     * @param ctx        application context
     * @param reposterId UID of the user who reposted
     * @param reelId     reelId that was reposted
     * @param ownerName  name of the original creator
     */
    public static void notifyRepostQueued(Context ctx,
                                          String reposterId,
                                          String reelId,
                                          String ownerName) {
        createChannel(ctx);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_repost)
            .setContentTitle("Reel Reposted")
            .setContentText("You reposted " + ownerName + "'s reel")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL);

        int notifId = NOTIF_ID_BASE + (reelId != null ? reelId.hashCode() & 0xFFFF : 0);
        try {
            NotificationManagerCompat.from(ctx).notify(notifId, builder.build());
        } catch (SecurityException ignored) {}
    }

    /**
     * Show a notification to the ORIGINAL CREATOR that their reel was reposted.
     * This is triggered via FCM on the recipient device, routed through
     * ReelFCMNotificationHandler → this method.
     *
     * @param ctx         context
     * @param reposterName name of the user who reposted
     * @param reelId      reelId
     */
    public static void notifyOwnerOfRepost(Context ctx,
                                            String reposterName,
                                            String reelId) {
        createChannel(ctx);

        // FIX: Add contentIntent so tapping the notification opens the reel
        Intent tapIntent = new Intent(ctx, SingleReelPlayerActivity.class);
        tapIntent.putExtra("reel_id", reelId != null ? reelId : "");
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int reqCode = NOTIF_ID_BASE + 5000 + (reelId != null ? reelId.hashCode() & 0xFFFF : 0);
        PendingIntent tapPi = PendingIntent.getActivity(ctx, reqCode, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // "View Reposts" action — opens reel notifications tab
        Intent bellIntent = new Intent(ctx, ReelNotificationsActivity.class);
        bellIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent bellPi = PendingIntent.getActivity(ctx, reqCode + 1, bellIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_repost)
            .setContentTitle("Your reel was reposted!")
            .setContentText(reposterName + " reposted your reel")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVibrate(new long[]{0, 250, 100, 250})
            .setContentIntent(tapPi)  // FIX: was missing — notification tap did nothing
            .addAction(R.drawable.ic_repost, "View Reel", tapPi)
            .addAction(R.drawable.ic_repost, "All Reposts", bellPi);

        int notifId = NOTIF_ID_BASE + 5000 + (reelId != null ? reelId.hashCode() & 0xFFFF : 0);
        try {
            NotificationManagerCompat.from(ctx).notify(notifId, builder.build());
        } catch (SecurityException ignored) {}
    }

    // ── v4.2.9 ADDITIONS ─────────────────────────────────────────────────────

    /**
     * Shows a rich repost notification for the original reel creator.
     * Called by ReelFCMNotificationHandler for TYPE_REPOST.
     *
     * @param avatar pre-downloaded circular avatar bitmap (may be null)
     * @param thumb  pre-downloaded reel thumbnail (may be null)
     */
    public static void showRepostNotification(Context ctx,
            String reposterName, String reposterUid, String reposterPhoto,
            String reelId, String thumbUrl, String caption,
            android.graphics.Bitmap avatar, android.graphics.Bitmap thumb) {

        String title = (reposterName != null && !reposterName.isEmpty() ? reposterName : "Someone")
                + " reposted your reel \uD83D\uDD01";
        String body  = (caption != null && !caption.isEmpty())
                ? "\u201c" + caption + "\u201d"
                : "Tap to see who reposted it";

        Intent tapIntent = new Intent(ctx, com.callx.app.player.SingleReelPlayerActivity.class);
        tapIntent.putExtra("reel_id", reelId);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int reqCode = ("repost_notif_" + reelId + reposterUid).hashCode();
        android.app.PendingIntent tapPi = android.app.PendingIntent.getActivity(ctx, reqCode, tapIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

        // Action: View all reposters
        Intent listIntent = new Intent(ctx, com.callx.app.social.ReelRepostListActivity.class);
        listIntent.putExtra("reel_id", reelId);
        listIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.PendingIntent listPi = android.app.PendingIntent.getActivity(ctx, reqCode + 1, listIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

        androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(ctx,
                ReelNotificationChannelManager.CHANNEL_REEL_REPOSTS)
            .setSmallIcon(com.callx.app.reels.R.drawable.ic_repost)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapPi)
            .setColor(0xFF4CAF50)
            .addAction(com.callx.app.reels.R.drawable.ic_repost, "View Reposters", listPi);

        if (avatar != null) b.setLargeIcon(avatar);
        if (thumb  != null) {
            b.setStyle(new androidx.core.app.NotificationCompat.BigPictureStyle()
                .bigPicture(thumb).setSummaryText(body));
        }

        android.app.NotificationManager nm =
            (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(reqCode, b.build());
    }

    /**
     * Shows a quote-repost notification for the original reel creator.
     * Called by ReelFCMNotificationHandler for TYPE_QUOTE_REPOST.
     */
    public static void showQuoteRepostNotification(Context ctx,
            String quoterName, String quoterUid, String reelId,
            String quoteText, android.graphics.Bitmap avatar) {

        String title = (quoterName != null ? quoterName : "Someone")
                + " quoted your reel \uD83D\uDCAC\uD83D\uDD01";
        String body  = (quoteText != null && !quoteText.isEmpty())
                ? "\u201c" + quoteText + "\u201d"
                : "Tap to see the quote repost";

        Intent tapIntent = new Intent(ctx, com.callx.app.player.SingleReelPlayerActivity.class);
        tapIntent.putExtra("reel_id", reelId);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int reqCode = ("quote_repost_notif_" + reelId + quoterUid).hashCode();
        android.app.PendingIntent tapPi = android.app.PendingIntent.getActivity(ctx, reqCode, tapIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

        androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(ctx,
                ReelNotificationChannelManager.CHANNEL_REEL_REPOSTS)
            .setSmallIcon(com.callx.app.reels.R.drawable.ic_repost)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapPi)
            .setColor(0xFF9C27B0);

        if (avatar != null) b.setLargeIcon(avatar);

        android.app.NotificationManager nm =
            (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(reqCode, b.build());
    }

    /**
     * Notifies the reposter that the repost was blocked because the source
     * reel is itself a repost (chain depth exceeded).
     */
    public static void notifyChainBlocked(Context ctx, String reelId) {
        int notifId = ("chain_blocked_" + reelId).hashCode();
        androidx.core.app.NotificationCompat.Builder b =
            new androidx.core.app.NotificationCompat.Builder(ctx,
                ReelNotificationChannelManager.CHANNEL_REEL_REPOSTS)
            .setSmallIcon(com.callx.app.reels.R.drawable.ic_reels)
            .setContentTitle("Can\'t repost this reel")
            .setContentText("This reel is already a repost. Only original reels can be reposted.")
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setColor(0xFFFF3B5C);

        android.app.NotificationManager nm =
            (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifId, b.build());
    }


}