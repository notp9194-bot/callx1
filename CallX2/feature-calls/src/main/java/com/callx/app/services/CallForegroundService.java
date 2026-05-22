  package com.callx.app.services;
  import android.app.Notification;
  import android.app.PendingIntent;
  import android.content.Intent;
  import android.os.Build;
  import android.os.Handler;
  import android.os.IBinder;
  import android.os.Looper;
  import androidx.annotation.Nullable;
  import androidx.core.app.NotificationCompat;
  import androidx.core.app.Person;
  import com.callx.app.activities.CallActivity;
  import com.callx.app.utils.Constants;
  /**
   * Foreground service shown during an active (connected) call.
   * Shows a persistent CallStyle notification (Android 12+) with an
   * "End Call" action so the user can hang up from the shade.
   * Updated every second with the live call duration.
   *
   * Lifecycle:
   *  start  → CallActivity.onConnected()
   *  stop   → CallActivity.endCall()
   */
  public class CallForegroundService extends android.app.Service {
      public static final int ID = Constants.CALL_ONGOING_NOTIF_ID;
      private String callerName = "CallX";
      private String callId     = "";
      private boolean isVideo   = false;
      private long startedAt    = 0;
      private final Handler tickHandler = new Handler(Looper.getMainLooper());
      private Runnable tickRunnable;
      @Override
      public int onStartCommand(Intent intent, int flags, int startId) {
          if (intent != null) {
              String n = intent.getStringExtra("name");
              String c = intent.getStringExtra("callId");
              if (n != null) callerName = n;
              if (c != null) callId     = c;
              isVideo = intent.getBooleanExtra("isVideo", false);
          }
          startedAt = System.currentTimeMillis();
          startForeground(ID, buildNotification("Connecting..."));
          startTicker();
          return START_STICKY;
      }
      private void startTicker() {
          tickRunnable = new Runnable() {
              @Override public void run() {
                  long elapsed = (System.currentTimeMillis() - startedAt) / 1000;
                  long m = elapsed / 60, s = elapsed % 60;
                  updateNotification(String.format("%d:%02d", m, s));
                  tickHandler.postDelayed(this, 1_000);
              }
          };
          tickHandler.postDelayed(tickRunnable, 1_000);
      }
      private void updateNotification(String duration) {
          try {
              android.app.NotificationManager nm =
                  (android.app.NotificationManager)
                  getSystemService(android.content.Context.NOTIFICATION_SERVICE);
              if (nm != null) nm.notify(ID, buildNotification("Connected • " + duration));
          } catch (Exception ignored) {}
      }
      private Notification buildNotification(String subtitle) {
          // "End Call" broadcast → NotificationActionReceiver
          Intent endIntent = new Intent(this, NotificationActionReceiver.class);
          endIntent.setAction(Constants.ACTION_END_CALL);
          endIntent.putExtra(Constants.EXTRA_CALL_ID, callId);
          PendingIntent endPi = PendingIntent.getBroadcast(this, ID + 1, endIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          // Tap notification → return to CallActivity
          Intent openIntent = new Intent(this, CallActivity.class);
          openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
              | Intent.FLAG_ACTIVITY_SINGLE_TOP);
          PendingIntent openPi = PendingIntent.getActivity(this, ID, openIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          NotificationCompat.Builder b = new NotificationCompat.Builder(
                  this, Constants.CHANNEL_CALLS)
              .setSmallIcon(isVideo
                  ? android.R.drawable.ic_menu_camera
                  : android.R.drawable.ic_menu_call)
              .setContentTitle(callerName)
              .setContentText(subtitle)
              .setOngoing(true)
              .setContentIntent(openPi)
              .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
              .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                  "End Call", endPi);

          // Android 12+ — CallStyle: system shows the green "call chip" on status bar
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              Person person = new Person.Builder()
                  .setName(callerName)
                  .setImportant(true)
                  .build();
              b.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, endPi));
          }
          return b.build();
      }
      @Override
      public void onDestroy() {
          tickHandler.removeCallbacksAndMessages(null);
          super.onDestroy();
      }
      @Nullable @Override
      public IBinder onBind(Intent intent) { return null; }
  }
