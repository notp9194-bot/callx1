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
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import com.callx.app.calls.R;
import com.callx.app.incoming.IncomingCallActivity;
import com.callx.app.utils.Constants;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.RectF;
import android.widget.RemoteViews;

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

    // Brand color: #5B5BF6 (from colors.xml)
    private static final int BRAND_COLOR = 0xFF5B5BF6;

    // ── Colour palette — per call type ────────────────────────────────────
    // Feature 2: setColor() per type — tints small icon + action buttons (API 31+)
    private static final int COLOR_VOICE_MISSED    = 0xFF6C63FF; // purple  — voice
    private static final int COLOR_VIDEO_MISSED    = 0xFFFF6B35; // orange  — video
    private static final int COLOR_MULTI_MISSED    = 0xFFEF4444; // red     — 2+ callers
    // Avatar ring gradient colours (Feature 1)
    private static final int RING_VOICE_START      = 0xFF6C63FF; // purple
    private static final int RING_VOICE_END        = 0xFFA855F7; // violet
    private static final int RING_VIDEO_START      = 0xFFF97316; // orange
    private static final int RING_VIDEO_END        = 0xFFEF4444; // red
    private static final int RING_MULTI_START      = 0xFFEF4444; // red
    private static final int RING_MULTI_END        = 0xFFEC4899; // pink
    private static final int RING_STROKE_DP        = 4;          // ring thickness

    // Custom missed-call vibration: 2 short pulses (distinct from ring)
    private static final long[] MISSED_VIBRATE = { 0, 300, 200, 300 };

    // SharedPrefs key for multi-caller grouping (JSON array of {uid,name,photo,ts})
    private static final String PREF_MISSED_CALLERS = "callx_missed_callers_list";
    // Notification TTL: 30 minutes
    private static final long MISSED_CALL_TTL_MS = 30 * 60 * 1000L;

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

    /** Full missed call notification with all advanced features */
    private void showMissedCallNotification() {
        try {
            final String callerUid   = savedFromUid   != null ? savedFromUid   : "";
            final String callerName  = savedFromName  != null ? savedFromName  : "Unknown";
            final String callerPhoto = savedFromPhoto != null ? savedFromPhoto : "";
            final boolean missedIsVideo = savedIsVideo;

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            // ── Feature: Lock screen privacy ─────────────────────────────────
            boolean appLockOn = false;
            try {
                android.content.SharedPreferences lp = getSharedPreferences("app_lock_prefs", MODE_PRIVATE);
                appLockOn = lp.getBoolean("lock_enabled", false);
            } catch (Exception ignored) {}
            int lockVis = appLockOn
                ? NotificationCompat.VISIBILITY_PRIVATE
                : NotificationCompat.VISIBILITY_PUBLIC;

            // ── Feature: Count per caller per type ───────────────────────────
            String callTypeStr = missedIsVideo ? "video call" : "voice call";
            String callTypeTag = missedIsVideo ? "video" : "voice";

            android.content.SharedPreferences countPrefs = getSharedPreferences("callx_missed_counts", MODE_PRIVATE);
            String countKey = Constants.PREF_MISSED_CALL_COUNT + callTypeTag + "_" + callerUid;
            int missedCount = countPrefs.getInt(countKey, 0) + 1;
            countPrefs.edit().putInt(countKey, missedCount).apply();

            // ── Feature: App icon badge count ────────────────────────────────
            // Total all missed (voice + video) for badge
            int voiceCount = countPrefs.getInt(Constants.PREF_MISSED_CALL_COUNT + "voice_" + callerUid, 0);
            int videoCount = countPrefs.getInt(Constants.PREF_MISSED_CALL_COUNT + "video_" + callerUid, 0);
            int totalBadge = voiceCount + videoCount;
            try {
                // androidx ShortcutBadger alternative — use NotificationManagerCompat badge
                // Works on Samsung, MIUI, OnePlus etc via standard API
                android.app.NotificationManager nmSys = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                // Badge via notification number (works on most launchers)
                // Will be applied to builder below via .setNumber()
            } catch (Exception ignored) {}

            // notifId per type → voice and video = 2 distinct notifications
            int notifId = ("missed_" + callTypeTag + "_" + callerUid).hashCode() & 0x7FFFFFFF;

            // ── Feature: DND check ────────────────────────────────────────────
            // If DND is on, we still show notification but suppress sound/vibration
            boolean isDndActive = false;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    NotificationManager.Policy policy = nm.getNotificationPolicy();
                    int filter = nm.getCurrentInterruptionFilter();
                    isDndActive = (filter == NotificationManager.INTERRUPTION_FILTER_NONE
                            || filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS);
                }
            } catch (Exception ignored) {}

            // ── Feature: Custom vibration (2 short pulses, distinct from ring) ─
            if (!isDndActive) {
                try {
                    Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (vib != null && vib.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vib.vibrate(VibrationEffect.createWaveform(MISSED_VIBRATE, -1));
                        } else {
                            vib.vibrate(MISSED_VIBRATE, -1);
                        }
                    }
                } catch (Exception ignored) {}
            }

            // ── Notification text ─────────────────────────────────────────────
            String missedAt = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(new java.util.Date());

            String notifTitle = missedCount > 1
                ? missedCount + " missed " + callTypeStr + "s from " + callerName
                : (missedIsVideo ? "\uD83D\uDCF9 Missed video call" : "\uD83D\uDCDE Missed voice call") + " from " + callerName;

            String bigText = missedCount > 1
                ? "Last missed \u2022 " + missedAt
                : "Tap to call back \u2022 " + missedAt;

            // ── Tap → ChatActivity (scroll to call history) ──────────────────
            android.content.Intent openIntent = new android.content.Intent();
            openIntent.setClassName(this, "com.callx.app.conversation.ChatActivity");
            openIntent.putExtra(Constants.EXTRA_PARTNER_UID,   callerUid);
            openIntent.putExtra(Constants.EXTRA_PARTNER_NAME,  callerName);
            openIntent.putExtra("partnerPhoto",                 callerPhoto);
            openIntent.putExtra("scrollToCallHistory",         true);  // ChatActivity can use this to scroll
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent openPi = android.app.PendingIntent.getActivity(
                this, notifId, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

            // ── Action: Voice call back ───────────────────────────────────────
            android.content.Intent cbIntent = new android.content.Intent(this, NotificationActionReceiver.class)
                .setAction(Constants.ACTION_CALL_BACK)
                .putExtra(Constants.EXTRA_PARTNER_UID,   callerUid)
                .putExtra(Constants.EXTRA_PARTNER_NAME,  callerName)
                .putExtra(Constants.EXTRA_PARTNER_PHOTO, callerPhoto)
                .putExtra(Constants.EXTRA_IS_VIDEO,      false)
                .putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
            android.app.PendingIntent callBackPi = android.app.PendingIntent.getBroadcast(
                this, ("cb_" + callTypeTag + "_" + callerUid).hashCode(), cbIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

            // ── Action: Video call back (only if original was video) ──────────
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
                    this, ("vcb_" + callTypeTag + "_" + callerUid).hashCode(), vcbIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            }

            // ── Action: Message with quick reply chips ────────────────────────
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
                this, ("mcmsg_" + callTypeTag + "_" + callerUid).hashCode(), msgIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
            NotificationCompat.Action msgAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_send, "\uD83D\uDCAC Message", msgPi)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build();

            // ── Action: Snooze 10 min ─────────────────────────────────────────
            android.content.Intent snoozeIntent = new android.content.Intent(this, NotificationActionReceiver.class)
                .setAction(Constants.ACTION_MISSED_CALL_SNOOZE)
                .putExtra(Constants.EXTRA_PARTNER_UID,   callerUid)
                .putExtra(Constants.EXTRA_PARTNER_NAME,  callerName)
                .putExtra(Constants.EXTRA_PARTNER_PHOTO, callerPhoto)
                .putExtra(Constants.EXTRA_IS_VIDEO,      missedIsVideo)
                .putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
            android.app.PendingIntent snoozePi = android.app.PendingIntent.getBroadcast(
                this, ("snz_" + callTypeTag + "_" + callerUid).hashCode(), snoozeIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Action snoozeAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_timer, "\u23F0 10 min", snoozePi).build();

            int callIcon = missedIsVideo ? R.drawable.ic_video_call : R.drawable.ic_call_notification;

            // ── Feature: heads-up fullScreenIntent (screen-on popup) ──────────
            // Shows as banner even when screen is on and app is in background
            android.content.Intent headsUpIntent = new android.content.Intent();
            headsUpIntent.setClassName(this, "com.callx.app.conversation.ChatActivity");
            headsUpIntent.putExtra(Constants.EXTRA_PARTNER_UID,  callerUid);
            headsUpIntent.putExtra(Constants.EXTRA_PARTNER_NAME, callerName);
            headsUpIntent.putExtra("partnerPhoto",               callerPhoto);
            headsUpIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent headsUpPi = android.app.PendingIntent.getActivity(
                this, ("hu_" + callerUid).hashCode() & 0x7FFFFFFF, headsUpIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

            // ── Feature 2: Per-type accent colour ─────────────────────────────
            int accentColor;
            if (missedCount > 1)       accentColor = COLOR_MULTI_MISSED;
            else if (missedIsVideo)    accentColor = COLOR_VIDEO_MISSED;
            else                       accentColor = COLOR_VOICE_MISSED;

            // NOTE: BigTextStyle intentionally removed — it overrides setCustomBigContentView.
            // Custom RemoteViews handles the expanded layout directly.

            // ── Feature 4: Custom RemoteViews (colorful gradient bg + colored pill buttons) ──
            // Use separate layout for video vs voice (different button sets)
            RemoteViews customView = null;
            try {
                // RemoteViews needs the MODULE's namespace (com.callx.app.calls),
                // not getPackageName() which returns the app's applicationId.
                int layoutRes = missedIsVideo
                    ? R.layout.notification_missed_video
                    : R.layout.notification_missed_call;
                customView = new RemoteViews("com.callx.app.calls", layoutRes);
                customView.setTextViewText(R.id.notif_caller_name, callerName);
                customView.setTextViewText(R.id.notif_call_type,
                    missedIsVideo ? "\uD83D\uDCF9 Missed video call" : "\uD83D\uDCDE Missed voice call");
                customView.setTextViewText(R.id.notif_time, missedAt);
                // Show miss count line if > 1
                if (missedCount > 1) {
                    customView.setViewVisibility(R.id.notif_miss_count, android.view.View.VISIBLE);
                    customView.setTextViewText(R.id.notif_miss_count,
                        missedCount + " missed calls");
                } else {
                    customView.setViewVisibility(R.id.notif_miss_count, android.view.View.GONE);
                }
                // Avatar placeholder (will be updated when bitmap loads)
                customView.setImageViewResource(R.id.notif_avatar,
                    missedIsVideo ? R.drawable.ic_video_orange : R.drawable.ic_phone_green);

                // ── Wire colored pill buttons to PendingIntents ───────────────
                // Voice button (both layouts)
                customView.setOnClickPendingIntent(R.id.notif_btn_voice, callBackPi);
                // Snooze button (both layouts)
                customView.setOnClickPendingIntent(R.id.notif_btn_snooze, snoozePi);
                if (missedIsVideo) {
                    // Video layout: Video + Voice + Snooze
                    if (videoCallBackPi != null) {
                        customView.setOnClickPendingIntent(R.id.notif_btn_video, videoCallBackPi);
                    }
                } else {
                    // Voice layout: Voice + Message + Snooze
                    customView.setOnClickPendingIntent(R.id.notif_btn_message, msgPi);
                }
            } catch (Exception rvEx) {
                customView = null; // fallback to standard style
            }

            // ── Feature 5: Colored action button icons ───────────────────────
            // Voice callback = green phone, video callback = orange camera,
            // message = cyan chat, snooze = yellow clock
            NotificationCompat.Action voiceAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_phone_green, "\uD83D\uDCDE Voice", callBackPi).build();

            // ── Build notification ────────────────────────────────────────────
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, Constants.CHANNEL_CALLS_MISSED)
                .setSmallIcon(callIcon)
                .setColor(accentColor)                   // per-type accent colour
                .setColorized(true)                      // needed for custom bg to show
                .setContentTitle(notifTitle)
                .setContentText(bigText)
                // NO setStyle() — BigTextStyle overrides setCustomBigContentView
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .setFullScreenIntent(headsUpPi, false)
                .setVisibility(lockVis)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setGroup(Constants.GROUP_KEY_MISSED_CALLS)
                .setNumber(totalBadge)
                .setSubText(missedCount > 1 ? missedCount + " missed" : "Just missed");

            // Feature 4: Apply custom RemoteViews if built successfully
            if (customView != null) {
                b.setCustomBigContentView(customView);
            }

            // DND: suppress sound and vibration
            if (isDndActive) b.setSilent(true);

            if (missedIsVideo && videoCallBackPi != null) {
                // Feature 5: orange video icon + green voice icon
                NotificationCompat.Action videoAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_video_orange, "\uD83D\uDCF9 Video", videoCallBackPi).build();
                b.addAction(videoAction);        // 1st
                b.addAction(voiceAction);        // 2nd
                b.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_snooze_yellow, "\u23F0 Snooze", snoozePi).build()); // 3rd
                b.addAction(msgAction);          // chips inside
            } else {
                b.addAction(voiceAction);        // 1st — green phone
                b.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_cyan, "\uD83D\uDCAC Message", msgPi)
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .build());                   // 2nd — cyan message with chips
                b.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_snooze_yellow, "\u23F0 Snooze", snoozePi).build()); // 3rd
            }
            // ── TTL — 30 min baad auto-dismiss (must be BEFORE notify) ─────
            b.setTimeoutAfter(MISSED_CALL_TTL_MS);
            nm.notify(notifId, b.build());

            // ── Feature 1 + 6: Colored ring avatar async load ────────────────
            // Loads caller photo → circle crop → draws gradient ring → re-notify
            final NotificationCompat.Builder finalBuilder = b;
            final int finalNotifId = notifId;
            final RemoteViews finalCustomView = customView;
            final int finalAccentColor = accentColor;
            final boolean finalMissedIsVideo = missedIsVideo;
            final String finalCallerName = callerName;
            if (!callerPhoto.isEmpty()) {
                new Thread(() -> {
                    try {
                        java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(callerPhoto).openConnection();
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(4000);
                        c.setDoInput(true);
                        c.connect();
                        Bitmap raw = BitmapFactory.decodeStream(c.getInputStream());
                        if (raw != null) {
                            // Feature 6: circle crop
                            Bitmap circle = toCircleBitmap(raw);
                            // Feature 1: draw colored gradient ring around avatar
                            int ringStart = finalMissedIsVideo ? RING_VIDEO_START : RING_VOICE_START;
                            int ringEnd   = finalMissedIsVideo ? RING_VIDEO_END   : RING_VOICE_END;
                            Bitmap ringed = drawAvatarWithRing(circle, ringStart, ringEnd);

                            // Update large icon (standard notification)
                            finalBuilder.setLargeIcon(ringed);

                            // Large icon already set above; no style rebuild needed

                            // Feature 4: Update RemoteViews avatar
                            if (finalCustomView != null) {
                                finalCustomView.setImageViewBitmap(R.id.notif_avatar, ringed);
                            }

                            nm.notify(finalNotifId, finalBuilder.build());
                            // Multi-caller summary update
                            updateMultiCallerSummary(nm, callIcon, callerUid, callerName,
                                callerPhoto, missedIsVideo, totalBadge, ringed);
                        }
                    } catch (Exception ignored2) {
                        updateMultiCallerSummary(nm, callIcon, callerUid, callerName,
                            callerPhoto, missedIsVideo, totalBadge, null);
                    }
                }).start();
            } else {
                updateMultiCallerSummary(nm, callIcon, callerUid, callerName,
                    callerPhoto, missedIsVideo, totalBadge, null);
            }

            // ── Feature 2: Firebase call log write ───────────────────────────
            // IncomingCallActivity handles accepted/declined; this covers timeout case
            // where showMissedCallNotification fires from IncomingRingService directly.
            // Also feeds MainActivity's missedCallsListener → nav_calls badge
            try {
                com.google.firebase.auth.FirebaseUser me =
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                if (me != null && !callerUid.isEmpty()) {
                    String myUid = me.getUid();
                    long ts = System.currentTimeMillis();
                    String media = missedIsVideo ? "video" : "audio";
                    java.util.Map<String, Object> myMissed = new java.util.HashMap<>();
                    myMissed.put("partnerUid",  callerUid);
                    myMissed.put("partnerName", callerName);
                    myMissed.put("direction",   "missed");
                    myMissed.put("mediaType",   media);
                    myMissed.put("timestamp",   ts);
                    myMissed.put("duration",    0L);
                    com.callx.app.utils.FirebaseUtils.getCallsRef(myUid).push()
                        .setValue(myMissed);
                }
            } catch (Exception fbIgnored) {}

        } catch (Exception ignored) {}
    }

    // ── Circular bitmap crop utility ─────────────────────────────────────────
    private static Bitmap toCircleBitmap(Bitmap src) {
        try {
            int size = Math.min(src.getWidth(), src.getHeight());
            Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFF000000);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            int dx = (src.getWidth() - size) / 2;
            int dy = (src.getHeight() - size) / 2;
            canvas.drawBitmap(src, -dx, -dy, paint);
            return output;
        } catch (Exception e) {
            return src; // fallback: raw bitmap
        }
    }

    // ── Feature 1: Draw gradient ring around circular avatar ─────────────────
    // ringStart/ringEnd are ARGB colour ints for the sweep gradient
    private static Bitmap drawAvatarWithRing(Bitmap circleBm, int ringStart, int ringEnd) {
        try {
            // Ring stroke in px (approx 4dp at mdpi baseline; good for notification icon)
            int strokePx = Math.max(4, circleBm.getWidth() / 14);
            int total = circleBm.getWidth() + strokePx * 2;
            Bitmap output = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);

            // Draw sweep gradient ring
            Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(strokePx);
            android.graphics.SweepGradient sweep = new android.graphics.SweepGradient(
                total / 2f, total / 2f,
                new int[]{ ringStart, ringEnd, ringStart },
                new float[]{ 0f, 0.5f, 1f });
            ringPaint.setShader(sweep);
            float r = (total - strokePx) / 2f;
            canvas.drawCircle(total / 2f, total / 2f, r, ringPaint);

            // Draw avatar in centre
            Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(circleBm, strokePx, strokePx, bitmapPaint);
            return output;
        } catch (Exception e) {
            return circleBm; // fallback
        }
    }

    // ── Multi-caller grouping: InboxStyle summary notification ───────────────
    // Tracks up to 5 unique callers in SharedPrefs JSON, builds InboxStyle summary.
    private void updateMultiCallerSummary(NotificationManager nm, int callIcon,
            String callerUid, String callerName, String callerPhoto,
            boolean isVideo, int totalBadge, Bitmap callerAvatar) {
        try {
            android.content.SharedPreferences prefs =
                getSharedPreferences("callx_missed_counts", MODE_PRIVATE);

            // ── Load existing callers list ───────────────────────────────────
            String json = prefs.getString(PREF_MISSED_CALLERS, "[]");
            org.json.JSONArray arr;
            try { arr = new org.json.JSONArray(json); }
            catch (Exception e) { arr = new org.json.JSONArray(); }

            // Upsert: caller already in list? bump timestamp. Else prepend.
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.optJSONObject(i);
                if (obj != null && callerUid.equals(obj.optString("uid"))) {
                    obj.put("ts", System.currentTimeMillis());
                    obj.put("name", callerName);
                    arr.put(i, obj);
                    found = true;
                    break;
                }
            }
            if (!found) {
                org.json.JSONObject entry = new org.json.JSONObject();
                entry.put("uid",   callerUid);
                entry.put("name",  callerName);
                entry.put("photo", callerPhoto);
                entry.put("ts",    System.currentTimeMillis());
                // Prepend (most recent first)
                org.json.JSONArray newArr = new org.json.JSONArray();
                newArr.put(entry);
                for (int i = 0; i < arr.length() && i < 4; i++) newArr.put(arr.get(i));
                arr = newArr;
            }
            // Persist updated list
            prefs.edit().putString(PREF_MISSED_CALLERS, arr.toString()).apply();

            int callerCount = arr.length();

            // Only show summary if 2+ distinct callers
            if (callerCount < 2) return;

            // ── Build InboxStyle lines (one per caller) ──────────────────────
            String callTypeStr = isVideo ? "video call" : "voice call";
            String missedAt = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(new java.util.Date());

            NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle();
            int shown = 0;
            for (int i = 0; i < arr.length() && i < 5; i++) {
                org.json.JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String n = obj.optString("name", "Unknown");
                inbox.addLine(n + "  •  missed " + callTypeStr);
                shown++;
            }
            int extra = callerCount - shown;
            if (extra > 0) inbox.setSummaryText("+" + extra + " more");

            // Summary title: "3 people called"
            String summaryTitle = callerCount + " missed calls";
            // List names: "eyeg, rahul +1 more"
            StringBuilder nameList = new StringBuilder();
            for (int i = 0; i < arr.length() && i < 3; i++) {
                org.json.JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                if (nameList.length() > 0) nameList.append(", ");
                nameList.append(obj.optString("name", "?"));
            }
            if (callerCount > 3) nameList.append(" +").append(callerCount - 3).append(" more");

            NotificationCompat.Builder summary = new NotificationCompat.Builder(this, Constants.CHANNEL_CALLS_MISSED)
                .setSmallIcon(callIcon)
                .setColor(BRAND_COLOR)
                .setContentTitle(summaryTitle)
                .setContentText(nameList.toString())
                .setStyle(inbox)
                .setGroup(Constants.GROUP_KEY_MISSED_CALLS)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setNumber(totalBadge)
                .setTimeoutAfter(MISSED_CALL_TTL_MS)
                .setPriority(NotificationCompat.PRIORITY_LOW);

            if (callerAvatar != null) summary.setLargeIcon(callerAvatar);

            nm.notify(Constants.MISSED_CALLS_SUMMARY_NOTIF_ID, summary.build());
        } catch (Exception e) {
            android.util.Log.w("IncomingRingService", "updateMultiCallerSummary failed", e);
        }
    }

    // ── Clear multi-caller list (call when user opens Calls tab / clears notifs) ──
    public static void clearMissedCallersList(android.content.Context ctx) {
        ctx.getSharedPreferences("callx_missed_counts", android.content.Context.MODE_PRIVATE)
            .edit().remove(PREF_MISSED_CALLERS).apply();
    }

    // ── Feature 7: Call-back result — re-show notification if call fails ─────
    // Called from NotificationActionReceiver after call attempt (pass notifId + callerUid etc.)
    public static void onCallBackFailed(Context ctx, int notifId, String callerName,
                                        String callTypeStr, String missedAt) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            int callIcon = callTypeStr.contains("video")
                ? R.drawable.ic_video_call : R.drawable.ic_call_notification;
            android.app.Notification failNotif = new NotificationCompat.Builder(ctx, Constants.CHANNEL_CALLS_MISSED)
                .setSmallIcon(callIcon)
                .setColor(0xFF5B5BF6)
                .setContentTitle("Call failed \u26A0\uFE0F")
                .setContentText("Couldn't reach " + callerName + " \u2022 " + missedAt)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build();
            nm.notify(notifId, failNotif);
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
            .setColor(BRAND_COLOR)
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
