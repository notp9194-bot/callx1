package com.callx.app.notifications;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.social.CollabRepostInboxActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CollabRepostNotificationHelper — Local push notifications for the Collab Repost system.
 *
 * Notification channels:
 *  • CHANNEL_INVITE   — "Collab Repost Invite"   (HIGH importance)
 *  • CHANNEL_RESULT   — "Collab Repost Result"   (DEFAULT importance)
 *
 * Notification types:
 *  • showInviteNotification   — to collaborator: "@user wants to collab repost with you"
 *  • showAcceptedNotification — to initiator:    "@user accepted your collab repost"
 *  • showDeclinedNotification — to initiator:    "@user declined your collab repost"
 *
 * Features:
 *  ✅ Android 8+ notification channels
 *  ✅ Thumbnail loaded via Glide (sync, background thread)
 *  ✅ Deep-link PendingIntent → CollabRepostInboxActivity
 *  ✅ Notification auto-dismiss on tap
 *  ✅ Notification grouping for multiple simultaneous invites
 *  ✅ Runtime permission check (Android 13+)
 */
public class CollabRepostNotificationHelper {

    public static final String CHANNEL_INVITE  = "collab_repost_invite";
    public static final String CHANNEL_RESULT  = "collab_repost_result";
    public static final String GROUP_COLLAB    = "com.callx.app.COLLAB_REPOST";

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    // ── Channel creation ──────────────────────────────────────────────────────
    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Invite channel — high importance so it makes a sound
        NotificationChannel inviteCh = new NotificationChannel(
            CHANNEL_INVITE,
            "Collab Repost Invites",
            NotificationManager.IMPORTANCE_HIGH);
        inviteCh.setDescription("Someone wants to co-repost a reel with you");
        inviteCh.enableVibration(true);
        inviteCh.setShowBadge(true);
        nm.createNotificationChannel(inviteCh);

        // Result channel — accepted/declined
        NotificationChannel resultCh = new NotificationChannel(
            CHANNEL_RESULT,
            "Collab Repost Results",
            NotificationManager.IMPORTANCE_DEFAULT);
        resultCh.setDescription("Updates on your collab repost invites");
        resultCh.setShowBadge(true);
        nm.createNotificationChannel(resultCh);
    }

    // ── Invite notification ────────────────────────────────────────────────────
    /**
     * Shows a notification to the collaborator:
     * "@[senderName] invited you to collab repost a reel!"
     *
     * @param ctx         Application context
     * @param collabId    Collab repost invite ID (used for notification ID + deep link)
     * @param senderName  Initiator's display name
     * @param senderCaption Initiator's caption (shown as body text preview)
     * @param thumbUrl    Reel thumbnail URL (loaded async, falls back to app icon)
     */
    public static void showInviteNotification(
            Context ctx,
            String collabId,
            String senderName,
            String senderCaption,
            String thumbUrl) {

        createChannels(ctx);

        EXECUTOR.execute(() -> {
            Bitmap thumb = loadBitmap(ctx, thumbUrl);

            PendingIntent pi = buildInboxPendingIntent(ctx, collabId, notifIdForCollab(collabId, "invite"));

            String title = "🤝 Collab Repost Invite";
            String body  = "@" + senderName + " invited you to co-repost a reel!";
            String big   = (senderCaption != null && !senderCaption.isEmpty())
                ? body + "\n\"" + senderCaption + "\""
                : body;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_INVITE)
                .setSmallIcon(R.drawable.ic_reels)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(big).setBigContentTitle(title))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setGroup(GROUP_COLLAB)
                .setGroupSummary(false)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

            if (thumb != null)
                builder.setLargeIcon(thumb);

            showNotification(ctx, notifIdForCollab(collabId, "invite"), builder.build());
        });
    }

    // ── Accepted notification ─────────────────────────────────────────────────
    public static void showAcceptedNotification(
            Context ctx,
            String collabId,
            String newReelId,
            String collaboratorName,
            String thumbUrl) {

        createChannels(ctx);

        EXECUTOR.execute(() -> {
            Bitmap thumb = loadBitmap(ctx, thumbUrl);

            PendingIntent pi = buildInboxPendingIntent(ctx, collabId, notifIdForCollab(collabId, "accepted"));

            String title = "✅ Collab Repost Accepted";
            String body  = "@" + collaboratorName + " accepted your collab repost! 🎉 It's live on both profiles.";

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_reels)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setGroup(GROUP_COLLAB);

            if (thumb != null) builder.setLargeIcon(thumb);

            showNotification(ctx, notifIdForCollab(collabId, "accepted"), builder.build());
        });
    }

    // ── Declined notification ─────────────────────────────────────────────────
    public static void showDeclinedNotification(
            Context ctx,
            String collabId,
            String collaboratorName,
            String thumbUrl) {

        createChannels(ctx);

        EXECUTOR.execute(() -> {
            Bitmap thumb = loadBitmap(ctx, thumbUrl);

            PendingIntent pi = buildInboxPendingIntent(ctx, collabId, notifIdForCollab(collabId, "declined"));

            String title = "✕ Collab Repost Declined";
            String body  = "@" + collaboratorName + " declined your collab repost invite.";

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_reels)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setGroup(GROUP_COLLAB);

            if (thumb != null) builder.setLargeIcon(thumb);

            showNotification(ctx, notifIdForCollab(collabId, "declined"), builder.build());
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private static PendingIntent buildInboxPendingIntent(Context ctx, String collabId, int notifId) {
        Intent intent = new Intent(ctx, CollabRepostInboxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (collabId != null) intent.putExtra("highlight_collab_id", collabId);

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;

        return PendingIntent.getActivity(ctx, notifId, intent, flags);
    }

    private static void showNotification(Context ctx, int notifId, Notification notif) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // POST_NOTIFICATIONS permission check
            if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        try {
            NotificationManagerCompat.from(ctx).notify(notifId, notif);
        } catch (SecurityException ignored) {}
    }

    private static Bitmap loadBitmap(Context ctx, String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            return Glide.with(ctx.getApplicationContext())
                .asBitmap()
                .load(url)
                .circleCrop()
                .submit(96, 96)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) { return null; }
    }

    /**
     * Converts a collab ID + event type string into a stable, unique integer notification ID.
     * Uses the same hash-based approach as ReelRepostNotificationHelper.
     */
    private static int notifIdForCollab(String collabId, String type) {
        int base = (collabId != null ? collabId.hashCode() : 0) ^ (type != null ? type.hashCode() : 0);
        return Math.abs(base) + 90000; // offset to avoid collision with other notification IDs
    }

    // ── Alias wrappers used by CollabPostAcceptActivity ──────────────────────

    public static void notifyCollabAccepted(android.content.Context ctx,
                                            String initiatorUid,
                                            String collaboratorUid,
                                            String collaboratorName,
                                            String reelId,
                                            String thumbUrl) {
        showAcceptedNotification(ctx, reelId, reelId, collaboratorName, thumbUrl);
    }

    public static void notifyCollabDeclined(android.content.Context ctx,
                                            String initiatorUid,
                                            String collaboratorUid,
                                            String collaboratorName,
                                            String reelId) {
        showDeclinedNotification(ctx, reelId, collaboratorName, "");
    }

    /**
     * Alias used by CollabPostInviteActivity: sends an invite notification to the collaborator.
     *
     * @param ctx             Application context
     * @param collaboratorUid UID of the user being invited (unused — notification is local)
     * @param initiatorUid    UID of the user sending the invite (unused here)
     * @param initiatorName   Display name of the initiator
     * @param reelId          ID of the reel being co-posted
     * @param inviteId        ID of the collab invite document (used as collabId)
     * @param thumbUrl        Reel thumbnail URL
     */
    public static void notifyCollabInvite(android.content.Context ctx,
                                          String collaboratorUid,
                                          String initiatorUid,
                                          String initiatorName,
                                          String reelId,
                                          String inviteId,
                                          String thumbUrl) {
        showInviteNotification(ctx, inviteId, initiatorName, "", thumbUrl);
    }

}
