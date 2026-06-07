  package com.callx.app.incoming;
  import android.app.NotificationManager;
  import android.content.Context;
  import android.animation.ObjectAnimator;
  import android.animation.AnimatorSet;
  import android.content.Intent;
  import android.os.Vibrator;
  import android.os.VibrationEffect;
  import android.view.animation.DecelerateInterpolator;
  import android.view.WindowManager;
  import com.bumptech.glide.Glide;
  import android.media.AudioAttributes;
  import android.media.MediaPlayer;
  import android.media.RingtoneManager;
  import android.net.Uri;
  import android.os.Bundle;
  import android.os.Handler;
  import android.os.Looper;
  import android.os.PowerManager;
  import androidx.appcompat.app.AppCompatActivity;
  import com.callx.app.calls.databinding.ActivityIncomingCallBinding;
  import com.callx.app.services.IncomingRingService;
  import com.callx.app.utils.Constants;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.ValueEventListener;
  import com.callx.app.db.AppDatabase;
  import com.callx.app.db.entity.CallLogEntity;
  import androidx.annotation.NonNull;
  import java.util.HashMap;
  import java.util.Map;
  import java.util.concurrent.Executors;
import com.callx.app.call.CallActivity;
  public class IncomingCallActivity extends AppCompatActivity {
      private ActivityIncomingCallBinding binding;
      private MediaPlayer ringtonePlayer;
      private PowerManager.WakeLock wakeLock;
      private String callId, fromUid, fromName, fromPhoto, fromThumb; // FIX-1: added fromThumb
      private boolean isVideo, acted = false;
      private ValueEventListener statusListener;
      private final Handler autoRejectHandler = new Handler(Looper.getMainLooper());
      private Vibrator vibrator;
      private static final int AUTO_REJECT_MS = 60_000;
      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          binding = ActivityIncomingCallBinding.inflate(getLayoutInflater());
          setContentView(binding.getRoot());
          // FIX: Window flags so screen turns on and shows on lock screen
          getWindow().addFlags(
              WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
              | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
              | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
              | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
          // Read extras — support both new Constants keys and legacy string keys
          callId   = getIntent().getStringExtra(Constants.EXTRA_CALL_ID);
          fromUid  = getIntent().getStringExtra(Constants.EXTRA_PARTNER_UID);
          fromName = getIntent().getStringExtra(Constants.EXTRA_PARTNER_NAME);
          isVideo  = getIntent().getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);
          if (callId  == null) callId  = getIntent().getStringExtra("callId");
          if (fromUid == null) fromUid = getIntent().getStringExtra("fromUid");
          if (fromName== null) fromName= getIntent().getStringExtra("fromName");
          if (!isVideo) isVideo = getIntent().getBooleanExtra("video", false);
          // FIX-9: read photo so it can be passed to CallActivity
          fromPhoto = getIntent().getStringExtra(Constants.EXTRA_PARTNER_PHOTO);
          if (fromPhoto == null) fromPhoto = getIntent().getStringExtra("partnerPhoto");
          // FIX-1: read thumb (100×100 WebP) for fast avatar in CallActivity
          fromThumb = getIntent().getStringExtra("partnerThumb");
          if (fromThumb == null) fromThumb = "";
          binding.tvCallerName.setText(fromName == null ? "Unknown" : fromName);
          binding.tvCallerSub.setText(isVideo ? "Incoming video call" : "Incoming voice call");
          // FIX: Set video/audio icon in the call type badge
          if (binding.ivCallTypeIcon != null) {
              binding.ivCallTypeIcon.setImageResource(isVideo
                  ? com.callx.app.calls.R.drawable.ic_video
                  : com.callx.app.calls.R.drawable.ic_phone);
          }
          // FIX: Load caller avatar with Glide (was never called before!)
          String avatarUrl = (fromThumb != null && !fromThumb.isEmpty()) ? fromThumb : fromPhoto;
          if (avatarUrl != null && !avatarUrl.isEmpty()) {
              Glide.with(this)
                  .load(avatarUrl)
                  .placeholder(com.callx.app.calls.R.drawable.ic_person)
                  .circleCrop()
                  .into(binding.ivCallerAvatar);
          }
          acquireWakeLock();
          // ── Block check: blocked user ki call silently reject karo ──────
          checkBlockAndProceed();
      }

      private void checkBlockAndProceed() {
          String myUid = com.callx.app.utils.FirebaseUtils.getCurrentUid();
          if (myUid == null || myUid.isEmpty() || fromUid == null || fromUid.isEmpty()) {
              proceedWithCall();
              return;
          }
          com.callx.app.utils.FirebaseUtils.getBlocksRef(myUid).child(fromUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
                      if (Boolean.TRUE.equals(snap.getValue(Boolean.class))) {
                          // Caller blocked hai — silently reject
                          if (!acted) { acted = true; reject(); }
                      } else {
                          proceedWithCall();
                      }
                  }
                  @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                      proceedWithCall(); // Error pe proceed karo, block check fail nahi block kare
                  }
              });
      }

      private void proceedWithCall() {
          // ── Silence Unknown Callers check ─────────────────────────────
          com.callx.app.utils.SecurityManager secMgr =
              new com.callx.app.utils.SecurityManager(this);
          if (secMgr.isSilenceUnknownCallers()) {
              String myUid = com.callx.app.utils.FirebaseUtils.getCurrentUid();
              if (myUid != null && fromUid != null) {
                  // Check if caller is in my contacts
                  com.callx.app.utils.FirebaseUtils.getContactsRef(myUid).child(fromUid)
                      .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                          @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
                              if (!snap.exists()) {
                                  // Not a contact — silently reject
                                  if (!acted) { acted = true; reject(); }
                              } else {
                                  startCallUI();
                              }
                          }
                          @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                              startCallUI(); // Fail-open: error pe reject mat karo
                          }
                      });
                  return; // Wait for Firebase callback
              }
          }
          startCallUI();
      }

      private void startCallUI() {
          startVibration();
          startLoopingRingtone();
          startPulseAnimation();
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
          stopVibration();
          stopRingtone();
          stopIncomingRingService();
          cancelRingNotification();
          if (callId != null && !callId.isEmpty()) {
              FirebaseUtils.db().getReference("activeCalls")
                  .child(callId).child("status").setValue("accepted");
          }
          Intent i = new Intent(this, CallActivity.class);
          i.putExtra("partnerUid",   fromUid);
          i.putExtra("partnerName",  fromName != null ? fromName : "");
          i.putExtra("partnerPhoto", fromPhoto != null ? fromPhoto : ""); // FIX-9
          i.putExtra("partnerThumb", fromThumb != null ? fromThumb : ""); // FIX-1
          i.putExtra("isCaller",     false);
          i.putExtra("video",        isVideo);
          i.putExtra("callId",       callId);
          startActivity(i);
          finish();
      }
      private void reject() {
          if (acted) return;
          acted = true;
          autoRejectHandler.removeCallbacksAndMessages(null);
          stopVibration();
          stopRingtone();
          stopIncomingRingService();
          cancelRingNotification();
          if (callId != null && !callId.isEmpty()) {
              FirebaseUtils.db().getReference("activeCalls")
                  .child(callId).child("status").setValue("rejected");
          }
          // ── Missed call log ──────────────────────────────────────────────
          // B (callee) ne reject/ignore kiya → dono ke liye "missed" entry likho
          logMissedCall();
          finish();
      }

      /**
       * Dono parties ke Firebase call log me "missed" entry likhta hai:
       *   B (callee/hum) → direction = "missed"
       *   A (caller/fromUid) → direction = "missed"
       * Aur B ke Room DB me bhi cache karta hai.
       */
      private void logMissedCall() {
          String myUid = FirebaseUtils.getCurrentUid();
          if (myUid == null || fromUid == null || fromUid.isEmpty()) return;

          long ts = System.currentTimeMillis();
          String media = isVideo ? "video" : "audio";

          // ── B ke liye (callee = hum) ──
          Map<String, Object> myMissed = new HashMap<>();
          myMissed.put("partnerUid",  fromUid);
          myMissed.put("partnerName", fromName != null ? fromName : "");
          myMissed.put("direction",   "missed");
          myMissed.put("mediaType",   media);
          myMissed.put("timestamp",   ts);
          myMissed.put("duration",    0L);
          FirebaseUtils.getCallsRef(myUid).push().setValue(myMissed)
              .addOnFailureListener(e -> android.util.Log.w("IncomingCall", "B missed-log failed", e));

          // ── A ke liye (caller = fromUid) — unhe bhi missed dikhao ──
          FirebaseUtils.getUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  String myName = snap.child("name").getValue(String.class);
                  Map<String, Object> callerMissed = new HashMap<>();
                  callerMissed.put("partnerUid",  myUid);
                  callerMissed.put("partnerName", myName != null ? myName : "");
                  callerMissed.put("direction",   "missed");
                  callerMissed.put("mediaType",   media);
                  callerMissed.put("timestamp",   ts);
                  callerMissed.put("duration",    0L);
                  FirebaseUtils.getCallsRef(fromUid).push().setValue(callerMissed)
                      .addOnFailureListener(e -> android.util.Log.w("IncomingCall", "A missed-log failed", e));
              }
              @Override public void onCancelled(DatabaseError e) {}
          });

          // ── Room cache — B ke liye ──
          Executors.newSingleThreadExecutor().execute(() -> {
              try {
                  CallLogEntity entity = new CallLogEntity();
                  entity.id          = java.util.UUID.randomUUID().toString();
                  entity.partnerUid  = fromUid;
                  entity.partnerName = fromName != null ? fromName : "";
                  entity.direction   = "missed";
                  entity.mediaType   = media;
                  entity.timestamp   = ts;
                  entity.duration    = 0L;
                  AppDatabase.getInstance(getApplicationContext())
                      .callLogDao().insertCallLog(entity);
              } catch (Exception ex) {
                  android.util.Log.w("IncomingCall", "Room missed-log failed", ex);
              }
          });
      }
      // FIX: Pulse animation on avatar — visual cue that call is incoming
      private void startPulseAnimation() {
          try {
              ObjectAnimator scaleX = ObjectAnimator.ofFloat(binding.ivCallerAvatar, "scaleX", 1f, 1.15f, 1f);
              ObjectAnimator scaleY = ObjectAnimator.ofFloat(binding.ivCallerAvatar, "scaleY", 1f, 1.15f, 1f);
              AnimatorSet pulse = new AnimatorSet();
              pulse.playTogether(scaleX, scaleY);
              pulse.setDuration(800);
              pulse.setInterpolator(new DecelerateInterpolator());
              scaleX.setRepeatCount(ObjectAnimator.INFINITE);
              scaleY.setRepeatCount(ObjectAnimator.INFINITE);
              scaleX.setRepeatMode(ObjectAnimator.REVERSE);
              scaleY.setRepeatMode(ObjectAnimator.REVERSE);
              pulse.start();
          } catch (Exception ignored) {}
      }

      // FIX: Vibration pattern — rings with phone (buzz on, pause, buzz on...)
      private void startVibration() {
          try {
              vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
              if (vibrator == null || !vibrator.hasVibrator()) return;
              long[] pattern = {0, 400, 200, 400, 800}; // off, on, off, on, pause
              if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                  vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0)); // 0 = repeat from start
              } else {
                  vibrator.vibrate(pattern, 0);
              }
          } catch (Exception ignored) {}
      }

      private void stopVibration() {
          try { if (vibrator != null) { vibrator.cancel(); vibrator = null; } }
          catch (Exception ignored) {}
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
          stopVibration();
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
