package com.callx.app.services;
  import android.app.Notification;
  import android.app.PendingIntent;
  import android.content.Intent;
  import android.graphics.Bitmap;
  import android.graphics.BitmapFactory;
  import android.os.Build;
  import android.os.Handler;
  import android.os.IBinder;
  import android.os.Looper;
  import androidx.annotation.Nullable;
  import androidx.core.graphics.drawable.IconCompat;
  import androidx.core.app.NotificationCompat;
  import androidx.core.app.Person;
  import com.callx.app.activities.CallActivity;
  import com.callx.app.utils.Constants;
  import com.callx.app.utils.FirebaseUtils;
  import com.callx.app.utils.PushNotify;
  import com.google.firebase.auth.FirebaseAuth;
  import java.net.HttpURLConnection;
  import java.net.URL;
  import java.util.concurrent.ExecutorService;
  import java.util.concurrent.Executors;
  import com.callx.app.db.AppDatabase;
  import com.callx.app.db.entity.CallLogEntity;

  /**
   * Foreground service for active 1-to-1 calls.
   *
   * NEW — Static fields exposed so other components (MainActivity banner,
   * IncomingRingService busy-check) can detect an active call without
   * binding to the service.
   *
   * FIX-KILLED v4: onTaskRemoved() now sends missed/dropped call push to remote
   *               party so they get notified even if this device is force-killed.
   */
  public class CallForegroundService extends android.app.Service {

      public static final String EXTRA_PARTNER_UID   = "fg_partner_uid";
      public static final String EXTRA_PARTNER_PHOTO = "fg_partner_photo";
      public static final String EXTRA_DIRECTION     = "fg_direction";
      public static final String EXTRA_IS_CALLER     = "fg_is_caller";

      public static final int ID = Constants.CALL_ONGOING_NOTIF_ID;

      // ── Feature 1: Return-to-call banner + Feature 2: Busy signal ────────
      public static volatile boolean isRunning          = false;
      public static volatile String  activePartnerUid   = "";
      public static volatile String  activePartnerName  = "";
      public static volatile String  activePartnerPhoto = "";
      public static volatile String  activePartnerThumb = "";
      public static volatile String  activeCallId       = "";
      public static volatile boolean activeIsVideo      = false;
      public static volatile boolean activeIsCaller     = false;

      // FIX-KILLED v4: CallActivity sets this to true when WebRTC connects
      // so onTaskRemoved() knows whether it was pre-connect (missed) or mid-call (dropped)
      public static volatile boolean activeCallConnected = false;

      private long startedAt      = 0;
      private Bitmap avatarBitmap = null;
      private final Handler tickHandler = new Handler(Looper.getMainLooper());
      private Runnable tickRunnable;
      private final ExecutorService bgEx = Executors.newSingleThreadExecutor();

      @Override
      public int onStartCommand(Intent intent, int flags, int startId) {
          if (intent != null) {
              String n  = intent.getStringExtra("name");
              String c  = intent.getStringExtra("callId");
              String t  = intent.getStringExtra("partnerThumb");
              String ph = intent.getStringExtra(EXTRA_PARTNER_PHOTO);
              String pu = intent.getStringExtra(EXTRA_PARTNER_UID);
              boolean iv = intent.getBooleanExtra("isVideo", false);
              boolean ic = intent.getBooleanExtra(EXTRA_IS_CALLER, false);

              if (n  != null && !n.isEmpty())  activePartnerName  = n;
              if (c  != null && !c.isEmpty())  activeCallId       = c;
              if (t  != null && !t.isEmpty())  activePartnerThumb = t;
              if (ph != null && !ph.isEmpty()) activePartnerPhoto = ph;
              if (pu != null && !pu.isEmpty()) activePartnerUid   = pu;
              activeIsVideo  = iv;
              activeIsCaller = ic;
          }
          if (startedAt == 0) startedAt = System.currentTimeMillis();

          isRunning = true;

          startForeground(ID, buildNotification("Connecting..."));
          startTicker();

          String avatarUrl = (!activePartnerThumb.isEmpty()) ? activePartnerThumb : activePartnerPhoto;
          if (!avatarUrl.isEmpty()) {
              final String url = avatarUrl;
              bgEx.execute(() -> {
                  try {
                      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                      conn.setConnectTimeout(4000);
                      conn.setReadTimeout(4000);
                      conn.connect();
                      Bitmap bm = BitmapFactory.decodeStream(conn.getInputStream());
                      if (bm != null) {
                          avatarBitmap = bm;
                          tickHandler.post(() -> updateNotification(
                              String.format("%d:%02d",
                                  (System.currentTimeMillis() - startedAt) / 60000,
                                  ((System.currentTimeMillis() - startedAt) / 1000) % 60)));
                      }
                  } catch (Exception ignored) {}
              });
          }
          return START_NOT_STICKY;
      }

      /**
       * FIX-KILLED v4: App force-killed ya swiped away hone par:
       *  1. Firebase mein call "ended" mark karo
       *  2. Remote party ko push notification bhejo (missed/dropped)
       *  3. Local Room DB mein call log save karo
       *
       * Note: bgEx.execute() is fire-and-forget — Firebase aur PushNotify
       * ke async calls complete honge ya nahi, OS decide karta hai.
       * WorkManager fallback ke liye CALL_FIXES_v4.md dekho.
       */
      @Override
      public void onTaskRemoved(Intent rootIntent) {
          isRunning = false;

          final String fPartnerUid  = activePartnerUid;
          final String fCallId      = activeCallId;
          final boolean fIsVideo    = activeIsVideo;
          final boolean fConnected  = activeCallConnected;
          final boolean fIsCaller   = activeIsCaller;
          final String fPartnerName = activePartnerName;
          final long   fStartedAt   = startedAt;

          // ── Step 1: Firebase mein "ended" mark karo ──────────────────────
          if (!fCallId.isEmpty()) {
              try {
                  com.callx.app.utils.FirebaseUtils.db()
                      .getReference("activeCalls")
                      .child(fCallId)
                      .child("status")
                      .setValue("ended");
              } catch (Exception ignored) {}
          }

          // ── Step 2: Remote party ko push notification bhejo ──────────────
          // FIX-KILLED: Yeh pehle bilkul nahi tha — remote user ko pata hi
          // nahi chalta tha ki call end ho gayi jab tak unka WebRTC timeout na ho.
          bgEx.execute(() -> {
              try {
                  com.google.firebase.auth.FirebaseUser me =
                      FirebaseAuth.getInstance().getCurrentUser();
                  if (me != null && !fPartnerUid.isEmpty() && !fCallId.isEmpty()) {
                      String myUid  = me.getUid();
                      String myName = FirebaseUtils.getCurrentName();
                      if (myName == null || myName.isEmpty()) myName = "Someone";
                      // fConnected = true  → call beech mein drop hui (mid-call dropped)
                      // fConnected = false → call connect hone se pehle kill (missed call)
                      // Dono cases mein missed call notification send karo remote ko
                      PushNotify.notifyMissedCall(
                          fPartnerUid, myUid, myName, fCallId, fIsVideo);
                  }
              } catch (Exception ignored) {}
          });

          // ── Step 3: Local Room DB mein call log save karo ────────────────
          if (fStartedAt > 0 && !fPartnerUid.isEmpty()) {
              final long dur  = System.currentTimeMillis() - fStartedAt;
              final String fDir  = fIsCaller ? "outgoing" : "incoming";
              final String fType = fIsVideo  ? "video"    : "audio";
              bgEx.execute(() -> {
                  try {
                      CallLogEntity e = new CallLogEntity();
                      e.id          = java.util.UUID.randomUUID().toString();
                      e.partnerUid  = fPartnerUid;
                      e.partnerName = fPartnerName;
                      e.direction   = fDir;
                      e.mediaType   = fType;
                      e.timestamp   = fStartedAt;
                      e.duration    = dur;
                      AppDatabase.getInstance(getApplicationContext()).callLogDao().insertCallLog(e);
                  } catch (Exception ignored) {}
              });
          }

          stopSelf();
          super.onTaskRemoved(rootIntent);
      }

      private void startTicker() {
          tickRunnable = new Runnable() {
              @Override public void run() {
                  long elapsed = (System.currentTimeMillis() - startedAt) / 1000;
                  updateNotification(String.format("%d:%02d", elapsed / 60, elapsed % 60));
                  tickHandler.postDelayed(this, 1_000);
              }
          };
          tickHandler.postDelayed(tickRunnable, 1_000);
      }

      private void updateNotification(String duration) {
          try {
              android.app.NotificationManager nm =
                  (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
              if (nm != null) nm.notify(ID, buildNotification("Connected \u2022 " + duration));
          } catch (Exception ignored) {}
      }

      private Notification buildNotification(String subtitle) {
          Intent endIntent = new Intent(this, NotificationActionReceiver.class);
          endIntent.setAction(Constants.ACTION_END_CALL);
          endIntent.putExtra(Constants.EXTRA_CALL_ID, activeCallId);
          PendingIntent endPi = PendingIntent.getBroadcast(this, ID + 1, endIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          Intent openIntent = new Intent(this, CallActivity.class);
          openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
              | Intent.FLAG_ACTIVITY_SINGLE_TOP
              | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
          openIntent.putExtra("partnerUid",   activePartnerUid);
          openIntent.putExtra("partnerName",  activePartnerName);
          openIntent.putExtra("partnerPhoto", activePartnerPhoto);
          openIntent.putExtra("partnerThumb", activePartnerThumb);
          openIntent.putExtra("callId",       activeCallId);
          openIntent.putExtra("video",        activeIsVideo);
          openIntent.putExtra("isCaller",     activeIsCaller);
          openIntent.putExtra("isRestore",    true);
          PendingIntent openPi = PendingIntent.getActivity(this, ID, openIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          NotificationCompat.Builder b = new NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
              .setSmallIcon(com.callx.app.calls.R.drawable.ic_call_notification)
              .setContentTitle(activePartnerName)
              .setContentText(subtitle)
              .setOngoing(true)
              .setContentIntent(openPi)
              .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
              .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", endPi);

          if (avatarBitmap != null) b.setLargeIcon(avatarBitmap);

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              Person.Builder pb = new Person.Builder()
                  .setName(activePartnerName).setImportant(true);
              if (avatarBitmap != null) pb.setIcon(IconCompat.createWithBitmap(avatarBitmap));
              b.setStyle(NotificationCompat.CallStyle.forOngoingCall(pb.build(), endPi));
          }
          return b.build();
      }

      @Override
      public void onDestroy() {
          isRunning          = false;
          activeCallConnected = false;
          tickHandler.removeCallbacksAndMessages(null);
          try { bgEx.shutdownNow(); } catch (Exception ignored) {}
          super.onDestroy();
      }

      @Nullable @Override
      public IBinder onBind(Intent intent) { return null; }
  }
  