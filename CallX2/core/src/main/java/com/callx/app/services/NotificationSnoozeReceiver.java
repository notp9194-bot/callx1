package com.callx.app.services;

  import android.app.AlarmManager;
  import android.app.NotificationManager;
  import android.app.PendingIntent;
  import android.content.BroadcastReceiver;
  import android.content.Context;
  import android.content.Intent;
  import android.os.Build;
  import androidx.core.app.NotificationCompat;
  import com.callx.app.core.R;
  import com.callx.app.utils.Constants;

  /**
   * NotificationSnoozeReceiver — Handles chat notification snooze actions.
   *
   * Actions:
   *  SNOOZE_1H   — dismiss now, re-show after 1 hour
   *  SNOOZE_8H   — dismiss now, re-show after 8 hours
   *  SNOOZE_24H  — dismiss now, re-show after 24 hours
   *  SNOOZE_FIRE — fires when snooze window expires, rebuilds notification
   */
  public class NotificationSnoozeReceiver extends BroadcastReceiver {

      public static final String ACTION_SNOOZE_1H  = "com.callx.app.SNOOZE_1H";
      public static final String ACTION_SNOOZE_8H  = "com.callx.app.SNOOZE_8H";
      public static final String ACTION_SNOOZE_24H = "com.callx.app.SNOOZE_24H";
      public static final String ACTION_SNOOZE_FIRE = "com.callx.app.SNOOZE_FIRE";

      public static final String EXTRA_CHAT_ID      = "snooze_chat_id";
      public static final String EXTRA_PARTNER_NAME = "snooze_partner_name";
      public static final String EXTRA_PARTNER_UID  = "snooze_partner_uid";
      public static final String EXTRA_NOTIF_ID     = "snooze_notif_id";
      public static final String EXTRA_MESSAGE      = "snooze_message";

      @Override
      public void onReceive(Context context, Intent intent) {
          if (intent == null || intent.getAction() == null) return;
          String action = intent.getAction();
          int notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0);

          if (ACTION_SNOOZE_FIRE.equals(action)) {
              // Time to re-show the snoozed notification
              String partnerName = intent.getStringExtra(EXTRA_PARTNER_NAME);
              String chatId      = intent.getStringExtra(EXTRA_CHAT_ID);
              String message     = intent.getStringExtra(EXTRA_MESSAGE);
              reshowNotification(context, notifId, partnerName, chatId, message);
              return;
          }

          // Cancel the original notification
          NotificationManager nm = (NotificationManager)
              context.getSystemService(Context.NOTIFICATION_SERVICE);
          if (nm != null) nm.cancel(notifId);

          // Determine snooze duration
          long delayMs;
          if      (ACTION_SNOOZE_1H.equals(action))  delayMs = 60 * 60 * 1000L;
          else if (ACTION_SNOOZE_8H.equals(action))  delayMs = 8 * 60 * 60 * 1000L;
          else if (ACTION_SNOOZE_24H.equals(action)) delayMs = 24 * 60 * 60 * 1000L;
          else return;

          // Schedule AlarmManager to re-fire
          scheduleSnooze(context, intent, notifId, delayMs);
      }

      private void scheduleSnooze(Context ctx, Intent original, int notifId, long delayMs) {
          Intent fireIntent = new Intent(ctx, NotificationSnoozeReceiver.class)
              .setAction(ACTION_SNOOZE_FIRE)
              .putExtra(EXTRA_NOTIF_ID,     notifId)
              .putExtra(EXTRA_CHAT_ID,      original.getStringExtra(EXTRA_CHAT_ID))
              .putExtra(EXTRA_PARTNER_NAME, original.getStringExtra(EXTRA_PARTNER_NAME))
              .putExtra(EXTRA_PARTNER_UID,  original.getStringExtra(EXTRA_PARTNER_UID))
              .putExtra(EXTRA_MESSAGE,      original.getStringExtra(EXTRA_MESSAGE));

          PendingIntent pi = PendingIntent.getBroadcast(ctx, notifId,
              fireIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
          if (am == null) return;
          long triggerAt = System.currentTimeMillis() + delayMs;
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
              am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
          } else {
              am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
          }
      }

      private void reshowNotification(Context ctx, int notifId,
                                       String partnerName, String chatId, String message) {
          if (partnerName == null) partnerName = "Message";
          if (message == null)    message = "You have a snoozed message";

          Intent openIntent = new Intent();
          openIntent.setClassName(ctx, "com.callx.app.conversation.ChatActivity");
          openIntent.putExtra(Constants.EXTRA_CHAT_ID, chatId);
          openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
          PendingIntent pi = PendingIntent.getActivity(ctx, notifId, openIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Constants.CHANNEL_MESSAGES)
              .setSmallIcon(R.drawable.ic_message_notification)
              .setContentTitle("⏰ " + partnerName)
              .setContentText(message)
              .setPriority(NotificationCompat.PRIORITY_HIGH)
              .setAutoCancel(true)
              .setContentIntent(pi);

          NotificationManager nm = (NotificationManager)
              ctx.getSystemService(Context.NOTIFICATION_SERVICE);
          if (nm != null) nm.notify(notifId, b.build());
      }

      /** Build Snooze action PendingIntents for use in notification builders. */
      public static PendingIntent snoozePi(Context ctx, String action, int notifId,
                                            String chatId, String partnerName,
                                            String partnerUid, String message) {
          Intent i = new Intent(ctx, NotificationSnoozeReceiver.class)
              .setAction(action)
              .putExtra(EXTRA_NOTIF_ID,     notifId)
              .putExtra(EXTRA_CHAT_ID,      chatId)
              .putExtra(EXTRA_PARTNER_NAME, partnerName)
              .putExtra(EXTRA_PARTNER_UID,  partnerUid)
              .putExtra(EXTRA_MESSAGE,      message);
          return PendingIntent.getBroadcast(ctx, (action + notifId).hashCode(), i,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      }
  }
  