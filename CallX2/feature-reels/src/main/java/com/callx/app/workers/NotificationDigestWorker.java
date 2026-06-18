package com.callx.app.workers;

import com.callx.app.R;

  import android.app.NotificationChannel;
  import android.app.NotificationManager;
  import android.app.PendingIntent;
  import android.content.Context;
  import android.content.Intent;
  import android.os.Build;
  import androidx.annotation.NonNull;
  import androidx.core.app.NotificationCompat;
  import androidx.work.*;
  import com.callx.app.reels.R;
  
  import com.callx.app.utils.Constants;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.android.gms.tasks.Tasks;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.concurrent.TimeUnit;

  /**
   * NotificationDigestWorker — Periodic unread-message digest notification.
   *
   * Runs every 2 hours when user has unread activity.
   * Shows: "You have X unread messages from Y chats"
   *
   * Features:
   *  ✅ Counts unread 1:1 messages
   *  ✅ Counts unread group messages
   *  ✅ Counts unread reel notifications
   *  ✅ Only fires if user has been inactive > 30 min
   *  ✅ Respects QuietHours setting
   *  ✅ Tapping → opens MainActivity
   *  ✅ Self-cancels if no unread activity
   */
  public class NotificationDigestWorker extends Worker {

      private static final String TAG              = "DigestWorker";
      private static final String CHANNEL_ID       = "callx_digest";
      private static final int    NOTIF_ID         = 99001;
      private static final long   INACTIVE_THRESH  = 30 * 60 * 1000L; // 30 min

      private static final String PREF_LAST_OPEN   = "callx_prefs";
      private static final String KEY_LAST_OPEN    = "last_app_open_ts";

      public NotificationDigestWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
          super(ctx, params);
      }

      @NonNull
      @Override
      public Result doWork() {
          try {
              if (FirebaseAuth.getInstance().getCurrentUser() == null) return Result.success();
              String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

              // Check quiet hours
              com.callx.app.utils.QuietHoursManager qhm =
                  new com.callx.app.utils.QuietHoursManager(getApplicationContext());
              if (qhm.shouldSuppress("message")) return Result.success();

              // Check inactivity
              long lastOpen = getApplicationContext()
                  .getSharedPreferences(PREF_LAST_OPEN, Context.MODE_PRIVATE)
                  .getLong(KEY_LAST_OPEN, 0L);
              long inactive = System.currentTimeMillis() - lastOpen;
              if (inactive < INACTIVE_THRESH) return Result.success();

              // Count unreads
              int[] unreads = { 0 }; // [0]=chats, [1]=groups, [2]=reels
              int[] groups  = { 0 };
              int[] reels   = { 0 };

              // 1:1 unread
              DataSnapshot chatSnap = Tasks.await(
                  FirebaseUtils.getContactsRef(uid).get());
              for (DataSnapshot c : chatSnap.getChildren()) {
                  Long u = c.child("unread").getValue(Long.class);
                  if (u != null && u > 0) unreads[0] += u.intValue();
              }

              // Group unread
              DataSnapshot grpSnap = Tasks.await(
                  FirebaseUtils.getUserGroupsRef(uid).get());
              for (DataSnapshot g : grpSnap.getChildren()) {
                  Long u = g.child("unread").getValue(Long.class);
                  if (u != null && u > 0) groups[0] += u.intValue();
              }

              // Reel notifications unread
              DataSnapshot reelSnap = Tasks.await(
                  FirebaseUtils.db().getReference("reel_notifications").child(uid).get());
              for (DataSnapshot n : reelSnap.getChildren()) {
                  Boolean read = n.child("read").getValue(Boolean.class);
                  if (read == null || !read) reels[0]++;
              }

              int total = unreads[0] + groups[0] + reels[0];
              if (total == 0) return Result.success();

              buildDigestNotification(unreads[0], groups[0], reels[0]);
              return Result.success();

          } catch (Exception e) {
              return Result.retry();
          }
      }

      private void buildDigestNotification(int chats, int groups, int reels) {
          ensureChannel();
          Context ctx = getApplicationContext();

          StringBuilder body = new StringBuilder();
          if (chats  > 0) body.append(chats).append(" unread message").append(chats > 1 ? "s" : "");
          if (groups > 0) { if (body.length() > 0) body.append(" • "); body.append(groups).append(" group message").append(groups > 1 ? "s" : ""); }
          if (reels  > 0) { if (body.length() > 0) body.append(" • "); body.append(reels).append(" reel notification").append(reels > 1 ? "s" : ""); }

          Intent intent = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.activities.MainActivity")
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
          PendingIntent pi = PendingIntent.getActivity(ctx, NOTIF_ID, intent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
              .setSmallIcon(R.drawable.ic_notifications)
              .setContentTitle("You have unread activity on CallX")
              .setContentText(body.toString())
              .setStyle(new NotificationCompat.BigTextStyle().bigText(body.toString()))
              .setPriority(NotificationCompat.PRIORITY_DEFAULT)
              .setAutoCancel(true)
              .setContentIntent(pi);

          NotificationManager nm = (NotificationManager)
              ctx.getSystemService(Context.NOTIFICATION_SERVICE);
          if (nm != null) nm.notify(NOTIF_ID, b.build());
      }

      private void ensureChannel() {
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
          NotificationManager nm = (NotificationManager)
              getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
          if (nm == null) return;
          NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
              "Activity Digest", NotificationManager.IMPORTANCE_DEFAULT);
          ch.setDescription("Periodic summary of unread messages and notifications");
          nm.createNotificationChannel(ch);
      }

      /** Call this from Application or MainActivity to schedule the periodic digest. */
      public static void schedule(Context ctx) {
          PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
              NotificationDigestWorker.class, 2, TimeUnit.HOURS)
              .setConstraints(new Constraints.Builder()
                  .setRequiredNetworkType(NetworkType.CONNECTED)
                  .build())
              .build();
          WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
              "digest_worker",
              ExistingPeriodicWorkPolicy.KEEP,
              req);
      }
  }
  