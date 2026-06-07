package com.callx.app.services;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.callx.app.group.GroupCallActivity;

/**
 * GroupCallActionReceiver — handles notification action broadcasts for group calls.
 *
 * Handles:
 *  - ACTION_GROUP_DECLINE_CALL: decline from notification (app killed)
 *  - ACTION_GROUP_END_CALL:     end call from ongoing notification or local broadcast
 */
public class GroupCallActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        String callId  = intent.getStringExtra(Constants.EXTRA_CALL_ID);
        String groupId = intent.getStringExtra(Constants.EXTRA_GROUP_ID);
        int notifId    = intent.getIntExtra(Constants.EXTRA_NOTIF_ID, -1);

        switch (action) {
            case Constants.ACTION_GROUP_DECLINE_CALL:
                handleDecline(context, callId, notifId);
                break;
            case Constants.ACTION_GROUP_END_CALL:
                handleEndCall(context, callId, notifId);
                // Also broadcast locally so GroupCallActivity can catch it
                Intent local = new Intent(Constants.ACTION_GROUP_END_CALL);
                context.sendBroadcast(local);
                break;
            case Constants.ACTION_GROUP_TOGGLE_MIC:
                handleToggleMic(context);
                break;
            case Constants.ACTION_GROUP_TOGGLE_CAMERA:
                handleToggleCamera(context);
                break;
        }
    }

    private void handleToggleMic(Context context) {
        GroupCallForegroundService.micOn = !GroupCallForegroundService.micOn;
        boolean nowMicOn = GroupCallForegroundService.micOn;
        // GroupCallActivity ko broadcast karo taaki WebRTC track bhi toggle ho
        Intent micBroadcast = new Intent("com.callx.app.INTERNAL_GROUP_TOGGLE_MIC");
        micBroadcast.putExtra(Constants.EXTRA_MIC_ON, nowMicOn);
        context.sendBroadcast(micBroadcast);
        // Service ko restart karo taaki notification button label update ho
        Intent svcIntent = new Intent(context, GroupCallForegroundService.class);
        svcIntent.putExtra(Constants.EXTRA_MIC_ON, nowMicOn);
        context.startService(svcIntent);
    }

    private void handleToggleCamera(Context context) {
        GroupCallForegroundService.camOn = !GroupCallForegroundService.camOn;
        boolean nowCamOn = GroupCallForegroundService.camOn;
        Intent camBroadcast = new Intent("com.callx.app.INTERNAL_GROUP_TOGGLE_CAMERA");
        camBroadcast.putExtra(Constants.EXTRA_CAM_ON, nowCamOn);
        context.sendBroadcast(camBroadcast);
        Intent svcIntent = new Intent(context, GroupCallForegroundService.class);
        svcIntent.putExtra(Constants.EXTRA_CAM_ON, nowCamOn);
        context.startService(svcIntent);
    }

    private void handleDecline(Context context, String callId, int notifId) {
        // Stop ring service
        try {
            context.stopService(new Intent(context, GroupCallRingService.class));
        } catch (Exception ignored) {}

        // Mark as declined in Firebase
        String myUid = getCurrentUid();
        if (myUid != null && callId != null && !callId.isEmpty()) {
            FirebaseUtils.db().getReference("groupCalls").child(callId)
                .child("participants").child(myUid)
                .child("status").setValue("declined");
        }

        // Dismiss notification
        dismissNotification(context, notifId >= 0 ? notifId
            : Constants.GROUP_CALL_RING_NOTIF_ID);
    }

    private void handleEndCall(Context context, String callId, int notifId) {
        // Stop ongoing service
        try {
            context.stopService(new Intent(context, GroupCallForegroundService.class));
        } catch (Exception ignored) {}

        // Mark as ended/left in Firebase
        String myUid = getCurrentUid();
        if (myUid != null && callId != null && !callId.isEmpty()) {
            FirebaseUtils.db().getReference("groupCalls").child(callId)
                .child("participants").child(myUid)
                .child("status").setValue("left");
        }

        dismissNotification(context, notifId >= 0 ? notifId
            : Constants.GROUP_CALL_ONGOING_NOTIF_ID);
    }

    private void dismissNotification(Context context, int notifId) {
        try {
            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notifId);
        } catch (Exception ignored) {}
    }

    private String getCurrentUid() {
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                return FirebaseAuth.getInstance().getCurrentUser().getUid();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
