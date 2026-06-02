package com.callx.app.services;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import com.callx.app.activities.CallActivity;
import com.callx.app.utils.Constants;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.CallLogEntity;

/**
 * Foreground service shown during an active (connected) call.
 * Shows a persistent CallStyle notification (Android 12+) with an
 * "End Call" action so the user can hang up from the shade.
 * Updated every second with the live call duration.
 *
 * Lifecycle:
 *  start  → CallActivity.onConnected()
 *  stop   → CallActivity.endCall()
 *
 * FIX-KILLED: onTaskRemoved() writes "ended" to Firebase so the remote party
 *             knows the call dropped when the OS kills the app.
 */
public class CallForegroundService extends android.app.Service {
    public static final String EXTRA_PARTNER_UID = "fg_partner_uid";
    public static final String EXTRA_DIRECTION    = "fg_direction";
    public static final int ID = Constants.CALL_ONGOING_NOTIF_ID;
    private String callerName   = "CallX";
    private String callId       = "";
    private String partnerThumb = "";
    private boolean isVideo     = false;
    private String  partnerUid  = "";
    private String  direction   = "outgoing";
    private long startedAt      = 0;
    private Bitmap avatarBitmap = null;
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private Runnable tickRunnable;
    private final ExecutorService bgEx = Executors.newSingleThreadExecutor();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String n = intent.getStringExtra("name");
            String c = intent.getStringExtra("callId");
            String t = intent.getStringExtra("partnerThumb");
            if (n != null && !n.isEmpty()) callerName   = n;
            if (c != null && !c.isEmpty()) callId       = c;
            if (t != null && !t.isEmpty()) partnerThumb = t;
            isVideo = intent.getBooleanExtra("isVideo", false);
            String pu = intent.getStringExtra(EXTRA_PARTNER_UID);
            String di = intent.getStringExtra(EXTRA_DIRECTION);
            if (pu != null && !pu.isEmpty()) partnerUid = pu;
            if (di != null && !di.isEmpty()) direction  = di;
        }
        if (startedAt == 0) startedAt = System.currentTimeMillis();
        startForeground(ID, buildNotification("Connecting..."));
        startTicker();
        if (!partnerThumb.isEmpty()) {
            bgEx.execute(() -> {
                try {
                    HttpURLConnection conn =
                        (HttpURLConnection) new URL(partnerThumb).openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.connect();
                    Bitmap bm = BitmapFactory.decodeStream(conn.getInputStream());
                    if (bm != null) {
                        avatarBitmap = bm;
                        tickHandler.post(() -> updateNotification(
                            String.format("%d:%02d",
                                (System.currentTimeMillis() - startedAt) / 60000,
                                ((System.currentTimeMillis() - startedAt) / 1000) % 60)));
                    }
                } catch (Exception ignored) {}
            });
        }
        return START_STICKY;
    }

    // FIX-KILLED: App swiped away / killed by OS → Firebase mein "ended" likho
    // Taaki remote party ko pata chale call drop ho gayi.
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (callId != null && !callId.isEmpty()) {
            try {
                com.callx.app.utils.FirebaseUtils.db()
                    .getReference("activeCalls")
                    .child(callId)
                    .child("status")
                    .setValue("ended");
            } catch (Exception ignored) {}
        }
        // FIX-KILLED: Room calllog write — app kill hone pe history blank na rahe
        if (startedAt > 0 && !partnerUid.isEmpty()) {
            final long dur = System.currentTimeMillis() - startedAt;
            final String fUid = partnerUid, fDir = direction, fType = isVideo ? "video" : "audio";
            final long fTs = startedAt;
            bgEx.execute(() -> {
                try {
                    CallLogEntity entity = new CallLogEntity();
                    entity.id          = java.util.UUID.randomUUID().toString();
                    entity.partnerUid  = fUid;
                    entity.partnerName = callerName;
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
                updateNotification(String.format("%d:%02d", m, s));
                tickHandler.postDelayed(this, 1_000);
            }
        };
        tickHandler.postDelayed(tickRunnable, 1_000);
    }

    private void updateNotification(String duration) {
        try {
            android.app.NotificationManager nm =
                (android.app.NotificationManager)
                getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, buildNotification("Connected • " + duration));
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String subtitle) {
        Intent endIntent = new Intent(this, NotificationActionReceiver.class);
        endIntent.setAction(Constants.ACTION_END_CALL);
        endIntent.putExtra(Constants.EXTRA_CALL_ID, callId);
        PendingIntent endPi = PendingIntent.getBroadcast(this, ID + 1, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, CallActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(
                this, Constants.CHANNEL_CALLS)
            .setSmallIcon(com.callx.app.calls.R.drawable.ic_call_notification)
            .setContentTitle(callerName)
            .setContentText(subtitle)
            .setOngoing(true)
            .setContentIntent(openPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", endPi);

        if (avatarBitmap != null) b.setLargeIcon(avatarBitmap);

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

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}
