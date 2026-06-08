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
import androidx.core.app.RemoteInput;
import com.callx.app.calls.R;
import com.callx.app.incoming.IncomingCallActivity;
import com.callx.app.utils.Constants;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

public class IncomingRingService extends Service {
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private final Handler stopHandler = new Handler(Looper.getMainLooper());

    private DatabaseReference callStatusRef;
    private ValueEventListener callStatusListener;

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

        savedCallId    = callId;
        savedFromUid   = fromUid;
        savedFromName  = fromName;
        savedFromPhoto = fromPhoto;
        savedIsVideo   = isVideo;

        // BUSY SIGNAL: already in a call
        if (CallForegroundService.isRunning) {
            if (callId != null && !callId.isEmpty()) {
                try {
                    com.callx.app.utils.FirebaseUtils.db()
                        .getReference("activeCalls").child(callId).child("status").setValue("busy");
                } catch (Exception ignored) {}
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(Constants.CALL_RING_NOTIF_ID,
            buildNotification(callId, fromUid, fromName, fromPhoto, fromThumb, isVideo));
        startRingtone();
        acquireWakeLock();
        watchCallStatus(callId);

        stopHandler.postDelayed(() -> {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Constants.CALL_RING_NOTIF_ID);
            showMissedCallNotification();
            stopSelf();
        }, Constants.CALL_TIMEOUT_MS + 2_000L);

        return START_NOT_STICKY;
    }

    private void watchCallStatus(String callId) {
        if (callId == null || callId.isEmpty()) return;
        try {
            callStatusRef = com.callx.app.utils.FirebaseUtils.db()
                .getReference("activeCalls").child(callId).child("status");

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
                            stopHandler.removeCallbacksAndMessages(null);
                            NotificationManager nm =
                                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                            if (nm != null) nm.cancel(Constants.CALL_RING_NOTIF_ID);
                            showMissedCallNotification();
                            stopSelf();
                            break;
                        case "accepted":
                        case "ongoing":
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

    /** Full 8-feature missed call notification */
    private void showMissedCallNotification() {
        try {
            final String callerUid   = savedFromUid   != null ? savedFromUid   : "";
            final String callerName  = savedFromName  != null ? savedFromName  : "Unknown";
            final String callerPhoto = savedFromPhoto != null ? savedFromPhoto : "";
            final boolean missedIsVideo = savedIsVideo;

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            // Feature 8: Lock screen privacy
            boolean appLockOn = false;
            try {
                android.content.SharedPreferences lp = getSharedPreferences("app_lock_prefs", MODE_PRIVATE);
                appLockOn = lp.getBoolean("lock_enabled", false);
            } catch (Exception ignored) {}
            int lockVis = appLockOn
                ? NotificationCompat.VISIBILITY_PRIVATE
                : NotificationCompat.VISIBILITY_PUBLIC;

            // Feature 5: Grouping — count same-caller missed calls, SEPARATED by type
            // Voice and video use different keys so they never mix counts
            String callTypeStr = missedIsVideo ? "video call" : "voice call";
            String callTypeTag = missedIsVideo ? "video" : "voice";   // used in keys below

            android.content.SharedPreferences countPrefs = getSharedPreferences("callx_missed_counts", MODE_PRIVATE);
            String countKey = Constants.PREF_MISSED_CALL_COUNT + callTypeTag + "_" + callerUid;
            int missedCount = countPrefs.getInt(countKey, 0) + 1;
            countPrefs.edit().putInt(countKey, missedCount).apply();

            // notifId also separated by type → voice and video show as TWO distinct notifications
            int notifId = ("missed_" + callTypeTag + "_" + callerUid).hashCode() & 0x7FFFFFFF;

            // Current time for body text
            String missedAt = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(new java.util.Date());

            // Title — clean, no repeat
            String notifTitle = missedCount > 1
                ? missedCount + " missed " + callTypeStr + "s from " + callerName
                : (missedIsVideo ? "\uD83D\uDCF9 Missed video call" : "\uD83D\uDCDE Missed voice call") + " from " + callerName;

            // Body — no repeat of title, just time info
            String bigText = missedCount > 1
                ? "Last missed \u2022 " + missedAt
                : "Tap to call back \u2022 " + missedAt;

            // Tap -> ChatActivity
            android.content.Intent openIntent = new android.content.Intent();
            openIntent.setClassName(this, "com.callx.app.conversation.ChatActivity");
            openIntent.putExtra(Constants.EXTRA_PARTNER_UID,  callerUid);
            openIntent.putExtra(Constants.EXTRA_PARTNER_NAME, callerName);
            openIntent.putExtra("partnerPhoto", callerPhoto);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent openPi = android.app.PendingIntent.getActivity(
                this, notifId, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

            // Feature 1a: Voice call back
            android.content.Intent cbIntent = new android.content.Intent(this, NotificationActionReceiver.class)
                .setAction(Constants.ACTION_CALL_BACK)
                .putExtra(Constants.EXTRA_PARTNER_UID,   callerUid)
                .putExtra(Constants.EXTRA_PARTNER_NAME,  callerName)
                .putExtra(Constants.EXTRA_PARTNER_PHOTO, callerPhoto)
                .putExtra(Constants.EXTRA_IS_VIDEO,      false)
                .putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
            android.app.PendingIntent callBackPi = android.app.PendingIntent.getBroadcast(
                this, ("cb_" + callerUid).hashCode(), cbIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

            // Feature 1b: Video call back (only if original was video)
            android.app.PendingIntent videoCallBackPi = null;
            if (missedIsVideo) {
                android.content.Intent vcbIntent = new android.content.Intent(this, NotificationActionReceiver.class)
                    .setAction(Constants.ACTION_VIDEO_CALL_BACK)
                    .putExtra(Constants.EXTRA_PARTNER_UID,   callerUid)
                    .putExtra(Constants.EXTRA_PARTNER_NAME,  callerName)
                    .putExtra(Constants.EXTRA_PARTNER_PHOTO, callerPhoto)
                    .putExtra(Constants.EXTRA_IS_VIDEO,      true)
                    .putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
                videoCallBackPi = android.app.PendingIntent.getBroadcast(
                    this, ("vcb_" + callerUid).hashCode(), vcbIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            }

            // Feature 2+3: Quick reply chips merged into RemoteInput via setChoices()
            // Android only shows 3 actions max — so chips live inside Message action, not as separate actions
            RemoteInput remoteInput = new RemoteInput.Builder(Constants.KEY_MISSED_CALL_REPLY)
                .setLabel("Write a message\u2026")
                .setChoices(new CharSequence[]{
                    "On my way \uD83D\uDE97",
                    "Call you later \uD83D\uDCDE",
                    "In a meeting \uD83E\uDD1D"
                })
                .build();
            android.content.Intent msgIntent = new android.content.Intent(this, NotificationActionReceiver.class)
                .setAction(Constants.ACTION_MISSED_CALL_MESSAGE)
                .putExtra(Constants.EXTRA_PARTNER_UID,   callerUid)
                .putExtra(Constants.EXTRA_PARTNER_NAME,  callerName)
                .putExtra(Constants.EXTRA_PARTNER_PHOTO, callerPhoto)
                .putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
            android.app.PendingIntent msgPi = android.app.PendingIntent.getBroadcast(
                this, ("mcmsg_" + callerUid).hashCode(), msgIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
            NotificationCompat.Action msgAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_send, "\uD83D\uDCAC Message", msgPi)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build();

            // Feature 4: Snooze 10 min
            android.content.Intent snoozeIntent = new android.content.Intent(this, NotificationActionReceiver.class)
                .setAction(Constants.ACTION_MISSED_CALL_SNOOZE)
                .putExtra(Constants.EXTRA_PARTNER_UID,   callerUid)
                .putExtra(Constants.EXTRA_PARTNER_NAME,  callerName)
                .putExtra(Constants.EXTRA_PARTNER_PHOTO, callerPhoto)
                .putExtra(Constants.EXTRA_IS_VIDEO,      missedIsVideo)
                .putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
            android.app.PendingIntent snoozePi = android.app.PendingIntent.getBroadcast(
                this, ("snz_" + callerUid).hashCode(), snoozeIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Action snoozeAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_timer, "\u23F0 10 min", snoozePi).build();

            int callIcon = missedIsVideo ? R.drawable.ic_video_call : R.drawable.ic_call_notification;

            // Build notification — 3 actions max (Android hard limit)
            // Video call: [📹 Video][📞 Voice][⏰ 10 min]  — Message+chips inside 💬 tap
            // Voice call: [📞 Voice][💬 Message][⏰ 10 min] — chips inside 💬 tap
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, Constants.CHANNEL_CALLS_MISSED)
                .setSmallIcon(callIcon)
                .setContentTitle(notifTitle)
                .setContentText(bigText)
                .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(bigText)
                    .setSummaryText(callTypeStr))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .setVisibility(lockVis)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setGroup(Constants.GROUP_KEY_MISSED_CALLS);

            if (missedIsVideo && videoCallBackPi != null) {
                b.addAction(R.drawable.ic_video_call, "\uD83D\uDCF9 Video", videoCallBackPi); // 1st
                b.addAction(R.drawable.ic_phone, "\uD83D\uDCDE Voice", callBackPi);           // 2nd
                b.addAction(snoozeAction);                                                     // 3rd
                b.addAction(msgAction);                                                        // expanded
            } else {
                b.addAction(R.drawable.ic_phone, "\uD83D\uDCDE Voice", callBackPi);           // 1st
                b.addAction(msgAction);                                                        // 2nd
                b.addAction(snoozeAction);                                                     // 3rd
            }
            nm.notify(notifId, b.build());

            // Feature 5: Group summary (2+ missed calls of same type)
            if (missedCount > 1) {
                android.app.Notification summary = new NotificationCompat.Builder(this, Constants.CHANNEL_CALLS_MISSED)
                    .setSmallIcon(callIcon)
                    .setContentTitle(missedIsVideo ? "Missed video calls" : "Missed voice calls")
                    .setContentText(missedCount + " missed " + callTypeStr + "s from " + callerName)
                    .setGroup(Constants.GROUP_KEY_MISSED_CALLS)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
                nm.notify(("summary_missed_" + callTypeTag + "_" + callerUid).hashCode() & 0x7FFFFFFF, summary);
            }

            // SubText — sirf "Just missed", system timestamp alag se dikhata hai
            b.setSubText("Just missed");

            // Avatar: best-effort async update only
            if (!callerPhoto.isEmpty()) {
                new Thread(() -> {
                    try {
                        java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(callerPhoto).openConnection();
                        c.setDoInput(true); c.connect();
                        Bitmap avatarBm = BitmapFactory.decodeStream(c.getInputStream());
                        if (avatarBm != null) {
                            b.setLargeIcon(avatarBm);
                            nm.notify(notifId, b.build());
                        }
                    } catch (Exception ignored2) {}
                }).start();
            }

        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String callId, String fromUid,
                                           String fromName, String fromPhoto,
                                           String fromThumb, boolean isVideo) {
        Intent fullIntent = new Intent(this, IncomingCallActivity.class);
        fullIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
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
        int icon = isVideo ? R.drawable.ic_video_call : R.drawable.ic_call_notification;

        return new NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
            .setSmallIcon(icon)
            .setContentTitle(fromName)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullPi)
            .setFullScreenIntent(fullPi, true)
            .addAction(R.drawable.ic_phone_off, "Decline", declinePi)
            .addAction(R.drawable.ic_phone, "Answer", fullPi)
            .build();
    }

    private void startRingtone() {
        try {
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            player = new MediaPlayer();
            player.setDataSource(this, ringtoneUri);
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
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callx:ring");
                wakeLock.acquire(Constants.CALL_TIMEOUT_MS + 5_000L);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopHandler.removeCallbacksAndMessages(null);
        if (player != null) { try { player.stop(); player.release(); } catch (Exception ignored) {} player = null; }
        if (wakeLock != null && wakeLock.isHeld()) { try { wakeLock.release(); } catch (Exception ignored) {} }
        if (callStatusRef != null && callStatusListener != null) {
            try { callStatusRef.removeEventListener(callStatusListener); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}
