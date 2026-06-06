package com.callx.app.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.callx.app.feed.XActivity;
import com.callx.app.messages.XDMConversationActivity;
import com.callx.app.tweet.XTweetDetailActivity;
import com.callx.app.x.R;
import java.util.concurrent.Executors;

/**
 * XNotificationHelper — Simple notification builder.
 *
 * FIX: postTweetInteraction() aur postDM() ab Executor thread me run karte hain
 * aur XFCMNotificationHandler.downloadCircle() se avatar properly download karte hain.
 * Pehle fromPhotoUrl ignore ho raha tha — ab large icon (avatar) notification me dikh ta hai.
 */
public class XNotificationHelper {

    private static int notifId = 9000;

    /**
     * Tweet interaction notification — like, retweet, reply, mention, quote, follow.
     *
     * FIX: fromPhotoUrl se avatar download karke setLargeIcon() se set kiya jata hai.
     * Executor thread pe run hota hai — main thread block nahi hoti.
     */
    public static void postTweetInteraction(Context ctx, String type,
                                            String fromName, String fromPhotoUrl,
                                            String tweetId) {
        final int id = notifId++;
        Executors.newSingleThreadExecutor().execute(() -> {
            // Avatar download (network-aware — 2G/no-network pe null)
            int netLevel = XFCMNotificationHandler.getNetworkLevel(ctx);
            Bitmap avatar = (netLevel >= 2 && fromPhotoUrl != null && !fromPhotoUrl.isEmpty())
                    ? XFCMNotificationHandler.downloadCircle(fromPhotoUrl, 100)
                    : null;

            String title;
            switch (type != null ? type : "") {
                case "like":    title = fromName + " liked your post";    break;
                case "retweet": title = fromName + " reposted your post"; break;
                case "reply":   title = fromName + " replied";            break;
                case "mention": title = fromName + " mentioned you";      break;
                case "quote":   title = fromName + " quoted your post";   break;
                case "follow":  title = fromName + " followed you";       break;
                default:        title = fromName + " interacted with you";
            }

            Intent intent = (tweetId != null && !tweetId.isEmpty())
                    ? new Intent(ctx, XTweetDetailActivity.class).putExtra("tweet_id", tweetId)
                    : new Intent(ctx, XActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent pi = PendingIntent.getActivity(ctx, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                    XNotificationChannelManager.CHANNEL_X_MENTIONS)
                    .setSmallIcon(R.drawable.ic_x_logo)
                    .setContentTitle(title)
                    .setContentText("")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);

            if (avatar != null) b.setLargeIcon(avatar);  // ← avatar set hoga

            try {
                NotificationManagerCompat.from(ctx).notify(id, b.build());
            } catch (SecurityException ignored) {}
        });
    }

    /**
     * DM notification.
     *
     * FIX: fromPhotoUrl se avatar download karke setLargeIcon() se set kiya jata hai.
     */
    public static void postDM(Context ctx, String fromName, String fromPhotoUrl,
                              String conversationId, String otherUid,
                              String otherHandle, String otherPhoto, String preview) {
        final int id = notifId++;
        Executors.newSingleThreadExecutor().execute(() -> {
            // Avatar download
            int netLevel = XFCMNotificationHandler.getNetworkLevel(ctx);
            Bitmap avatar = (netLevel >= 2 && fromPhotoUrl != null && !fromPhotoUrl.isEmpty())
                    ? XFCMNotificationHandler.downloadCircle(fromPhotoUrl, 100)
                    : null;

            Intent intent = new Intent(ctx, XDMConversationActivity.class)
                    .putExtra("conversation_id", conversationId)
                    .putExtra("other_uid", otherUid)
                    .putExtra("other_name", fromName)
                    .putExtra("other_handle", otherHandle)
                    .putExtra("other_photo", otherPhoto)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent pi = PendingIntent.getActivity(ctx, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                    XNotificationChannelManager.CHANNEL_X_DM)
                    .setSmallIcon(R.drawable.ic_x_dm)
                    .setContentTitle(fromName)
                    .setContentText(preview != null && !preview.isEmpty() ? preview : "New message")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);

            if (avatar != null) b.setLargeIcon(avatar);  // ← avatar set hoga

            try {
                NotificationManagerCompat.from(ctx).notify(id, b.build());
            } catch (SecurityException ignored) {}
        });
    }
}
