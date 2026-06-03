package com.callx.app.services;

  import android.app.Notification;
  import android.app.NotificationManager;
  import android.app.PendingIntent;
  import android.app.Service;
  import android.content.Intent;
  import android.os.Build;
  import android.os.Handler;
  import android.os.IBinder;
  import android.os.Looper;
  import androidx.annotation.Nullable;
  import androidx.core.app.NotificationCompat;
  import com.callx.app.calls.R;
  import com.callx.app.activities.GroupCallActivity;
  import com.callx.app.db.AppDatabase;
  import com.callx.app.db.entity.CallLogEntity;
  import com.callx.app.utils.Constants;
  import com.callx.app.utils.FirebaseUtils;
  import com.callx.app.utils.PushNotify;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.auth.FirebaseUser;

  /**
   * GroupCallForegroundService — ongoing notification while group call is active.
   *
   * FIX-KILLED v4:
   *  - onTaskRemoved() → agar isCaller hai, toh call "ended" mark karo sabke liye
   *  - sabhi participants ko missed call push bhejo
   *  - Room DB mein call log save karo
   */
  public class GroupCallForegroundService extends Service {

      public static final String EXTRA_GROUP_NAME        = "fg_group_name";
      public static final String EXTRA_CALL_ID           = "fg_call_id";
      public static final String EXTRA_IS_VIDEO          = "fg_is_video";
      public static final String EXTRA_PARTICIPANT_COUNT = "fg_participant_count";
      public static final String EXTRA_MY_UID            = "fg_my_uid";
      public static final String EXTRA_GROUP_ID          = "fg_group_id";
      public static final String EXTRA_IS_CALLER         = "fg_is_caller";

      private String  groupName        = "";
      private String  callId           = "";
      private String  myUid            = "";
      private String  groupId          = "";
      private boolean isCaller         = false;
      private boolean isVideo          = false;
      private int     participantCount = 2;
      private long    startedAt        = 0;

      private final Handler tickHandler = new Handler(Looper.getMainLooper());
      private Runnable tickRunnable;
      private final java.util.concurrent.ExecutorService bgEx =
          java.util.concurrent.Executors.newSingleThreadExecutor();

      @Override
      public int onStartCommand(Intent intent, int flags, int startId) {
          if (intent != null) {
              String gn = intent.getStringExtra(EXTRA_GROUP_NAME);
              String ci = intent.getStringExtra(EXTRA_CALL_ID);
              String mu = intent.getStringExtra(EXTRA_MY_UID);
              if (gn != null) groupName        = gn;
              if (ci != null) callId           = ci;
              if (mu != null) myUid            = mu;
              String gi = intent.getStringExtra(EXTRA_GROUP_ID);
              if (gi != null) groupId = gi;
              isCaller         = intent.getBooleanExtra(EXTRA_IS_CALLER, false);
              isVideo          = intent.getBooleanExtra(EXTRA_IS_VIDEO, false);
              participantCount = intent.getIntExtra(EXTRA_PARTICIPANT_COUNT, 2);
          }
          if (startedAt == 0) startedAt = System.currentTimeMillis();
          startForeground(Constants.GROUP_CALL_ONGOING_NOTIF_ID, buildNotification("0:00"));
          if (tickRunnable == null) startTicker();
          return START_NOT_STICKY;
      }

      /**
       * FIX-KILLED v4: App force-kill ya swipe par:
       *  1. Apna participant status "left" mark karo
       *  2. Agar isCaller (host) hai → call status "ended" set karo sabke liye
       *     (pehle sirf "left" likhte the — baaki sab hang ho jaate the)
       *  3. Missed group call push — GroupCallActionReceiver se nahi chal sakta kyunki process dead hai
       *  4. Room DB mein call log save karo
       */
      @Override
      public void onTaskRemoved(Intent rootIntent) {
          final String fCallId    = callId;
          final String fMyUid     = myUid;
          final String fGroupId   = groupId;
          final String fGroupName = groupName;
          final boolean fIsCaller = isCaller;
          final boolean fIsVideo  = isVideo;
          final long    fStartAt  = startedAt;

          if (fCallId != null && !fCallId.isEmpty()) {
              try {
                  com.google.firebase.database.DatabaseReference callRef =
                      FirebaseUtils.db().getReference("groupCalls").child(fCallId);

                  // ── Step 1: Apna "left" status mark karo ─────────────────
                  if (fMyUid != null && !fMyUid.isEmpty()) {
                      callRef.child("participants").child(fMyUid).child("status").setValue("left");
                  }

                  // ── Step 2 (FIX-KILLED NEW): Host kill → call sabke liye end karo ──
                  // Pehle yeh bilkul nahi tha. Host ke kill hone par baaki sab ka
                  // screen hanging reh jaata tha — unka Firebase listener
                  // "status=ended" change kabhi nahi paata tha.
                  if (fIsCaller) {
                      callRef.child("status").setValue("ended");
                  }
              } catch (Exception ignored) {}
          }

          // ── Step 3 (FIX-KILLED NEW): Missed group call push ──────────────
          // Background thread mein try karo — OS kill karne se pehle kuch ms milte hain
          bgEx.execute(() -> {
              try {
                  FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
                  if (me != null && fGroupId != null && !fGroupId.isEmpty()
                          && fCallId != null && !fCallId.isEmpty()) {
                      String myName = FirebaseUtils.getCurrentName();
                      if (myName == null || myName.isEmpty()) myName = "Someone";
                      // Group missed call — baaki participants ko notify karo
                      PushNotify.notifyMissedGroupCall(
                          fGroupId, me.getUid(), myName, fCallId, fIsVideo);
                  }
              } catch (Exception ignored) {}
          });

          // ── Step 4: Room DB mein call log save karo ──────────────────────
          if (fStartAt > 0 && !fGroupId.isEmpty()) {
              final long dur     = System.currentTimeMillis() - fStartAt;
              final String fDir  = fIsCaller ? "outgoing" : "incoming";
              final String fType = fIsVideo  ? "group_video" : "group_audio";
              bgEx.execute(() -> {
                  try {
                      CallLogEntity entity = new CallLogEntity();
                      entity.id          = java.util.UUID.randomUUID().toString();
                      entity.partnerUid  = fGroupId;
                      entity.partnerName = fGroupName != null ? fGroupName : "";
                      entity.direction   = fDir;
                      entity.mediaType   = fType;
                      entity.timestamp   = fStartAt;
                      entity.duration    = dur;
                      AppDatabase.getInstance(getApplicationContext())
                          .callLogDao().insertCallLog(entity);
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
                  long m = elapsed / 60, s = elapsed % 60;
                  String duration = String.format("%d:%02d", m, s);
                  try {
                      NotificationManager nm =
                          (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                      if (nm != null)
                          nm.notify(Constants.GROUP_CALL_ONGOING_NOTIF_ID, buildNotification(duration));
                  } catch (Exception ignored) {}
                  tickHandler.postDelayed(this, 1_000);
              }
          };
          tickHandler.postDelayed(tickRunnable, 1_000);
      }

      private Notification buildNotification(String duration) {
          Intent tapIntent = new Intent(this, GroupCallActivity.class);
          tapIntent.putExtra(GroupCallActivity.EXTRA_CALL_ID, callId);
          tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
          PendingIntent tapPi = PendingIntent.getActivity(this,
              Constants.GROUP_CALL_ONGOING_NOTIF_ID, tapIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          Intent endIntent = new Intent(this, GroupCallActionReceiver.class);
          endIntent.setAction(Constants.ACTION_GROUP_END_CALL);
          endIntent.putExtra(Constants.EXTRA_CALL_ID, callId);
          PendingIntent endPi = PendingIntent.getBroadcast(this,
              Constants.GROUP_CALL_ONGOING_NOTIF_ID + 1, endIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          String title = (groupName != null && !groupName.isEmpty()) ? groupName : "Group Call";
          String text  = (isVideo ? "Video call" : "Voice call")
              + " \u2022 " + duration
              + " \u2022 " + participantCount + " participant"
              + (participantCount == 1 ? "" : "s");

          int smallIconRes = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
          return new NotificationCompat.Builder(this, Constants.CHANNEL_GROUP_CALLS_ONGOING)
              .setSmallIcon(smallIconRes)
              .setContentTitle(title)
              .setContentText(text)
              .setOngoing(true)
              .setAutoCancel(false)
              .setCategory(NotificationCompat.CATEGORY_CALL)
              .setPriority(NotificationCompat.PRIORITY_LOW)
              .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
              .setContentIntent(tapPi)
              .addAction(R.drawable.ic_phone_off, "End Call", endPi)
              .build();
      }

      @Override
      public void onDestroy() {
          tickHandler.removeCallbacksAndMessages(null);
          NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
          if (nm != null) nm.cancel(Constants.GROUP_CALL_ONGOING_NOTIF_ID);
          super.onDestroy();
      }

      @Nullable @Override public IBinder onBind(Intent intent) { return null; }
  }
  