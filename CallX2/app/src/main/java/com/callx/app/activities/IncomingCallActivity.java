  package com.callx.app.activities;
  import android.app.NotificationManager;
  import android.content.Context;
  import android.content.Intent;
  import android.media.AudioAttributes;
  import android.media.MediaPlayer;
  import android.media.RingtoneManager;
  import android.net.Uri;
  import android.os.Bundle;
  import android.os.Handler;
  import android.os.Looper;
  import android.os.PowerManager;
  import androidx.appcompat.app.AppCompatActivity;
  import com.callx.app.databinding.ActivityIncomingCallBinding;
  import com.callx.app.services.IncomingRingService;
  import com.callx.app.utils.Constants;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.ValueEventListener;
  public class IncomingCallActivity extends AppCompatActivity {
      private ActivityIncomingCallBinding binding;
      private MediaPlayer ringtonePlayer;
      private PowerManager.WakeLock wakeLock;
      private String callId, fromUid, fromName;
      private boolean isVideo, acted = false;
      private ValueEventListener statusListener;
      private final Handler autoRejectHandler = new Handler(Looper.getMainLooper());
      private static final int AUTO_REJECT_MS = 60_000;
      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          binding = ActivityIncomingCallBinding.inflate(getLayoutInflater());
          setContentView(binding.getRoot());
          // Read extras — support both new Constants keys and legacy string keys
          callId   = getIntent().getStringExtra(Constants.EXTRA_CALL_ID);
          fromUid  = getIntent().getStringExtra(Constants.EXTRA_PARTNER_UID);
          fromName = getIntent().getStringExtra(Constants.EXTRA_PARTNER_NAME);
          isVideo  = getIntent().getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);
          if (callId  == null) callId  = getIntent().getStringExtra("callId");
          if (fromUid == null) fromUid = getIntent().getStringExtra("fromUid");
          if (fromName== null) fromName= getIntent().getStringExtra("fromName");
          if (!isVideo) isVideo = getIntent().getBooleanExtra("video", false);
          binding.tvCallerName.setText(fromName == null ? "Unknown" : fromName);
          binding.tvCallerSub.setText(isVideo ? "Incoming video CallX..." : "Incoming CallX...");
          acquireWakeLock();
          startLoopingRingtone();
          watchCallStatus();
          // Auto-reject after 60 s if user ignores the call
          autoRejectHandler.postDelayed(() -> { if (!acted) reject(); }, AUTO_REJECT_MS);
          binding.btnAccept.setOnClickListener(v -> accept());
          binding.btnReject.setOnClickListener(v -> reject());
      }
      private void acquireWakeLock() {
          try {
              PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
              if (pm == null) return;
              wakeLock = pm.newWakeLock(
                  PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                  "callx:incoming_call");
              wakeLock.acquire(AUTO_REJECT_MS + 5_000L);
          } catch (Exception ignored) {}
      }
      /** Looping ringtone via MediaPlayer — plays until accept/reject/timeout */
      private void startLoopingRingtone() {
          try {
              Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
              ringtonePlayer = new MediaPlayer();
              ringtonePlayer.setDataSource(this, uri);
              ringtonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                  .build());
              ringtonePlayer.setLooping(true);
              ringtonePlayer.prepare();
              ringtonePlayer.start();
          } catch (Exception ignored) {}
      }
      private void stopRingtone() {
          try {
              if (ringtonePlayer != null) {
                  ringtonePlayer.stop();
                  ringtonePlayer.release();
                  ringtonePlayer = null;
              }
          } catch (Exception ignored) {}
      }
      /** Watch Firebase — auto-dismiss if caller cancels before answer */
      private void watchCallStatus() {
          if (callId == null || callId.isEmpty()) return;
          statusListener = new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot s) {
                  String st = s.getValue(String.class);
                  if ("ended".equals(st) || "cancelled".equals(st)) {
                      runOnUiThread(() -> { if (!acted) reject(); });
                  }
              }
              @Override public void onCancelled(DatabaseError e) {}
          };
          FirebaseUtils.db().getReference("activeCalls")
              .child(callId).child("status").addValueEventListener(statusListener);
      }
      private void accept() {
          if (acted) return;
          acted = true;
          autoRejectHandler.removeCallbacksAndMessages(null);
          stopRingtone();
          stopIncomingRingService();
          cancelRingNotification();
          if (callId != null && !callId.isEmpty()) {
              FirebaseUtils.db().getReference("activeCalls")
                  .child(callId).child("status").setValue("accepted");
          }
          Intent i = new Intent(this, CallActivity.class);
          i.putExtra("partnerUid",  fromUid);
          i.putExtra("partnerName", fromName != null ? fromName : "");
          i.putExtra("isCaller",    false);
          i.putExtra("video",       isVideo);
          i.putExtra("callId",      callId);
          startActivity(i);
          finish();
      }
      private void reject() {
          if (acted) return;
          acted = true;
          autoRejectHandler.removeCallbacksAndMessages(null);
          stopRingtone();
          stopIncomingRingService();
          cancelRingNotification();
          if (callId != null && !callId.isEmpty()) {
              FirebaseUtils.db().getReference("activeCalls")
                  .child(callId).child("status").setValue("rejected");
          }
          finish();
      }
      private void stopIncomingRingService() {
          try { stopService(new Intent(this, IncomingRingService.class)); }
          catch (Exception ignored) {}
      }
      private void cancelRingNotification() {
          try {
              NotificationManager nm = (NotificationManager)
                  getSystemService(Context.NOTIFICATION_SERVICE);
              if (nm != null) nm.cancel(Constants.CALL_RING_NOTIF_ID);
          } catch (Exception ignored) {}
      }
      @Override
      protected void onDestroy() {
          autoRejectHandler.removeCallbacksAndMessages(null);
          stopRingtone();
          if (wakeLock != null && wakeLock.isHeld()) {
              try { wakeLock.release(); } catch (Exception ignored) {}
          }
          if (statusListener != null && callId != null && !callId.isEmpty()) {
              try {
                  FirebaseUtils.db().getReference("activeCalls")
                      .child(callId).child("status").removeEventListener(statusListener);
              } catch (Exception ignored) {}
          }
          super.onDestroy();
      }
  }
