package com.callx.app.smallwindow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.callx.app.R;

/**
 * SmallWindowService — Floating window ko alive rakhne ke liye foreground service.
 *
 * Start karo:
 *   Intent svc = new Intent(context, SmallWindowService.class);
 *   svc.putExtra("userId", "uid_123");
 *   svc.putExtra("name", "Ali Hassan");
 *   svc.putExtra("status", "Online");
 *   context.startForegroundService(svc);   // API 26+
 *
 * Stop karo:
 *   context.stopService(new Intent(context, SmallWindowService.class));
 */
public class SmallWindowService extends Service {

    public static final String EXTRA_USER_ID = "userId";
    public static final String EXTRA_NAME    = "name";
    public static final String EXTRA_STATUS  = "status";

    private static final String CHANNEL_ID = "small_window_channel";
    private static final int    NOTIF_ID   = 9901;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String userId = intent != null ? intent.getStringExtra(EXTRA_USER_ID) : null;
        String name   = intent != null ? intent.getStringExtra(EXTRA_NAME)    : "CallX";
        String status = intent != null ? intent.getStringExtra(EXTRA_STATUS)  : "";

        // Android 14+ (API 34): specialUse type requires explicit foreground type flag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(name, status),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotification(name, status));
        }

        // Show the floating window — pass userId for deep-link / chat open
        SmallWindowManager.getInstance().show(getApplicationContext(), userId, name, status);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        SmallWindowManager.getInstance().dismiss(getApplicationContext());
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Notification ──────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Small Window",
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("CallX floating window");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String name, String status) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("CallX — " + (name != null ? name : ""))
            .setContentText(status != null ? status : "Small Window active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build();
    }
}
