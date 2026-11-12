package com.callx.app.voicerooms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * VoiceRoomService — foreground service: room minimize / app background hone par bhi
 * audio (aur mic) chalta rahe. Notification se wapas room kholo ya "Leave" karo.
 */
public class VoiceRoomService extends Service {
    static final String CHANNEL = "vr_room_live_service";
    private static final int NOTIF_ID = 7941;
    private static final String ACT_LEAVE = "com.callx.app.voicerooms.LEAVE";
    private static final String ACT_REFRESH = "com.callx.app.voicerooms.REFRESH";

    static void start(Context c) {
        Intent i = new Intent(c, VoiceRoomService.class);
        ContextCompat.startForegroundService(c, i);
    }

    static void refresh(Context c) {
        if (!VrSession.get().isActive()) return;
        Intent i = new Intent(c, VoiceRoomService.class).setAction(ACT_REFRESH);
        ContextCompat.startForegroundService(c, i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, VoiceRoomService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        VrSession s = VrSession.get();
        if (intent != null && ACT_LEAVE.equals(intent.getAction())) {
            s.leave(s.isHost());
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!s.isActive()) {
            // Session nahi hai (process restart) — foreground me gaye bina band karo
            startForegroundCompat(buildNotification(s));
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat(buildNotification(s));
        return START_NOT_STICKY;
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (Build.VERSION.SDK_INT >= 30 && VrSession.hasMicPermission(this)) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(VrSession s) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Voice room (live)",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, VoiceRoomActivity.class);
        open.putExtra(VoiceRoomActivity.EXTRA_ID, s.roomId());
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open, fl);
        PendingIntent leavePi = PendingIntent.getService(this, 2,
                new Intent(this, VoiceRoomService.class).setAction(ACT_LEAVE), fl);

        String sub = s.micOn() ? "You're live \uD83C\uDF99\uFE0F  \u2022  tap to return"
                : "Listening  \u2022  tap to return";
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(s.title().isEmpty() ? "Voice room" : s.title())
                .setContentText(sub)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(openPi)
                .addAction(0, s.isHost() ? "End room" : "Leave", leavePi)
                .build();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
