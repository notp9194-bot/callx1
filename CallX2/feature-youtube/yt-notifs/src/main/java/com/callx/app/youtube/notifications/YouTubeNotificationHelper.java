package com.callx.app.youtube.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.callx.app.youtube.notifications.YouTubeNotificationsActivity; // use YTNavigatorProvider for cross-module nav
// Open via YTNavigatorProvider.get().openPlayer()
import com.callx.app.youtube.notifications.R;
import java.io.InputStream;
import java.net.URL;

/**
 * YouTubeNotificationHelper — v3 (Like avatar + Like HUN fix)
 *
 * v3 fix: postLikeMilestone() ab avatarUrl accept karta hai.
 * Avatar background thread pe load karo aur yahan pass karo.
 * Builder pe PRIORITY_HIGH + setDefaults → HUN dikhega.
 */
public class YouTubeNotificationHelper {

    private static int notifId = 7000;

    // ── New video ─────────────────────────────────────────────────────────────
    public static void postNewVideo(Context ctx, String channelName,
                                    String videoTitle, String thumbnailUrl,
                                    String videoId) {
        Intent intent = new Intent(ctx, YouTubePlayerActivity.class)
            .putExtra("video_id", videoId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(ctx, notifId,
            intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(
                ctx, YouTubeNotificationChannelManager.CHANNEL_YT_SUBSCRIPTIONS)
            .setSmallIcon(R.drawable.ic_youtube_logo)
            .setContentTitle(channelName + " uploaded a new video")
            .setContentText(videoTitle)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL);

        Bitmap thumb = loadBitmap(thumbnailUrl);
        if (thumb != null) {
            b.setLargeIcon(thumb);
            b.setStyle(new NotificationCompat.BigPictureStyle()
                .bigPicture(thumb)
                .bigLargeIcon((Bitmap) null));
        }

        postNotif(ctx, b.build());
    }

    // ── Comment ───────────────────────────────────────────────────────────────
    public static void postComment(Context ctx, String commenterName,
                                   String videoId, String videoTitle,
                                   String commentPreview) {
        Intent intent = new Intent(ctx, YouTubePlayerActivity.class)
            .putExtra("video_id", videoId)
            .putExtra("open_comments", true)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(ctx, notifId,
            intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        postNotif(ctx, new NotificationCompat.Builder(
                ctx, YouTubeNotificationChannelManager.CHANNEL_YT_COMMENTS)
            .setSmallIcon(R.drawable.ic_youtube_logo)
            .setContentTitle(commenterName + " commented on your video")
            .setContentText(commentPreview)
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText("\"" + commentPreview + "\"\n" + videoTitle))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build());
    }

    // ── Reply ─────────────────────────────────────────────────────────────────
    public static void postReply(Context ctx, String replierName,
                                 String videoId, String replyPreview) {
        Intent intent = new Intent(ctx, YouTubePlayerActivity.class)
            .putExtra("video_id", videoId)
            .putExtra("open_comments", true)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(ctx, notifId,
            intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        postNotif(ctx, new NotificationCompat.Builder(
                ctx, YouTubeNotificationChannelManager.CHANNEL_YT_COMMENTS)
            .setSmallIcon(R.drawable.ic_youtube_logo)
            .setContentTitle(replierName + " replied to your comment")
            .setContentText(replyPreview)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build());
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────
    public static void postSubscribe(Context ctx, String subscriberName,
                                     String subscriberPhotoUrl) {
        Intent intent = new Intent(ctx, YouTubeActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(ctx, notifId,
            intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        postNotif(ctx, new NotificationCompat.Builder(
                ctx, YouTubeNotificationChannelManager.CHANNEL_YT_GENERAL)
            .setSmallIcon(R.drawable.ic_youtube_logo)
            .setContentTitle(subscriberName + " subscribed to your channel")
            .setContentText("You have a new subscriber!")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build());
    }

    // ── Live ──────────────────────────────────────────────────────────────────
    public static void postLive(Context ctx, String channelName,
                                String videoId, String streamTitle) {
        Intent intent = new Intent(ctx, YouTubePlayerActivity.class)
            .putExtra("video_id", videoId)
            .putExtra("is_live", true)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(ctx, notifId,
            intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        postNotif(ctx, new NotificationCompat.Builder(
                ctx, YouTubeNotificationChannelManager.CHANNEL_YT_LIVE)
            .setSmallIcon(R.drawable.ic_youtube_logo)
            .setContentTitle(channelName + " is LIVE now!")
            .setContentText(streamTitle)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build());
    }

    // ── Like milestone — v3: avatarBitmap param added ─────────────────────────
    /**
     * @param avatarBitmap  Background thread pe pehle download karo, yahan pass karo.
     *                      null pass karo agar download fail ho ya network nahi ho.
     */
    public static void postLikeMilestone(Context ctx, String videoTitle,
                                         long likeCount, String videoId,
                                         Bitmap avatarBitmap) {
        Intent intent = new Intent(ctx, YouTubePlayerActivity.class)
            .putExtra("video_id", videoId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(ctx, notifId,
            intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(
                ctx, YouTubeNotificationChannelManager.CHANNEL_YT_LIKES)
            .setSmallIcon(R.drawable.ic_youtube_logo)
            .setContentTitle("🎉 " + likeCount + " likes on your video!")
            .setContentText(videoTitle)
            .setContentIntent(pi)
            .setAutoCancel(true)
            // ── v3 fix: PRIORITY_HIGH → HUN ──
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL);

        // ── v3 fix: avatar set karo ──
        if (avatarBitmap != null) b.setLargeIcon(avatarBitmap);

        postNotif(ctx, b.build());
    }

    /**
     * Legacy overload — purane callers ke liye backward compatible.
     * Avatar nahi dikhega, HUN dikhega (PRIORITY_HIGH).
     */
    public static void postLikeMilestone(Context ctx, String videoTitle,
                                         long likeCount, String videoId) {
        postLikeMilestone(ctx, videoTitle, likeCount, videoId, null);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────
    private static void postNotif(Context ctx, Notification n) {
        try { NotificationManagerCompat.from(ctx).notify(notifId++, n); }
        catch (SecurityException ignored) {}
    }

    private static Bitmap loadBitmap(String url) {
        if (url == null || url.isEmpty()) return null;
        try (InputStream in = new URL(url).openStream()) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) { return null; }
    }
}
