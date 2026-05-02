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
import com.callx.app.activities.IncomingCallActivity;
import com.callx.app.utils.Constants;
/**
 * Foreground service that rings and shows a full-screen incoming-call
 * notification even when the app process is killed.
 *
 * Notification includes:
 *  - Full-screen intent  → IncomingCallActivity (locked screen / AOD)
 *  - "Accept" action     → opens IncomingCallActivity via activity PendingIntent
 *  - "Decline" action    → broadcast to NotificationActionReceiver
 *  - CallStyle           → Android 12+ green call chip on status bar
 *  - PRIORITY_MAX        → pre-Android 12 heads-up + full-screen
 *  - Looping ringtone    → device default ringtone
 *  - SCREEN_BRIGHT wake lock → screen wakes on incoming call
 */
public class IncomingRingService extends Service {
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private final Handler stopHandler = new Handler(Looper.getMainLooper());
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String callId   = intent != null ? intent.getStringExtra(Constants.EXTRA_CALL_ID)   : "";
        String fromUid  = intent != null ? intent.getStringExtra(Constants.EXTRA_PARTNER_UID)  : "";
        String fromName = intent != null ? intent.getStringExtra(Constants.EXTRA_PARTNER_NAME) : "Unknown";
        boolean isVideo = intent != null && intent.getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);
        startForeground(Constants.CALL_RING_NOTIF_ID,
            buildNotification(callId, fromUid, fromName, isVideo));
        startRingtone();
        acquireWakeLock();
        // Auto-stop after timeout
        stopHandler.postDelayed(() -> {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Constants.CALL_RING_NOTIF_ID);
            stopSelf();
        }, Constants.CALL_TIMEOUT_MS + 2_000L);
        return START_NOT_STICKY;
    }
    private Notification buildNotification(String callId, String fromUid,
                                           String fromName, boolean isVideo) {
        // ── Full-screen / tap intent → IncomingCallActivity ─────────────
        Intent fullIntent = new Intent(this, IncomingCallActivityKt.class);
        fullIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        fullIntent.putExtra(Constants.EXTRA_CALL_ID,     callId);
        fullIntent.putExtra(Constants.EXTRA_PARTNER_UID, fromUid);
        fullIntent.putExtra(Constants.EXTRA_PARTNER_NAME, fromName);
        fullIntent.putExtra(Constants.EXTRA_IS_VIDEO,    isVideo);
        PendingIntent fullPi = PendingIntent.getActivity(this,
            Constants.CALL_RING_NOTIF_ID, fullIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // ── Accept button → activity PendingIntent (avoids bg-start restriction) ─
        PendingIntent acceptPi = fullPi; // tap opens IncomingCallActivity
        // ── Decline button → broadcast → NotificationActionReceiver ─────
        Intent declineIntent = new Intent(this, NotificationActionReceiver.class);
        declineIntent.setAction(Constants.ACTION_DECLINE_CALL);
        declineIntent.putExtra(Constants.EXTRA_CALL_ID,       callId);
        declineIntent.putExtra(Constants.EXTRA_PARTNER_UID,   fromUid);
        declineIntent.putExtra(Constants.EXTRA_PARTNER_NAME,  fromName);
        declineIntent.putExtra(Constants.EXTRA_IS_VIDEO,      isVideo);
        declineIntent.putExtra(Constants.EXTRA_NOTIF_ID,      Constants.CALL_RING_NOTIF_ID);
        PendingIntent declinePi = PendingIntent.getBroadcast(this,
            Constants.CALL_RING_NOTIF_ID + 1, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int icon = isVideo ? android.R.drawable.ic_menu_camera : android.R.drawable.ic_menu_call;
        String text = isVideo ? "Incoming video call" : "Incoming voice call";
        NotificationCompat.Builder b = new NotificationCompat.Builder(
                this, Constants.CHANNEL_CALLS_INCOMING)
            .setSmallIcon(icon)
            .setContentTitle(fromName)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(Constants.CALL_TIMEOUT_MS)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .addAction(android.R.drawable.ic_menu_call,  "Accept",  acceptPi)
            .addAction(android.R.drawable.ic_delete,     "Decline", declinePi);
        // Android 12+ — CallStyle shows green call chip + styled actions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Person caller = new Person.Builder()
                .setName(fromName)
                .setImportant(true)
                .build();
            b.setStyle(NotificationCompat.CallStyle
                .forIncomingCall(caller, declinePi, acceptPi));
        }
        return b.build();
    }
    private void startRingtone() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            player  = new MediaPlayer();
            player.setDataSource(this, uri);
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) {}
    }
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            // SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP wakes the screen
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "callx:incoming_ring");
            wakeLock.acquire(Constants.CALL_TIMEOUT_MS + 5_000L);
        } catch (Exception ignored) {}
    }
    @Override
    public void onDestroy() {
        stopHandler.removeCallbacksAndMessages(null);
        try { if (player != null) { player.stop(); player.release(); player = null; } }
        catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}
