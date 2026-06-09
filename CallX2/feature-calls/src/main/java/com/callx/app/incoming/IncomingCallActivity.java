package com.callx.app.incoming;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.calls.databinding.ActivityIncomingCallBinding;
import com.callx.app.call.CallActivity;
import com.callx.app.services.IncomingRingService;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.CallLogEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class IncomingCallActivity extends AppCompatActivity {

    private ActivityIncomingCallBinding binding;
    private MediaPlayer ringtonePlayer;
    private PowerManager.WakeLock wakeLock;
    private String callId, fromUid, fromName, fromPhoto, fromThumb;
    private boolean isVideo, acted = false;
    private ValueEventListener statusListener;
    private final Handler autoRejectHandler = new Handler(Looper.getMainLooper());
    private Vibrator vibrator;
    private static final int AUTO_REJECT_MS = 60_000;

    // Ringing dots animation
    private final Handler dotsHandler = new Handler(Looper.getMainLooper());
    private int dotCount = 0;

    // Ripple animation
    private AnimatorSet rippleSet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityIncomingCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Show on lock screen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Read extras — support both Constants keys and legacy string keys
        callId   = getIntent().getStringExtra(Constants.EXTRA_CALL_ID);
        fromUid  = getIntent().getStringExtra(Constants.EXTRA_PARTNER_UID);
        fromName = getIntent().getStringExtra(Constants.EXTRA_PARTNER_NAME);
        isVideo  = getIntent().getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);
        if (callId   == null) callId   = getIntent().getStringExtra("callId");
        if (fromUid  == null) fromUid  = getIntent().getStringExtra("fromUid");
        if (fromName == null) fromName = getIntent().getStringExtra("fromName");
        if (!isVideo) isVideo = getIntent().getBooleanExtra("video", false);

        fromPhoto = getIntent().getStringExtra(Constants.EXTRA_PARTNER_PHOTO);
        if (fromPhoto == null) fromPhoto = getIntent().getStringExtra("partnerPhoto");
        fromThumb = getIntent().getStringExtra("partnerThumb");
        if (fromThumb == null) fromThumb = "";

        // Set caller name
        binding.tvCallerName.setText(fromName == null ? "Unknown" : fromName);

        // Call type badge
        binding.tvCallerSub.setText(isVideo ? "Incoming video call" : "Incoming voice call");
        if (binding.ivCallTypeIcon != null) {
            binding.ivCallTypeIcon.setImageResource(isVideo
                ? com.callx.app.calls.R.drawable.ic_video
                : com.callx.app.calls.R.drawable.ic_phone);
        }

        // Load caller avatar
        String avatarUrl = (fromThumb != null && !fromThumb.isEmpty()) ? fromThumb : fromPhoto;
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(this)
                .load(avatarUrl)
                .placeholder(com.callx.app.calls.R.drawable.ic_person)
                .circleCrop()
                .into(binding.ivCallerAvatar);
        }

        acquireWakeLock();
        checkBlockAndProceed();
    }

    // ── Block check ─────────────────────────────────────────────────────────
    private void checkBlockAndProceed() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty() || fromUid == null || fromUid.isEmpty()) {
            proceedWithCall();
            return;
        }
        FirebaseUtils.getBlocksRef(myUid).child(fromUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (Boolean.TRUE.equals(snap.getValue(Boolean.class))) {
                        if (!acted) { acted = true; reject(); }
                    } else {
                        proceedWithCall();
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    proceedWithCall();
                }
            });
    }

    // ── Silence unknown callers ─────────────────────────────────────────────
    private void proceedWithCall() {
        com.callx.app.utils.SecurityManager secMgr = new com.callx.app.utils.SecurityManager(this);
        if (secMgr.isSilenceUnknownCallers()) {
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null && fromUid != null) {
                FirebaseUtils.getContactsRef(myUid).child(fromUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap) {
                            if (!snap.exists()) {
                                if (!acted) { acted = true; reject(); }
                            } else {
                                startCallUI();
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            startCallUI();
                        }
                    });
                return;
            }
        }
        startCallUI();
    }

    // ── Main call UI ────────────────────────────────────────────────────────
    private void startCallUI() {
        startVibration();
        startLoopingRingtone();
        startRippleAnimation();
        startRingingDotsAnimation();
        watchCallStatus();

        autoRejectHandler.postDelayed(() -> { if (!acted) reject(); }, AUTO_REJECT_MS);

        binding.btnAccept.setOnClickListener(v -> accept());
        binding.btnReject.setOnClickListener(v -> reject());

        // ── Quick reply buttons ─────────────────────────────────────────────
        // Reject call + send pre-set message to caller
        binding.btnQuickReply1.setOnClickListener(v -> sendQuickReply("Can't talk right now"));
        binding.btnQuickReply2.setOnClickListener(v -> sendQuickReply("I'll call you later"));
        binding.btnQuickReply3.setOnClickListener(v -> sendQuickReply("On my way!"));
    }

    // ── Quick reply: reject + send message ─────────────────────────────────
    private void sendQuickReply(String message) {
        if (acted) return;
        acted = true;
        autoRejectHandler.removeCallbacksAndMessages(null);
        stopVibration();
        stopRingtone();
        stopRippleAnimation();
        stopRingingDots();
        stopIncomingRingService();
        cancelRingNotification();

        if (callId != null && !callId.isEmpty()) {
            FirebaseUtils.db().getReference("activeCalls")
                .child(callId).child("status").setValue("rejected");
        }

        // Send quick reply message via Firebase chat
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid != null && fromUid != null && !fromUid.isEmpty()) {
            String chatId = myUid.compareTo(fromUid) < 0
                ? myUid + "_" + fromUid
                : fromUid + "_" + myUid;

            Map<String, Object> msg = new HashMap<>();
            msg.put("senderId", myUid);
            msg.put("text", message);
            msg.put("timestamp", System.currentTimeMillis());
            msg.put("type", "text");
            msg.put("status", "sent");
            FirebaseUtils.db().getReference("chats").child(chatId)
                .child("messages").push().setValue(msg);
        }

        Toast.makeText(this, "Replied: " + message, Toast.LENGTH_SHORT).show();
        logMissedCall();
        finish();
    }

    // ── Accept ──────────────────────────────────────────────────────────────
    private void accept() {
        if (acted) return;
        acted = true;
        autoRejectHandler.removeCallbacksAndMessages(null);
        stopVibration();
        stopRingtone();
        stopRippleAnimation();
        stopRingingDots();
        stopIncomingRingService();
        cancelRingNotification();

        if (callId != null && !callId.isEmpty()) {
            FirebaseUtils.db().getReference("activeCalls")
                .child(callId).child("status").setValue("accepted");
        }

        Intent i = new Intent(this, CallActivity.class);
        i.putExtra("partnerUid",   fromUid);
        i.putExtra("partnerName",  fromName != null ? fromName : "");
        i.putExtra("partnerPhoto", fromPhoto != null ? fromPhoto : "");
        i.putExtra("partnerThumb", fromThumb != null ? fromThumb : "");
        i.putExtra("isCaller",     false);
        i.putExtra("video",        isVideo);
        i.putExtra("callId",       callId);
        startActivity(i);
        finish();
    }

    // ── Reject ──────────────────────────────────────────────────────────────
    private void reject() {
        if (acted) return;
        acted = true;
        autoRejectHandler.removeCallbacksAndMessages(null);
        stopVibration();
        stopRingtone();
        stopRippleAnimation();
        stopRingingDots();
        stopIncomingRingService();
        cancelRingNotification();

        if (callId != null && !callId.isEmpty()) {
            FirebaseUtils.db().getReference("activeCalls")
                .child(callId).child("status").setValue("rejected");
        }
        logMissedCall();
        finish();
    }

    // ── Missed call log — FIXED direction values ─────────────────────────
    private void logMissedCall() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || fromUid == null || fromUid.isEmpty()) return;

        long ts    = System.currentTimeMillis();
        String media = isVideo ? "video" : "audio";

        // B (callee/us) → direction = "missed"
        Map<String, Object> myMissed = new HashMap<>();
        myMissed.put("partnerUid",  fromUid);
        myMissed.put("partnerName", fromName != null ? fromName : "");
        myMissed.put("direction",   "missed");
        myMissed.put("mediaType",   media);
        myMissed.put("timestamp",   ts);
        myMissed.put("duration",    0L);
        FirebaseUtils.getCallsRef(myUid).push().setValue(myMissed)
            .addOnFailureListener(e -> android.util.Log.w("IncomingCall", "B missed-log failed", e));

        // A (caller) → direction = "no_answer" (FIXED: was "missed" which was wrong)
        FirebaseUtils.getUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String myName = snap.child("name").getValue(String.class);
                Map<String, Object> callerLog = new HashMap<>();
                callerLog.put("partnerUid",  myUid);
                callerLog.put("partnerName", myName != null ? myName : "");
                callerLog.put("direction",   "no_answer"); // FIXED: caller ke liye correct direction
                callerLog.put("mediaType",   media);
                callerLog.put("timestamp",   ts);
                callerLog.put("duration",    0L);
                FirebaseUtils.getCallsRef(fromUid).push().setValue(callerLog)
                    .addOnFailureListener(e -> android.util.Log.w("IncomingCall", "A no_answer log failed", e));
            }
            @Override public void onCancelled(DatabaseError e) {}
        });

        // Room DB cache for callee
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

    // ── Watch Firebase for caller cancel ────────────────────────────────────
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

    // ── Ripple animation on the 3 ring views ─────────────────────────────
    private void startRippleAnimation() {
        try {
            View r1 = binding.rippleRing1;
            View r2 = binding.rippleRing2;
            View r3 = binding.rippleRing3;
            if (r1 == null || r2 == null || r3 == null) return;

            AnimatorSet set1 = makeRipplePulse(r1, 0);
            AnimatorSet set2 = makeRipplePulse(r2, 300);
            AnimatorSet set3 = makeRipplePulse(r3, 600);

            rippleSet = new AnimatorSet();
            rippleSet.playTogether(set1, set2, set3);
            rippleSet.start();
        } catch (Exception ignored) {}
    }

    private AnimatorSet makeRipplePulse(View v, long delay) {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(v, "scaleX", 0.8f, 1.2f, 0.8f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(v, "scaleY", 0.8f, 1.2f, 0.8f);
        ObjectAnimator alpha  = ObjectAnimator.ofFloat(v, "alpha", 0.1f, 0.4f, 0.1f);
        scaleX.setRepeatCount(ObjectAnimator.INFINITE);
        scaleY.setRepeatCount(ObjectAnimator.INFINITE);
        alpha.setRepeatCount(ObjectAnimator.INFINITE);
        scaleX.setRepeatMode(ObjectAnimator.RESTART);
        scaleY.setRepeatMode(ObjectAnimator.RESTART);
        alpha.setRepeatMode(ObjectAnimator.RESTART);
        scaleX.setInterpolator(new DecelerateInterpolator());
        scaleY.setInterpolator(new DecelerateInterpolator());
        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, alpha);
        set.setDuration(1600);
        set.setStartDelay(delay);
        return set;
    }

    private void stopRippleAnimation() {
        try { if (rippleSet != null) { rippleSet.cancel(); rippleSet = null; } }
        catch (Exception ignored) {}
    }

    // ── "Ringing..." animated dots ───────────────────────────────────────
    private void startRingingDotsAnimation() {
        dotsHandler.post(new Runnable() {
            @Override public void run() {
                if (acted) return;
                dotCount = (dotCount + 1) % 4;
                StringBuilder dots = new StringBuilder("Ringing");
                for (int i = 0; i < dotCount; i++) dots.append(".");
                try { binding.tvRingingStatus.setText(dots.toString()); }
                catch (Exception ignored) {}
                dotsHandler.postDelayed(this, 500);
            }
        });
    }

    private void stopRingingDots() {
        dotsHandler.removeCallbacksAndMessages(null);
    }

    // ── Looping ringtone ─────────────────────────────────────────────────
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

    // ── Vibration ─────────────────────────────────────────────────────────
    private void startVibration() {
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = {0, 400, 200, 400, 800};
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {}
    }

    private void stopVibration() {
        try { if (vibrator != null) { vibrator.cancel(); vibrator = null; } }
        catch (Exception ignored) {}
    }

    // ── WakeLock ──────────────────────────────────────────────────────────
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

    private void stopIncomingRingService() {
        try { stopService(new Intent(this, IncomingRingService.class)); }
        catch (Exception ignored) {}
    }

    private void cancelRingNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Constants.CALL_RING_NOTIF_ID);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        autoRejectHandler.removeCallbacksAndMessages(null);
        stopVibration();
        stopRingtone();
        stopRippleAnimation();
        stopRingingDots();
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
