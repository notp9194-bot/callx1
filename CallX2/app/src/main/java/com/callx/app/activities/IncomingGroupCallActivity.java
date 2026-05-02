package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.R;
import com.callx.app.services.GroupCallRingService;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;

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

    public static final String EXTRA_CALL_ID    = "igc_call_id";
    public static final String EXTRA_GROUP_ID   = "igc_group_id";
    public static final String EXTRA_GROUP_NAME = "igc_group_name";
    public static final String EXTRA_GROUP_ICON = "igc_group_icon";
    public static final String EXTRA_CALLER_UID  = "igc_caller_uid";
    public static final String EXTRA_CALLER_NAME = "igc_caller_name";
    public static final String EXTRA_IS_VIDEO    = "igc_is_video";

    private String callId, groupId, groupName, groupIcon, callerUid, callerName;
    private boolean isVideo;

    private final Handler autoDeclineHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.activity_incoming_group_call);

        callId     = getIntent().getStringExtra(EXTRA_CALL_ID);
        groupId    = getIntent().getStringExtra(EXTRA_GROUP_ID);
        groupName  = getIntent().getStringExtra(EXTRA_GROUP_NAME);
        groupIcon  = getIntent().getStringExtra(EXTRA_GROUP_ICON);
        callerUid  = getIntent().getStringExtra(EXTRA_CALLER_UID);
        callerName = getIntent().getStringExtra(EXTRA_CALLER_NAME);
        isVideo    = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);

        TextView tvGroupName  = findViewById(R.id.tvIncomingGroupName);
        TextView tvCallType   = findViewById(R.id.tvIncomingGroupCallType);
        TextView tvCallerInfo = findViewById(R.id.tvIncomingGroupCallerInfo);
        ImageButton btnAccept  = findViewById(R.id.btnGroupAcceptCall);
        ImageButton btnDecline = findViewById(R.id.btnGroupDeclineCall);

        tvGroupName.setText(groupName != null ? groupName : "Group");
        tvCallType.setText(isVideo ? "Incoming Group Video Call" : "Incoming Group Voice Call");
        tvCallerInfo.setText((callerName != null ? callerName : "Someone") + " is calling...");

        btnAccept.setOnClickListener(v -> acceptCall());
        btnDecline.setOnClickListener(v -> declineCall());

        // Auto-dismiss on timeout
        autoDeclineHandler.postDelayed(this::declineCall, Constants.CALL_TIMEOUT_MS);
    }

    private void acceptCall() {
        autoDeclineHandler.removeCallbacksAndMessages(null);
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
        stopRingService();

        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid != null && callId != null) {
            FirebaseUtils.db().getReference("groupCalls").child(callId)
                .child("participants").child(myUid).child("status").setValue("declined");
        }
        finish();
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
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Prevent back from dismissing without decision
    }
}
