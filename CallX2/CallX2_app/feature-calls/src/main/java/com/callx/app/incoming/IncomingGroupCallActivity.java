package com.callx.app.incoming;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.calls.R;
import com.callx.app.services.GroupCallRingService;
import com.callx.app.utils.Constants;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Build;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.animation.DecelerateInterpolator;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.group.GroupCallActivity;

/**
 * IncomingGroupCallActivity — Full-screen incoming group call UI.
 *
 * Features:
 *  - Shows group name, caller name, call type (audio/video)
 *  - Accept → launches GroupCallActivity
 *  - Decline → writes declined status to Firebase, stops ring service
 *  - Auto-dismiss after CALL_TIMEOUT_MS
 *  - Wake lock + show-when-locked flags
 */
public class IncomingGroupCallActivity extends AppCompatActivity {

    public static final String EXTRA_CALL_ID     = "igc_call_id";
    public static final String EXTRA_GROUP_ID    = "igc_group_id";
    public static final String EXTRA_GROUP_NAME  = "igc_group_name";
    public static final String EXTRA_GROUP_ICON  = "igc_group_icon";
    public static final String EXTRA_CALLER_UID   = "igc_caller_uid";
    public static final String EXTRA_CALLER_NAME  = "igc_caller_name";
    public static final String EXTRA_CALLER_PHOTO = "igc_caller_photo"; // FIX-4
    public static final String EXTRA_IS_VIDEO     = "igc_is_video";

    private String callId, groupId, groupName, groupIcon, callerUid, callerName, callerPhoto; // FIX-4
    private boolean isVideo;

    private final Handler autoDeclineHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.activity_incoming_group_call);

        // WakeLock — keep screen on for incoming call
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "callx:incoming_group");
            wakeLock.acquire(Constants.CALL_TIMEOUT_MS + 5_000L);
        }

        callId     = getIntent().getStringExtra(EXTRA_CALL_ID);
        groupId    = getIntent().getStringExtra(EXTRA_GROUP_ID);
        groupName  = getIntent().getStringExtra(EXTRA_GROUP_NAME);
        groupIcon  = getIntent().getStringExtra(EXTRA_GROUP_ICON);
        callerUid  = getIntent().getStringExtra(EXTRA_CALLER_UID);
        callerName = getIntent().getStringExtra(EXTRA_CALLER_NAME);
        callerPhoto = getIntent().getStringExtra(EXTRA_CALLER_PHOTO); // FIX-4
        if (callerPhoto == null) callerPhoto = "";
        isVideo    = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);

        TextView tvGroupName  = findViewById(R.id.tvIncomingGroupName);
        TextView tvCallType   = findViewById(R.id.tvIncomingGroupCallType);
        TextView tvCallerInfo = findViewById(R.id.tvIncomingGroupCallerInfo);
        ImageButton btnAccept  = findViewById(R.id.btnGroupAcceptCall);
        ImageButton btnDecline = findViewById(R.id.btnGroupDeclineCall);

        // FIX-4: load caller avatar into existing group icon view if no group icon available
        ImageView ivGroupIcon = findViewById(R.id.ivIncomingGroupIcon);
        if (ivGroupIcon != null) {
            String avatarUrl = (groupIcon != null && !groupIcon.isEmpty()) ? groupIcon : callerPhoto;
            if (!avatarUrl.isEmpty()) {
                Glide.with(this).load(avatarUrl).circleCrop().into(ivGroupIcon);
            }
        }

        tvGroupName.setText(groupName != null ? groupName : "Group");
        tvCallType.setText(isVideo ? "Incoming Group Video Call" : "Incoming Group Voice Call");
        tvCallerInfo.setText((callerName != null ? callerName : "Someone") + " is calling...");

        btnAccept.setOnClickListener(v -> acceptCall());
        btnDecline.setOnClickListener(v -> declineCall());

        // FIX: Vibration + pulse animation
        startVibration();
        startPulseAnimation(ivGroupIcon);

        // Auto-dismiss on timeout
        autoDeclineHandler.postDelayed(this::declineCall, Constants.CALL_TIMEOUT_MS);
    }

    private void acceptCall() {
        autoDeclineHandler.removeCallbacksAndMessages(null);
        stopVibration();
        stopRingService();

        // Mark as joined in Firebase
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid != null && callId != null) {
            FirebaseUtils.db().getReference("groupCalls").child(callId)
                .child("participants").child(myUid).child("status").setValue("joining");
        }

        Intent i = new Intent(this, GroupCallActivity.class);
        i.putExtra(GroupCallActivity.EXTRA_CALL_ID,    callId);
        i.putExtra(GroupCallActivity.EXTRA_GROUP_ID,   groupId);
        i.putExtra(GroupCallActivity.EXTRA_GROUP_NAME, groupName);
        i.putExtra(GroupCallActivity.EXTRA_GROUP_ICON, groupIcon);
        i.putExtra(GroupCallActivity.EXTRA_IS_VIDEO,   isVideo);
        i.putExtra(GroupCallActivity.EXTRA_IS_CALLER,  false);
        startActivity(i);
        finish();
    }

    private void declineCall() {
        autoDeclineHandler.removeCallbacksAndMessages(null);
        stopVibration();
        stopRingService();

        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid != null && callId != null) {
            FirebaseUtils.db().getReference("groupCalls").child(callId)
                .child("participants").child(myUid).child("status").setValue("declined");
        }
        finish();
    }


    // FIX: Pulse animation on group icon — visual incoming call cue
    private void startPulseAnimation(android.view.View target) {
        if (target == null) return;
        try {
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(target, "scaleX", 1f, 1.15f, 1f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(target, "scaleY", 1f, 1.15f, 1f);
            scaleX.setDuration(800);
            scaleY.setDuration(800);
            scaleX.setRepeatCount(ObjectAnimator.INFINITE);
            scaleY.setRepeatCount(ObjectAnimator.INFINITE);
            scaleX.setRepeatMode(ObjectAnimator.RESTART);
            scaleY.setRepeatMode(ObjectAnimator.RESTART);
            scaleX.setInterpolator(new DecelerateInterpolator());
            scaleY.setInterpolator(new DecelerateInterpolator());
            AnimatorSet pulse = new AnimatorSet();
            pulse.playTogether(scaleX, scaleY);
            pulse.start();
        } catch (Exception ignored) {}
    }

    // FIX: Vibration — buzz pattern synced with ring
    private void startVibration() {
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = {0, 400, 200, 400, 800};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    private void stopRingService() {
        try {
            Intent stop = new Intent(this, GroupCallRingService.class);
            stopService(stop);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        autoDeclineHandler.removeCallbacksAndMessages(null);
        stopVibration();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Prevent back from dismissing without decision
    }
}
