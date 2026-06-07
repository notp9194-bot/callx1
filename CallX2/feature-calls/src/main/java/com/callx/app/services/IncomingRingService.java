package com.callx.app.services;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import com.callx.app.calls.R;
import com.callx.app.incoming.IncomingCallActivity;
import com.callx.app.utils.Constants;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

/**
 * Foreground service that rings on incoming call even from killed state.
 *
 * Feature 2 — BUSY SIGNAL:
 *   Jab yeh service start ho aur CallForegroundService already chal rahi ho
 *   (matlab user already ek call mein hai), toh ring karne ki bajaye Firebase
 *   mein "busy" likh do aur band ho jao. Doosre caller ko auto-missed call
 *   notification milegi.
 *
 * Feature 3 — AUTO CANCEL + MISSED CALL:
 *   Firebase mein activeCalls/{callId}/status watch karo.
 *   Agar caller ne kat diya (cancelled/ended) ya timeout hua toh:
 *     1. Ring notification turant cancel karo
 *     2. Missed call notification dikhao
 *     3. Service band karo
 */
public class IncomingRingService extends Service {
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private final Handler stopHandler = new Handler(Looper.getMainLooper());

    // Firebase listener — caller cancel detect karne ke liye
    private DatabaseReference callStatusRef;
    private ValueEventListener callStatusListener;

    // Call info — missed call notif ke liye store karo
    private String savedCallId;
    private String savedFromUid;
    private String savedFromName;
    private String savedFromPhoto;
    private boolean savedIsVideo;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String callId    = intent != null ? intent.getStringExtra(Constants.EXTRA_CALL_ID)     : "";
        String fromUid   = intent != null ? intent.getStringExtra(Constants.EXTRA_PARTNER_UID)  : "";
        String fromName  = intent != null ? intent.getStringExtra(Constants.EXTRA_PARTNER_NAME) : "Unknown";
        String fromPhoto = intent != null ? intent.getStringExtra(Constants.EXTRA_PARTNER_PHOTO): "";
        String fromThumb = intent != null ? intent.getStringExtra("partnerThumb")               : "";
        boolean isVideo  = intent != null && intent.getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);
        if (fromPhoto == null) fromPhoto = "";
        if (fromThumb == null) fromThumb = "";

        // Save for missed call notification
        savedCallId   = callId;
        savedFromUid  = fromUid;
        savedFromName = fromName;
        savedFromPhoto = fromPhoto;
        savedIsVideo  = isVideo;

        // ── Feature 2: BUSY SIGNAL ────────────────────────────────────────
        if (CallForegroundService.isRunning) {
            if (callId != null && !callId.isEmpty()) {
                try {
                    com.callx.app.utils.FirebaseUtils.db()
                        .getReference("activeCalls")
                        .child(callId)
                        .child("status")
                        .setValue("busy");
                } catch (Exception ignored) {}
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        // ─────────────────────────────────────────────────────────────────

        startForeground(Constants.CALL_RING_NOTIF_ID,
            buildNotification(callId, fromUid, fromName, fromPhoto, fromThumb, isVideo));
        startRingtone();
        acquireWakeLock();

        // ── Feature 3: Watch Firebase — caller cancel detect karo ─────────
        watchCallStatus(callId);
        // ─────────────────────────────────────────────────────────────────

        // Auto-stop after timeout — missed call bhi dikhao
        stopHandler.postDelayed(() -> {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Constants.CALL_RING_NOTIF_ID);
            showMissedCallNotification();
            stopSelf();
        }, Constants.CALL_TIMEOUT_MS + 2_000L);

        return START_NOT_STICKY;
    }

    /**
     * Firebase mein activeCalls/{callId}/status watch karo.
     * Agar "cancelled", "ended", "timeout", "busy" aaye toh:
     *   - Ring notif cancel karo
     *   - Missed call notif dikhao
     *   - Service band karo
     */
    private void watchCallStatus(String callId) {
        if (callId == null || callId.isEmpty()) return;
        try {
            callStatusRef = com.callx.app.utils.FirebaseUtils.db()
                .getReference("activeCalls")
                .child(callId)
                .child("status");

            callStatusListener = new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    String status = snapshot.getValue(String.class);
                    if (status == null) return;
                    switch (status) {
                        case "cancelled":
                        case "ended":
                        case "timeout":
                        case "busy":
                            // Caller ne kat diya ya timeout — ring band karo, missed dikhao
                            stopHandler.removeCallbacksAndMessages(null);
                            NotificationManager nm =
                                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                            if (nm != null) nm.cancel(Constants.CALL_RING_NOTIF_ID);
                            showMissedCallNotification();
                            stopSelf();
                            break;
                        case "accepted":
                        case "ongoing":
                            // Callee ne kisi aur device se utha liya — sirf ring band karo
                            stopHandler.removeCallbacksAndMessages(null);
                            NotificationManager nm2 =
                                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                            if (nm2 != null) nm2.cancel(Constants.CALL_RING_NOTIF_ID);
                            stopSelf();
                            break;
                        default:
                            break;
                    }
                }
                @Override
                public void onCancelled(DatabaseError error) {}
            };

            callStatusRef.addValueEventListener(callStatusListener);
        } catch (Exception ignored) {}
    }

    /**
     * Missed call notification — same style jaise CallxMessagingService mein hai.
     * Background/killed state mein callee ko dikhao.
     */
    private void showMissedCallNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            // Tap karne par ChatActivity khule
            android.content.Intent openIntent = new android.content.Intent();
            openIntent.setClassName(this, "com.callx.app.conversation.ChatActivity");
            openIntent.putExtra(Constants.EXTRA_PARTNER_UID,  savedFromUid);
            openIntent.putExtra(Constants.EXTRA_PARTNER_NAME, savedFromName);
            openIntent.putExtra("partnerPhoto", savedFromPhoto);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int notifId = ("missed_" + savedFromUid).hashCode() & 0x7FFFFFFF;
            PendingIntent openPi = PendingIntent.getActivity(this, notifId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Call Back action
            Intent callBackIntent = new Intent(this, NotificationActionReceiver.class)
                .setAction(Constants.ACTION_CALL_BACK)
                .putExtra(Constants.EXTRA_PARTNER_UID,   savedFromUid)
                .putExtra(Constants.EXTRA_PARTNER_NAME,  savedFromName)
                .putExtra(Constants.EXTRA_PARTNER_PHOTO, savedFromPhoto)
                .putExtra(Constants.EXTRA_IS_VIDEO,      savedIsVideo)
                .putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
            PendingIntent callBackPi = PendingIntent.getBroadcast(this,
                ("cb_" + savedFromUid).hashCode(), callBackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                    Constants.CHANNEL_CALLS_MISSED)
                .setSmallIcon(R.drawable.ic_call_notification)
                .setContentTitle("Missed call from " + savedFromName)
                .setContentText("Tap to call back")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_phone, "📞 Call Back", callBackPi)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL);

            // ── Message button — call nahi karna toh message bhejo ────────
            Intent msgIntent = new Intent(this, NotificationActionReceiver.class)
                .setAction(Constants.ACTION_MISSED_CALL_MESSAGE)
                .putExtra(Constants.EXTRA_PARTNER_UID,   savedFromUid)
                .putExtra(Constants.EXTRA_PARTNER_NAME,  savedFromName)
                .putExtra(Constants.EXTRA_PARTNER_PHOTO, savedFromPhoto)
                .putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
            PendingIntent msgPi = PendingIntent.getBroadcast(this,
                ("msg_" + savedFromUid).hashCode(), msgIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(R.drawable.ic_send, "💬 Message", msgPi);

            // Avatar async download
            final String photoUrl = savedFromPhoto;
            new Thread(() -> {
                try {
                    if (!photoUrl.isEmpty()) {
                        java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(photoUrl).openConnection();
                        c.setDoInput(true); c.connect();
                        Bitmap bm = BitmapFactory.decodeStream(c.getInputStream());
                        if (bm != null) b.setLargeIcon(bm);
                    }
                } catch (Exception ignored2) {}
                nm.notify(notifId, b.build());
            }).start();

        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String callId, String fromUid,
                                           String fromName, String fromPhoto,
                                           String fromThumb, boolean isVideo) {
        Intent fullIntent = new Intent(this, IncomingCallActivity.class);
        fullIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        fullIntent.putExtra(Constants.EXTRA_CALL_ID,       callId);
        fullIntent.putExtra(Constants.EXTRA_PARTNER_UID,   fromUid);
        fullIntent.putExtra(Constants.EXTRA_PARTNER_NAME,  fromName);
        fullIntent.putExtra(Constants.EXTRA_PARTNER_PHOTO, fromPhoto);
        fullIntent.putExtra("partnerThumb",                fromThumb);
        fullIntent.putExtra(Constants.EXTRA_IS_VIDEO,      isVideo);
        PendingIntent fullPi = PendingIntent.getActivity(this,
            Constants.CALL_RING_NOTIF_ID, fullIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent declineIntent = new Intent(this, NotificationActionReceiver.class);
        declineIntent.setAction(Constants.ACTION_DECLINE_CALL);
        declineIntent.putExtra(Constants.EXTRA_CALL_ID,       callId);
        declineIntent.putExtra(Constants.EXTRA_PARTNER_UID,   fromUid);
        declineIntent.putExtra(Constants.EXTRA_PARTNER_NAME,  fromName);
        declineIntent.putExtra(Constants.EXTRA_PARTNER_PHOTO, fromPhoto);
        declineIntent.putExtra("partnerThumb",                 fromThumb);
        declineIntent.putExtra(Constants.EXTRA_IS_VIDEO,      isVideo);
        declineIntent.putExtra(Constants.EXTRA_NOTIF_ID,      Constants.CALL_RING_NOTIF_ID);
        PendingIntent declinePi = PendingIntent.getBroadcast(this,
            Constants.CALL_RING_NOTIF_ID + 1, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = isVideo ? "Incoming video call" : "Incoming voice call";

        NotificationCompat.Builder b = new NotificationCompat.Builder(
                this, Constants.CHANNEL_CALLS_INCOMING)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(fromName)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(Constants.CALL_TIMEOUT_MS)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Person caller = new Person.Builder()
                .setName(fromName)
                .setImportant(true)
                .build();
            b.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, fullPi));
        } else {
            b.addAction(R.drawable.ic_phone_off, "Decline", declinePi)
             .addAction(R.drawable.ic_phone,     "Accept",  fullPi);
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
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "callx:incoming_ring");
            wakeLock.acquire(Constants.CALL_TIMEOUT_MS + 5_000L);
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopHandler.removeCallbacksAndMessages(null);
        // Firebase listener remove karo — memory leak nahi hoga
        try {
            if (callStatusRef != null && callStatusListener != null)
                callStatusRef.removeEventListener(callStatusListener);
        } catch (Exception ignored) {}
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
