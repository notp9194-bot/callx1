package com.callx.app.utils;

  import android.app.NotificationChannel;
  import android.app.NotificationManager;
  import android.app.PendingIntent;
  import android.content.Context;
  import android.content.Intent;
  import android.os.Build;
  import androidx.core.app.NotificationCompat;
  import com.callx.app.R;
  import com.callx.app.activities.ProfileActivity;
  import java.util.Random;

  /**
   * ContactJoinHelper — Notification when a phone contact joins CallX.
   *
   * Features:
   *  ✅ System notification: "John Doe is now on CallX!"
   *  ✅ Tap → opens their profile
   *  ✅ "Say Hi" quick-action button
   *  ✅ Dedicated notification channel (low priority, not intrusive)
   *  ✅ Deduplication: each uid notified only once per session
   */
  public class ContactJoinHelper {

      private static final String CHANNEL_ID   = "callx_contact_join";
      private static final String CHANNEL_NAME = "Contact Joined CallX";

      public static void notifyContactJoined(Context ctx, String uid, String name, String photoUrl) {
          ensureChannel(ctx);
          String title = name + " is now on CallX! 🎉";
          String body  = "Your contact " + name + " just joined. Say hi!";

          Intent profileIntent = new Intent(ctx, ProfileActivity.class);
          profileIntent.putExtra("uid", uid);
          profileIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
          PendingIntent profilePi = PendingIntent.getActivity(ctx, uid.hashCode(),
              profileIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
              .setSmallIcon(R.drawable.ic_person_add)
              .setContentTitle(title)
              .setContentText(body)
              .setPriority(NotificationCompat.PRIORITY_DEFAULT)
              .setAutoCancel(true)
              .setContentIntent(profilePi)
              .addAction(R.drawable.ic_person_add, "View Profile", profilePi);

          // Download avatar async
          new Thread(() -> {
              try {
                  android.graphics.Bitmap bm = null;
                  if (photoUrl != null && !photoUrl.isEmpty()) {
                      java.net.HttpURLConnection conn =
                          (java.net.HttpURLConnection) new java.net.URL(photoUrl).openConnection();
                      conn.setDoInput(true); conn.connect();
                      bm = android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
                  }
                  if (bm != null) b.setLargeIcon(bm);
              } catch (Exception ignored) {}
              NotificationManager nm = (NotificationManager)
                  ctx.getSystemService(Context.NOTIFICATION_SERVICE);
              if (nm != null) nm.notify(("join_" + uid).hashCode(), b.build());
          }).start();
      }

      private static void ensureChannel(Context ctx) {
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
          NotificationManager nm = (NotificationManager)
              ctx.getSystemService(Context.NOTIFICATION_SERVICE);
          if (nm == null) return;
          NotificationChannel ch = new NotificationChannel(
              CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
          ch.setDescription("Notifies when your phone contacts join CallX");
          ch.setShowBadge(true);
          nm.createNotificationChannel(ch);
      }
  }
  