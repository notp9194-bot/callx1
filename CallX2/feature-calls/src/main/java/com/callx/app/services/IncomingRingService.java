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

            // Feature 5: Grouping — count same-caller missed calls
            android.content.SharedPreferences countPrefs = getSharedPreferences("callx_missed_counts", MODE_PRIVATE);
            String countKey = Constants.PREF_MISSED_CALL_COUNT + callerUid;
            int missedCount = countPrefs.getInt(countKey, 0) + 1;
            countPrefs.edit().putInt(countKey, missedCount).apply();

            int notifId = ("missed_" + callerUid).hashCode() & 0x7FFFFFFF;
            String callTypeStr = missedIsVideo ? "video call" : "voice call";

            // Feature 7: BigText expanded content
            String bigText = missedCount > 1
                ? missedCount + " missed " + callTypeStr + "s from " + callerName
                : "Missed " + callTypeStr + " \u2022 just now";
            String notifTitle = missedCount > 1
                ? missedCount + " missed calls from " + callerName
                : (missedIsVideo ? "\uD83D\uDCF9 Missed video call" : "\uD83D\uDCDE Missed call") + " from " + callerName;

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

            // Feature 2: Quick reply chips
            String[] quickReplies = {
                "On my way \uD83D\uDE97",
                "Call you later \uD83D\uDCDE",
                "In a meeting \uD83E\uDD1D"
            };
            String[] quickActionStrs = {
                Constants.ACTION_QUICK_REPLY_1,
                Constants.ACTION_QUICK_REPLY_2,
                Constants.ACTION_QUICK_REPLY_3
            };
            NotificationCompat.Action[] qrActions = new NotificationCompat.Action[3];
            for (int i = 0; i < 3; i++) {
                android.content.Intent qrIntent = new android.content.Intent(this, NotificationActionReceiver.class)
                    .setAction(quickActionStrs[i])
                    .putExtra(Constants.EXTRA_PARTNER_UID,      callerUid)
                    .putExtra(Constants.EXTRA_PARTNER_NAME,     callerName)
                    .putExtra(Constants.EXTRA_PARTNER_PHOTO,    callerPhoto)
                    .putExtra(Constants.EXTRA_QUICK_REPLY_TEXT, quickReplies[i])
                    .putExtra(Constants.EXTRA_NOTIF_ID,         notifId);
                android.app.PendingIntent qrPi = android.app.PendingIntent.getBroadcast(
                    this, ("qr" + i + "_" + callerUid).hashCode(), qrIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                qrActions[i] = new NotificationCompat.Action.Builder(
                    R.drawable.ic_send, quickReplies[i], qrPi).build();
            }

            // Feature 3: Manual message (RemoteInput)
            RemoteInput remoteInput = new RemoteInput.Builder(Constants.KEY_MISSED_CALL_REPLY)
                .setLabel("Write a message\u2026").build();
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

            // Build notification
            // Collapsed view shows first 3 actions only:
            //   Video call: [Video][Voice][10 min]  then Message + chips in expanded
            //   Voice call: [Voice][Message][10 min] then chips in expanded
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
            // Feature 2: Quick reply chips (expanded only — 4th+ actions)
            for (NotificationCompat.Action qra : qrActions) b.addAction(qra);

            nm.notify(notifId, b.build());

            // Feature 5: Group summary (2+ missed calls)
            if (missedCount > 1) {
                android.app.Notification summary = new NotificationCompat.Builder(this, Constants.CHANNEL_CALLS_MISSED)
                    .setSmallIcon(callIcon)
                    .setContentTitle("Missed calls")
                    .setContentText(missedCount + " missed calls from " + callerName)
                    .setGroup(Constants.GROUP_KEY_MISSED_CALLS)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
                nm.notify(("summary_missed_" + callerUid).hashCode() & 0x7FFFFFFF, summary);
            }

            // Feature 6 + avatar: async update with last seen + photo
            new Thread(() -> {
                Bitmap avatarBm = null;
                if (!callerPhoto.isEmpty()) {
                    try {
                        java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(callerPhoto).openConnection();
                        c.setDoInput(true); c.connect();
                        avatarBm = BitmapFactory.decodeStream(c.getInputStream());
                    } catch (Exception ignored2) {}
                }
                final String[] lastSeenHolder = {null};
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                if (!callerUid.isEmpty()) {
                    com.callx.app.utils.FirebaseUtils.getUserRef(callerUid)
                        .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                            @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                                try {
                                    Object online = snap.child("online").getValue();
                                    Object ls = snap.child("lastSeen").getValue();
                                    if (Boolean.TRUE.equals(online)) {
                                        lastSeenHolder[0] = "Online now";
                                    } else if (ls instanceof Long) {
                                        long diff = System.currentTimeMillis() - (Long) ls;
                                        long mins = diff / 60000;
                                        if (mins < 1) lastSeenHolder[0] = "Last seen just now";
                                        else if (mins < 60) lastSeenHolder[0] = "Last seen " + mins + " min ago";
                                        else {
                                            long hrs = mins / 60;
                                            lastSeenHolder[0] = hrs < 24
                                                ? "Last seen " + hrs + "h ago"
                                                : "Last seen yesterday";
                                        }
                                    }
                                } catch (Exception ignored3) {}
                                latch.countDown();
                            }
                            @Override public void onCancelled(com.google.firebase.database.DatabaseError e) { latch.countDown(); }
                        });
                    try { latch.await(4, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored4) {}
                }
                if (avatarBm != null) b.setLargeIcon(avatarBm);
                if (lastSeenHolder[0] != null) b.setSubText(lastSeenHolder[0]);
                if (avatarBm != null || lastSeenHolder[0] != null) nm.notify(notifId, b.build());
            }).start();

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
