package com.callx.app.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import com.callx.app.R;
import com.callx.app.activities.IncomingGroupCallActivity;
import com.callx.app.utils.Constants;

/**
 * GroupCallRingService — Rings and shows a full-screen incoming group call
 * notification even when the app process is KILLED (background/terminated).
 *
 * Features:
 *  - Foreground service type: phoneCall (survives process kill via FCM)
 *  - Full-screen intent → IncomingGroupCallActivity (lock screen / AOD)
 *  - "Join" action (activity PendingIntent — avoids bg-start restriction)
 *  - "Decline" action → broadcast to GroupCallActionReceiver
 *  - Android 12+ CallStyle: green call chip on status bar
 *  - Looping device ringtone via MediaPlayer
 *  - SCREEN_BRIGHT wake lock → wakes screen on incoming call
 *  - Auto-cancel after CALL_TIMEOUT_MS + 2s buffer
 *  - stopWithTask=false → service keeps running even if launcher task is swiped
 */
public class GroupCallRingService extends Service {

    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;
    private final Handler stopHandler = new Handler(Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String callId     = intent != null
            ? intent.getStringExtra(Constants.EXTRA_CALL_ID)         : "";
        String groupId    = intent != null
            ? intent.getStringExtra(Constants.EXTRA_GROUP_ID)        : "";
        String groupName  = intent != null
            ? intent.getStringExtra(Constants.EXTRA_GROUP_NAME)      : "Group";
        String groupIcon  = intent != null
            ? intent.getStringExtra(Constants.EXTRA_GROUP_ICON)      : "";
        String callerUid  = intent != null
            ? intent.getStringExtra(Constants.EXTRA_GROUP_CALLER_UID)  : "";
        String callerName = intent != null
            ? intent.getStringExtra(Constants.EXTRA_GROUP_CALLER_NAME) : "Someone";
        boolean isVideo   = intent != null
            && intent.getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);

        startForeground(Constants.GROUP_CALL_RING_NOTIF_ID,
            buildRingNotification(callId, groupId, groupName, groupIcon,
                callerUid, callerName, isVideo));
        startRingtone();
        acquireWakeLock();

        // Auto stop on timeout
        stopHandler.postDelayed(() -> {
            NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Constants.GROUP_CALL_RING_NOTIF_ID);
            stopSelf();
        }, Constants.CALL_TIMEOUT_MS + 2_000L);

        return START_NOT_STICKY;
    }

    private Notification buildRingNotification(
            String callId, String groupId, String groupName, String groupIcon,
            String callerUid, String callerName, boolean isVideo) {

        // ── Full-screen / tap intent ──────────────────────────────────────
        Intent fullIntent = new Intent(this, IncomingGroupCallActivity.class);
        fullIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        fullIntent.putExtra(IncomingGroupCallActivity.EXTRA_CALL_ID,    callId);
        fullIntent.putExtra(IncomingGroupCallActivity.EXTRA_GROUP_ID,   groupId);
        fullIntent.putExtra(IncomingGroupCallActivity.EXTRA_GROUP_NAME, groupName);
        fullIntent.putExtra(IncomingGroupCallActivity.EXTRA_GROUP_ICON, groupIcon);
        fullIntent.putExtra(IncomingGroupCallActivity.EXTRA_CALLER_UID,  callerUid);
        fullIntent.putExtra(IncomingGroupCallActivity.EXTRA_CALLER_NAME, callerName);
        fullIntent.putExtra(IncomingGroupCallActivity.EXTRA_IS_VIDEO,   isVideo);

        PendingIntent fullPi = PendingIntent.getActivity(this,
            Constants.GROUP_CALL_RING_NOTIF_ID, fullIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // ── Decline action → GroupCallActionReceiver ──────────────────────
        Intent declineIntent = new Intent(this, GroupCallActionReceiver.class);
        declineIntent.setAction(Constants.ACTION_GROUP_DECLINE_CALL);
        declineIntent.putExtra(Constants.EXTRA_CALL_ID,            callId);
        declineIntent.putExtra(Constants.EXTRA_GROUP_ID,           groupId);
        declineIntent.putExtra(Constants.EXTRA_GROUP_CALLER_UID,   callerUid);
        declineIntent.putExtra(Constants.EXTRA_NOTIF_ID,           Constants.GROUP_CALL_RING_NOTIF_ID);
        PendingIntent declinePi = PendingIntent.getBroadcast(this,
            Constants.GROUP_CALL_RING_NOTIF_ID + 1, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // ── Join action = same as full-screen tap ─────────────────────────
        PendingIntent joinPi = fullPi;

        String nameDisplay = (groupName != null && !groupName.isEmpty())
            ? groupName : "Group";
        String callTypeText = isVideo ? "Incoming group video call"
                                      : "Incoming group voice call";
        String bodyText = callerName + " started a " + (isVideo ? "video" : "voice") + " call";
        int icon = isVideo
            ? R.drawable.ic_video_call : R.drawable.ic_phone;

        NotificationCompat.Builder b = new NotificationCompat.Builder(
                this, Constants.CHANNEL_GROUP_CALLS_INCOMING)
            .setSmallIcon(icon)
            .setContentTitle(nameDisplay)
            .setContentText(bodyText)
            .setSubText(callTypeText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(Constants.CALL_TIMEOUT_MS)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .addAction(R.drawable.ic_phone, "Join", joinPi)
            .addAction(R.drawable.ic_phone_off, "Decline", declinePi);

        // Android 12+ — CallStyle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Person caller = new Person.Builder()
                .setName(callerName != null ? callerName : "Group call")
                .setImportant(true)
                .build();
            b.setStyle(NotificationCompat.CallStyle
                .forIncomingCall(caller, declinePi, joinPi));
        }

        return b.build();
    }

    private void startRingtone() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "callx:group_ring");
            wakeLock.acquire(Constants.CALL_TIMEOUT_MS + 5_000L);
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopHandler.removeCallbacksAndMessages(null);
        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
