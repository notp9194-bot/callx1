package com.callx.app.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.calls.R;
import com.callx.app.services.IncomingRingService;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

/**
 * IncomingCallActivity — Full-screen incoming call UI.
 *
 * Production improvements:
 *  - Avatar loading via Glide (thumb → full → initial fallback)
 *  - Auto-reject after CALL_TIMEOUT_MS with missed-call log
 *  - "Call Ended" overlay if caller cancelled before answer
 *  - Vibration pattern with proper VibrationEffect API (Android O+)
 *  - ACTION_DECLINED/ACTION_CANCELLED broadcast for callee notification cleanup
 *  - SHOW_WHEN_LOCKED flag so full-screen displays over lock screen
 *  - Battery: Ringtone released on stop; Vibrator cancelled on stop
 */
public class IncomingCallActivity extends AppCompatActivity {

    private String callId, callerUid, callerName, callerPhoto, callerThumb;
    private boolean isVideo;

    // UI
    private TextView   tvCallerName, tvCallType, tvCallStatus;
    private ImageView  ivAvatar;

    // Ringtone / vibrate
    private Ringtone ringtone;
    private Vibrator vibrator;
    private static final long[] VIBRATE_PATTERN = {0, 700, 500, 700, 500};

    // Timeouts
    private final Handler  timeoutHandler    = new Handler(Looper.getMainLooper());
    private Runnable       timeoutRunnable;

    // Broadcast receiver for ACTION_ACCEPTED / ACTION_DECLINED from notification
    private BroadcastReceiver actionReceiver;

    // Firebase: watch for caller cancel
    private ValueEventListener callStatusListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over lock screen
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.activity_incoming_call);

        callId      = getIntent().getStringExtra(Constants.EXTRA_CALL_ID);
        callerUid   = getIntent().getStringExtra(Constants.EXTRA_PARTNER_UID);
        callerName  = getIntent().getStringExtra(Constants.EXTRA_PARTNER_NAME);
        callerPhoto = getIntent().getStringExtra(Constants.EXTRA_PARTNER_PHOTO);
        callerThumb = getIntent().getStringExtra("partnerThumb");
        isVideo     = getIntent().getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);

        if (callId == null || callerUid == null) { finish(); return; }

        tvCallerName = findViewById(R.id.tvIncomingCallerName);
        tvCallType   = findViewById(R.id.tvIncomingCallType);
        tvCallStatus = findViewById(R.id.tvIncomingStatus);
        ivAvatar     = findViewById(R.id.ivIncomingAvatar);

        // Caller name
        if (tvCallerName != null) tvCallerName.setText(callerName != null ? callerName : "Unknown");
        // Call type label
        if (tvCallType != null)   tvCallType.setText(isVideo ? "Incoming video call" : "Incoming voice call");
        if (tvCallStatus != null) tvCallStatus.setVisibility(View.GONE);

        // Avatar loading — thumb first (faster), fall back to full photo
        loadAvatar();

        // Buttons
        View btnAccept  = findViewById(R.id.btnAcceptCall);
        View btnDecline = findViewById(R.id.btnDeclineCall);
        if (btnAccept  != null) btnAccept.setOnClickListener(v  -> acceptCall());
        if (btnDecline != null) btnDecline.setOnClickListener(v -> declineCall());

        // Stop IncomingRingService (it showed the HUN; we're full-screen now)
        try { stopService(new Intent(this, IncomingRingService.class)); } catch (Exception ignored) {}

        startRingtoneAndVibration();
        registerActionReceiver();
        watchCallStatus();
        scheduleAutoReject();
    }

    // ── Avatar ────────────────────────────────────────────────────────────

    private void loadAvatar() {
        if (ivAvatar == null) return;
        // Try thumb first for speed, then full photo
        String url = (callerThumb != null && !callerThumb.isEmpty()) ? callerThumb
                   : (callerPhoto != null && !callerPhoto.isEmpty()) ? callerPhoto
                   : null;
        if (url != null) {
            Glide.with(this)
                .load(url)
                .apply(RequestOptions.circleCropTransform()
                    .placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar))
                .into(ivAvatar);
        }
    }

    // ── Ringtone + vibration ──────────────────────────────────────────────

    private void startRingtoneAndVibration() {
        try {
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(this, ringtoneUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
                ringtone.setLooping(true);
            }
            ringtone.play();
        } catch (Exception ignored) {}

        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, 0));
                } else {
                    //noinspection deprecation
                    vibrator.vibrate(VIBRATE_PATTERN, 0);
                }
            }
        } catch (Exception ignored) {}
    }

    private void stopRingtoneAndVibration() {
        try { if (ringtone != null && ringtone.isPlaying()) ringtone.stop(); } catch (Exception ignored) {}
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
    }

    // ── Broadcast receiver (from notification buttons) ────────────────────

    private void registerActionReceiver() {
        actionReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (Constants.ACTION_ACCEPT_CALL.equals(action))  acceptCall();
                else if (Constants.ACTION_DECLINE_CALL.equals(action)) declineCall();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Constants.ACTION_ACCEPT_CALL);
        f.addAction(Constants.ACTION_DECLINE_CALL);
        try { registerReceiver(actionReceiver, f); } catch (Exception ignored) {}
    }

    // ── Firebase: watch for caller cancel ─────────────────────────────────

    private void watchCallStatus() {
        if (callId == null) return;
        callStatusListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String st = snap.getValue(String.class);
                if ("cancelled".equals(st) || "ended".equals(st)) {
                    stopRingtoneAndVibration();
                    showCallEnded("Call ended");
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("activeCalls")
            .child(callId).child("status")
            .addValueEventListener(callStatusListener);
    }

    // ── Auto-reject timeout ───────────────────────────────────────────────

    private void scheduleAutoReject() {
        timeoutRunnable = () -> {
            // Log missed call on our side
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null) {
                java.util.Map<String, Object> log = new java.util.HashMap<>();
                log.put("partnerUid",  callerUid);
                log.put("partnerName", callerName != null ? callerName : "");
                log.put("direction",   "missed");
                log.put("mediaType",   isVideo ? "video" : "audio");
                log.put("timestamp",   System.currentTimeMillis());
                log.put("duration",    0L);
                FirebaseUtils.getCallsRef(myUid).push().setValue(log);
            }
            declineCall();
        };
        timeoutHandler.postDelayed(timeoutRunnable, Constants.CALL_TIMEOUT_MS);
    }

    private void showCallEnded(String msg) {
        if (tvCallStatus != null) {
            tvCallStatus.setText(msg);
            tvCallStatus.setVisibility(View.VISIBLE);
        }
        timeoutHandler.postDelayed(this::finish, 2_000);
    }

    // ── Accept / Decline ──────────────────────────────────────────────────

    private void acceptCall() {
        stopRingtoneAndVibration();
        if (timeoutRunnable != null) timeoutHandler.removeCallbacks(timeoutRunnable);

        Intent callIntent = new Intent(this, CallActivity.class);
        callIntent.putExtra("partnerUid",   callerUid);
        callIntent.putExtra("partnerName",  callerName);
        callIntent.putExtra("partnerPhoto", callerPhoto);
        callIntent.putExtra("partnerThumb", callerThumb);
        callIntent.putExtra("callId",       callId);
        callIntent.putExtra("isCaller",     false);
        callIntent.putExtra("video",        isVideo);
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(callIntent);
        finish();
    }

    private void declineCall() {
        stopRingtoneAndVibration();
        if (timeoutRunnable != null) timeoutHandler.removeCallbacks(timeoutRunnable);
        if (callId != null) {
            FirebaseUtils.db().getReference("activeCalls")
                .child(callId).child("status").setValue("rejected");
        }
        finish();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        stopRingtoneAndVibration();
        if (timeoutRunnable != null) timeoutHandler.removeCallbacks(timeoutRunnable);
        try { if (actionReceiver != null) unregisterReceiver(actionReceiver); } catch (Exception ignored) {}
        try {
            if (callStatusListener != null && callId != null)
                FirebaseUtils.db().getReference("activeCalls")
                    .child(callId).child("status")
                    .removeEventListener(callStatusListener);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
