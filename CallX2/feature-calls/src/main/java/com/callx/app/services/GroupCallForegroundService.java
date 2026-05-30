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
import com.callx.app.utils.Constants;

/**
 * GroupCallForegroundService — ongoing notification while group call is active.
 *
 * Features:
 *  - Persistent ongoing notification with group name + timer text
 *  - "End Call" action via broadcast
 *  - START_NOT_STICKY (stops when killed by OS — call ends too)
 *  - Updates participant count via startService re-call
 *  - BUG-4 FIX: Live timer — updates every second like 1:1 CallForegroundService
 */
public class GroupCallForegroundService extends Service {

    public static final String EXTRA_GROUP_NAME        = "fg_group_name";
    public static final String EXTRA_CALL_ID           = "fg_call_id";
    public static final String EXTRA_IS_VIDEO          = "fg_is_video";
    public static final String EXTRA_PARTICIPANT_COUNT  = "fg_participant_count";

    private String  groupName        = "";
    private String  callId           = "";
    private boolean isVideo          = false;
    private int     participantCount = 2;
    private long    startedAt        = 0;

    // BUG-4 FIX: ticker for live call duration
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private Runnable tickRunnable;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String gn = intent.getStringExtra(EXTRA_GROUP_NAME);
            String ci = intent.getStringExtra(EXTRA_CALL_ID);
            if (gn != null) groupName        = gn;
            if (ci != null) callId           = ci;
            isVideo          = intent.getBooleanExtra(EXTRA_IS_VIDEO, false);
            participantCount = intent.getIntExtra(EXTRA_PARTICIPANT_COUNT, 2);
        }

        // BUG-4 FIX: record start time only once; re-calls (participant count update) preserve it
        if (startedAt == 0) startedAt = System.currentTimeMillis();

        startForeground(Constants.GROUP_CALL_ONGOING_NOTIF_ID,
            buildNotification("0:00"));

        // BUG-4 FIX: start ticker only if not already running
        if (tickRunnable == null) startTicker();

        return START_NOT_STICKY;
    }

    // BUG-4 FIX: tick every second and refresh notification duration
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
        // BUG-4 FIX: live duration shown, plus participant count
        String text  = (isVideo ? "Video call" : "Voice call")
            + " • " + duration
            + " • " + participantCount + " participant"
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
        // BUG-4 FIX: stop ticker on destroy
        tickHandler.removeCallbacksAndMessages(null);
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(Constants.GROUP_CALL_ONGOING_NOTIF_ID);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
