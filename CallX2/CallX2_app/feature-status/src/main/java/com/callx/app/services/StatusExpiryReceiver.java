package com.callx.app.services;
  import android.app.NotificationChannel;
  import android.app.NotificationManager;
  import android.content.BroadcastReceiver;
  import android.content.Context;
  import android.content.Intent;
  import android.os.Build;
  import androidx.core.app.NotificationCompat;
  import com.callx.app.status.R;
  import com.callx.app.compose.NewStatusActivity;
  /**
   * StatusExpiryReceiver — Fires 2h before a user's status expires.
   * Reminds them to post a new status or extend it.
   */
  public class StatusExpiryReceiver extends BroadcastReceiver {
      private static final String CHANNEL_ID = "callx_status_expiry";
      @Override
      public void onReceive(Context ctx, Intent intent) {
          if (intent == null) return;
          String statusId = intent.getStringExtra("status_id");
          ensureChannel(ctx);
          android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx,
              (statusId != null ? statusId : "exp").hashCode(),
              new Intent(ctx, NewStatusActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              android.app.PendingIntent.FLAG_UPDATE_CURRENT |
              android.app.PendingIntent.FLAG_IMMUTABLE);
          NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
              .setSmallIcon(R.drawable.ic_status_notification)
              .setContentTitle("Your status is expiring soon!")
              .setContentText("Tap to post a new status before it disappears")
              .setPriority(NotificationCompat.PRIORITY_DEFAULT)
              .setAutoCancel(true)
              .setContentIntent(pi)
              .addAction(R.drawable.ic_camera, "Post New Status", pi);
          NotificationManager nm = (NotificationManager)
              ctx.getSystemService(Context.NOTIFICATION_SERVICE);
          if (nm != null) nm.notify("status_expiry".hashCode(), b.build());
      }
      private void ensureChannel(Context ctx) {
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
          NotificationManager nm = (NotificationManager)
              ctx.getSystemService(Context.NOTIFICATION_SERVICE);
          if (nm == null) return;
          NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
              "Status Expiry Reminder", NotificationManager.IMPORTANCE_DEFAULT);
          ch.setDescription("Reminds you before your status expires");
          nm.createNotificationChannel(ch);
      }
  }
  