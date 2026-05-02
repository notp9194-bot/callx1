package com.callx.app.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.callx.app.R;
import com.callx.app.activities.GroupCallActivity;
import com.callx.app.utils.Constants;

/**
 * GroupCallForegroundService — ongoing notification while group call is active.
 *
 * Features:
 *  - Persistent ongoing notification with group name + timer text
 *  - "End Call" action via broadcast
 *  - START_NOT_STICKY (stops when killed by OS — call ends too)
 *  - Updates participant count via startService re-call
 */
public class GroupCallForegroundService extends Service {

    public static final String EXTRA_GROUP_NAME       = "fg_group_name";
    public static final String EXTRA_CALL_ID          = "fg_call_id";
    public static final String EXTRA_IS_VIDEO         = "fg_is_video";
    public static final String EXTRA_PARTICIPANT_COUNT = "fg_participant_count";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String groupName = intent != null
            ? intent.getStringExtra(EXTRA_GROUP_NAME) : "";
        String callId    = intent != null
            ? intent.getStringExtra(EXTRA_CALL_ID) : "";
        boolean isVideo  = intent != null
            && intent.getBooleanExtra(EXTRA_IS_VIDEO, false);
        int participantCount = intent != null
            ? intent.getIntExtra(EXTRA_PARTICIPANT_COUNT, 2) : 2;

        startForeground(Constants.GROUP_CALL_ONGOING_NOTIF_ID,
            buildNotification(groupName, callId, isVideo, participantCount));
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String groupName, String callId,
                                            boolean isVideo, int participantCount) {
        // Tap → return to GroupCallActivity
        Intent tapIntent = new Intent(this, GroupCallActivity.class);
        tapIntent.putExtra(GroupCallActivity.EXTRA_CALL_ID, callId);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tapPi = PendingIntent.getActivity(this,
            Constants.GROUP_CALL_ONGOING_NOTIF_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // End call action
        Intent endIntent = new Intent(this, GroupCallActionReceiver.class);
        endIntent.setAction(Constants.ACTION_GROUP_END_CALL);
        endIntent.putExtra(Constants.EXTRA_CALL_ID, callId);
        PendingIntent endPi = PendingIntent.getBroadcast(this,
            Constants.GROUP_CALL_ONGOING_NOTIF_ID + 1, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = (groupName != null && !groupName.isEmpty()) ? groupName : "Group Call";
        String text  = (isVideo ? "Video call" : "Voice call")
            + " • " + participantCount + " participant"
            + (participantCount == 1 ? "" : "s");

        return new NotificationCompat.Builder(this, Constants.CHANNEL_GROUP_CALLS_ONGOING)
            .setSmallIcon(isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone)
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

    @Override public void onDestroy() {
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(Constants.GROUP_CALL_ONGOING_NOTIF_ID);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
