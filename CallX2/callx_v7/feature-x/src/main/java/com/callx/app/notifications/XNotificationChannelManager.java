package com.callx.app.notifications;

  import android.app.NotificationChannel;
  import android.app.NotificationManager;
  import android.content.Context;
  import android.os.Build;

  /** Creates all notification channels needed by the X module. */
  public class XNotificationChannelManager {

      public static final String CHANNEL_X_GENERAL  = "x_general";
      public static final String CHANNEL_X_MENTIONS  = "x_mentions";
      public static final String CHANNEL_X_DM        = "x_dm";

      public static void ensureChannels(Context ctx) {
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

          NotificationManager nm =
              (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
          if (nm == null) return;

          nm.createNotificationChannel(new NotificationChannel(
              CHANNEL_X_GENERAL, "X — General", NotificationManager.IMPORTANCE_DEFAULT));
          nm.createNotificationChannel(new NotificationChannel(
              CHANNEL_X_MENTIONS, "X — Mentions & Replies", NotificationManager.IMPORTANCE_HIGH));
          nm.createNotificationChannel(new NotificationChannel(
              CHANNEL_X_DM, "X — Direct Messages", NotificationManager.IMPORTANCE_HIGH));
      }
  }