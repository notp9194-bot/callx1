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

/**
 * GroupCallForegroundService — ongoing notification while group call is active.
 *
 * FIX-KILLED: onTaskRemoved() → Firebase mein "ended" + participant status likho
 *             taaki baki sab log ko pata chale ki call drop ho gayi.
 */
public class GroupCallForegroundService extends Service {

    public static final String EXTRA_GROUP_NAME        = "fg_group_name";
    public static final String EXTRA_CALL_ID           = "fg_call_id";
    public static final String EXTRA_IS_VIDEO          = "fg_is_video";
    public static final String EXTRA_PARTICIPANT_COUNT = "fg_participant_count";
    public static final String EXTRA_MY_UID            = "fg_my_uid";
    public static final String EXTRA_GROUP_ID          = "fg_group_id";
    public static final String EXTRA_IS_CALLER         = "fg_is_caller";

    // Static field — set by GroupCallActivity when first ICE peer connects
    public static volatile long connectedAt = 0;
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
            isCaller = intent.getBooleanExtra(EXTRA_IS_CALLER, false);
            isVideo          = intent.getBooleanExtra(EXTRA_IS_VIDEO, false);
            participantCount = intent.getIntExtra(EXTRA_PARTICIPANT_COUNT, 2);
        }
        if (startedAt == 0) startedAt = System.currentTimeMillis();
        startForeground(Constants.GROUP_CALL_ONGOING_NOTIF_ID, buildNotification("0:00"));
        if (tickRunnable == null) startTicker();
        return START_NOT_STICKY;
    }

    // FIX-KILLED: App kill hui → Firebase cleanup karo taaki group ka baaki logo ko pata chale
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (callId != null && !callId.isEmpty()) {
            try {
                com.google.firebase.database.DatabaseReference callRef =
                    com.callx.app.utils.FirebaseUtils.db()
                        .getReference("groupCalls").child(callId);
                // Participant ko "left" mark karo
                if (myUid != null && !myUid.isEmpty()) {
                    callRef.child("participants").child(myUid).child("status").setValue("left");
                }
            } catch (Exception ignored) {}
        }
        // FIX-KILLED: Room calllog write — app kill hone pe group call history blank na rahe
        // FIX: use connectedAt for accurate duration (startedAt = join time, connectedAt = first ICE connect)
        long effectiveStart = connectedAt > 0 ? connectedAt : 0;
        if (effectiveStart > 0 && !groupId.isEmpty()) {
            final long dur = System.currentTimeMillis() - effectiveStart;
            final String fGroupId = groupId, fGroupName = groupName;
            final String fDir = isCaller ? "outgoing" : "incoming";
            final String fType = isVideo ? "group_video" : "group_audio";
            final long fTs = startedAt;
            bgEx.execute(() -> {
                try {
                    CallLogEntity entity = new CallLogEntity();
                    entity.id          = java.util.UUID.randomUUID().toString();
                    entity.partnerUid  = fGroupId;
                    entity.partnerName = fGroupName != null ? fGroupName : "";
                    entity.direction   = fDir;
                    entity.mediaType   = fType;
                    entity.timestamp   = fTs;
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
        tickHandler.removeCallbacksAndMessages(null);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(Constants.GROUP_CALL_ONGOING_NOTIF_ID);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
