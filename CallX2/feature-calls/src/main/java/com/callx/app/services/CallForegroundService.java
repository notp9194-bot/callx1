package com.callx.app.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.app.Person;
import com.callx.app.activities.CallActivity;
import com.callx.app.utils.Constants;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CallForegroundService — Ongoing notification for active 1:1 calls.
 *
 * Production improvements:
 *  - Mute status tracking: shows "🔇 Muted" in notification subtitle
 *  - Avatar download with exponential backoff retry (max 3 attempts)
 *  - CallStyle notification on Android 12+ (green call chip)
 *  - START_STICKY: OS can restart with null intent; preserves last-known state
 *  - "End Call" action → NotificationActionReceiver broadcast
 *  - Tap notification → returns to CallActivity (FLAG_SINGLE_TOP)
 *  - Live timer updates every second
 */
public class CallForegroundService extends Service {

    public static final int ID = Constants.CALL_ONGOING_NOTIF_ID;

    private String  callerName   = "CallX";
    private String  callId       = "";
    private String  partnerThumb = "";
    private boolean isVideo      = false;
    private boolean micOn        = true;   // ← mute tracking
    private long    startedAt    = 0;
    private Bitmap  avatarBitmap = null;
    private int     avatarRetry  = 0;
    private static final int AVATAR_MAX_RETRY = 3;

    private final Handler        tickHandler = new Handler(Looper.getMainLooper());
    private Runnable             tickRunnable;
    private final ExecutorService bgEx = Executors.newSingleThreadExecutor();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String n = intent.getStringExtra("name");
            String c = intent.getStringExtra("callId");
            String t = intent.getStringExtra("partnerThumb");
            if (n != null && !n.isEmpty()) callerName    = n;
            if (c != null && !c.isEmpty()) callId        = c;
            if (t != null && !t.isEmpty()) partnerThumb  = t;
            isVideo = intent.getBooleanExtra("isVideo", false);

            // Mute status update from CallActivity.toggleMic()
            if (intent.hasExtra("updateMic")) {
                micOn = intent.getBooleanExtra("micOn", true);
                refreshNotification();
                return START_STICKY;
            }
        }
        if (startedAt == 0) startedAt = System.currentTimeMillis();

        startForeground(ID, buildNotification("Connecting…"));
        startTicker();

        // Download avatar in background with retry
        if (!partnerThumb.isEmpty() && avatarBitmap == null) {
            downloadAvatar(partnerThumb, 0);
        }
        return START_STICKY;
    }

    private void downloadAvatar(String url, int attempt) {
        bgEx.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.connect();
                Bitmap bm = BitmapFactory.decodeStream(conn.getInputStream());
                if (bm != null) {
                    avatarBitmap = bm;
                    tickHandler.post(this::refreshNotification);
                } else if (attempt < AVATAR_MAX_RETRY) {
                    tickHandler.postDelayed(() -> downloadAvatar(url, attempt + 1), 2000L * (attempt + 1));
                }
            } catch (Exception ignored) {
                if (attempt < AVATAR_MAX_RETRY) {
                    tickHandler.postDelayed(() -> downloadAvatar(url, attempt + 1), 2000L * (attempt + 1));
                }
            }
        });
    }

    private void startTicker() {
        if (tickRunnable != null) return;
        tickRunnable = new Runnable() {
            @Override public void run() {
                long elapsed = (System.currentTimeMillis() - startedAt) / 1000;
                refreshNotification(String.format("%d:%02d", elapsed / 60, elapsed % 60));
                tickHandler.postDelayed(this, 1_000);
            }
        };
        tickHandler.postDelayed(tickRunnable, 1_000);
    }

    private void refreshNotification() {
        long elapsed = (System.currentTimeMillis() - startedAt) / 1000;
        refreshNotification(String.format("%d:%02d", elapsed / 60, elapsed % 60));
    }

    private void refreshNotification(String duration) {
        try {
            android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, buildNotification("Connected • " + duration));
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String subtitle) {
        // "End Call" broadcast → NotificationActionReceiver
        Intent endIntent = new Intent(this, NotificationActionReceiver.class);
        endIntent.setAction(Constants.ACTION_END_CALL);
        endIntent.putExtra(Constants.EXTRA_CALL_ID, callId);
        PendingIntent endPi = PendingIntent.getBroadcast(this, ID + 1, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Tap → return to CallActivity
        Intent openIntent = new Intent(this, CallActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Show mute status in subtitle
        String displaySubtitle = subtitle;
        if (!micOn) displaySubtitle = "\uD83D\uDD07 Muted  •  " + subtitle; // 🔇

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
            .setSmallIcon(com.callx.app.calls.R.drawable.ic_call_notification)
            .setContentTitle(callerName)
            .setContentText(displaySubtitle)
            .setOngoing(true)
            .setContentIntent(openPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", endPi);

        if (avatarBitmap != null) b.setLargeIcon(avatarBitmap);

        // Android 12+ CallStyle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Person.Builder pb = new Person.Builder().setName(callerName).setImportant(true);
            if (avatarBitmap != null) pb.setIcon(IconCompat.createWithBitmap(avatarBitmap));
            b.setStyle(NotificationCompat.CallStyle.forOngoingCall(pb.build(), endPi));
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        tickHandler.removeCallbacksAndMessages(null);
        try { bgEx.shutdownNow(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
