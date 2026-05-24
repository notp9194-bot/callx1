package com.callx.app.notifications;

  import android.app.Notification;
  import android.app.PendingIntent;
  import android.content.Context;
  import android.content.Intent;
  import android.graphics.Bitmap;
  import android.graphics.BitmapFactory;
  import androidx.core.app.NotificationCompat;
  import androidx.core.app.NotificationManagerCompat;
  import com.callx.app.activities.XActivity;
  import com.callx.app.activities.XDMConversationActivity;
  import com.callx.app.activities.XTweetDetailActivity;
  import com.callx.app.x.R;
  import java.io.InputStream;
  import java.net.URL;

  /** Builds and posts X system notifications (survived in killed state via WorkManager). */
  public class XNotificationHelper {

      private static int notifId = 9000;

      public static void postTweetInteraction(Context ctx, String type,
                                              String fromName, String fromPhotoUrl,
                                              String tweetId) {
          String title, body;
          switch (type != null ? type : "") {
              case "like":    title = fromName + " liked your post";    body = ""; break;
              case "retweet": title = fromName + " reposted your post"; body = ""; break;
              case "reply":   title = fromName + " replied";            body = ""; break;
              case "mention": title = fromName + " mentioned you";      body = ""; break;
              case "quote":   title = fromName + " quoted your post";   body = ""; break;
              case "follow":  title = fromName + " followed you";       body = ""; break;
              default:        title = fromName + " interacted with you"; body = "";
          }

          Intent intent = tweetId != null
              ? new Intent(ctx, XTweetDetailActivity.class).putExtra("tweet_id", tweetId)
              : new Intent(ctx, XActivity.class);
          intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

          PendingIntent pi = PendingIntent.getActivity(ctx, notifId,
              intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          Notification notif = new NotificationCompat.Builder(ctx, XNotificationChannelManager.CHANNEL_X_MENTIONS)
              .setSmallIcon(R.drawable.ic_x_logo)
              .setContentTitle(title)
              .setContentText(body)
              .setContentIntent(pi)
              .setAutoCancel(true)
              .setPriority(NotificationCompat.PRIORITY_HIGH)
              .build();

          try {
              NotificationManagerCompat.from(ctx).notify(notifId++, notif);
          } catch (SecurityException ignored) {}
      }

      public static void postDM(Context ctx, String fromName, String fromPhotoUrl,
                                String conversationId, String otherUid,
                                String otherHandle, String otherPhoto, String preview) {
          Intent intent = new Intent(ctx, XDMConversationActivity.class)
              .putExtra("conversation_id", conversationId)
              .putExtra("other_uid", otherUid)
              .putExtra("other_name", fromName)
              .putExtra("other_handle", otherHandle)
              .putExtra("other_photo", otherPhoto)
              .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

          PendingIntent pi = PendingIntent.getActivity(ctx, notifId,
              intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          Notification notif = new NotificationCompat.Builder(ctx, XNotificationChannelManager.CHANNEL_X_DM)
              .setSmallIcon(R.drawable.ic_x_dm)
              .setContentTitle(fromName)
              .setContentText(preview != null ? preview : "New message")
              .setContentIntent(pi)
              .setAutoCancel(true)
              .setPriority(NotificationCompat.PRIORITY_HIGH)
              .build();

          try {
              NotificationManagerCompat.from(ctx).notify(notifId++, notif);
          } catch (SecurityException ignored) {}
      }

      private static Bitmap loadBitmap(String url) {
          try {
              return BitmapFactory.decodeStream((InputStream) new URL(url).getContent());
          } catch (Exception e) { return null; }
      }
  }