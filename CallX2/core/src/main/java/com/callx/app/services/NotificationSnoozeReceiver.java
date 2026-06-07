package com.callx.app.services;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.callx.app.core.R;
import com.callx.app.utils.Constants;

/**
 * NotificationSnoozeReceiver — Handles chat + missed call notification snooze.
 *
 * Actions:
 *  SNOOZE_1H              — dismiss now, re-show after 1 hour
 *  SNOOZE_8H              — dismiss now, re-show after 8 hours
 *  SNOOZE_24H             — dismiss now, re-show after 24 hours
 *  SNOOZE_FIRE            — fires when snooze window expires (chat)
 *  MISSED_CALL_SNOOZE_FIRE — fires when missed call snooze expires (10 min)
 */
public class NotificationSnoozeReceiver extends BroadcastReceiver {

    public static final String ACTION_SNOOZE_1H              = "com.callx.app.SNOOZE_1H";
    public static final String ACTION_SNOOZE_8H              = "com.callx.app.SNOOZE_8H";
    public static final String ACTION_SNOOZE_24H             = "com.callx.app.SNOOZE_24H";
    public static final String ACTION_SNOOZE_FIRE            = "com.callx.app.SNOOZE_FIRE";
    public static final String ACTION_MISSED_CALL_SNOOZE_FIRE = "com.callx.app.MISSED_CALL_SNOOZE_FIRE";

    public static final String EXTRA_CHAT_ID       = "snooze_chat_id";
    public static final String EXTRA_PARTNER_NAME  = "snooze_partner_name";
    public static final String EXTRA_PARTNER_UID   = "snooze_partner_uid";
    public static final String EXTRA_NOTIF_ID      = "snooze_notif_id";
    public static final String EXTRA_MESSAGE       = "snooze_message";
    public static final String EXTRA_IS_VIDEO      = "snooze_is_video";
    public static final String EXTRA_PARTNER_PHOTO = "snooze_partner_photo";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action  = intent.getAction();
        int    notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0);

        // ── Chat snooze fire ─────────────────────────────────────────────
        if (ACTION_SNOOZE_FIRE.equals(action)) {
            String partnerName = intent.getStringExtra(EXTRA_PARTNER_NAME);
            String chatId      = intent.getStringExtra(EXTRA_CHAT_ID);
            String message     = intent.getStringExtra(EXTRA_MESSAGE);
            reshowChatNotification(context, notifId, partnerName, chatId, message);
            return;
        }

        // ── Missed call snooze fire — reshow full missed call notification ─
        if (ACTION_MISSED_CALL_SNOOZE_FIRE.equals(action)) {
            String callerName  = intent.getStringExtra(EXTRA_PARTNER_NAME);
            String callerUid   = intent.getStringExtra(EXTRA_PARTNER_UID);
            String callerPhoto = intent.getStringExtra(EXTRA_PARTNER_PHOTO);
            boolean isVideo    = intent.getBooleanExtra(EXTRA_IS_VIDEO, false);
            reshowMissedCallNotification(context, notifId, callerName, callerUid,
                callerPhoto != null ? callerPhoto : "", isVideo);
            return;
        }

        // ── Cancel + schedule for 1h/8h/24h ─────────────────────────────
        NotificationManager nm = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifId);

        long delayMs;
        if      (ACTION_SNOOZE_1H.equals(action))  delayMs = 60 * 60 * 1000L;
        else if (ACTION_SNOOZE_8H.equals(action))  delayMs = 8 * 60 * 60 * 1000L;
        else if (ACTION_SNOOZE_24H.equals(action)) delayMs = 24 * 60 * 60 * 1000L;
        else return;

        scheduleSnooze(context, intent, notifId, delayMs);
    }

    private void scheduleSnooze(Context ctx, Intent original, int notifId, long delayMs) {
        Intent fireIntent = new Intent(ctx, NotificationSnoozeReceiver.class)
            .setAction(ACTION_SNOOZE_FIRE)
            .putExtra(EXTRA_NOTIF_ID,     notifId)
            .putExtra(EXTRA_CHAT_ID,      original.getStringExtra(EXTRA_CHAT_ID))
            .putExtra(EXTRA_PARTNER_NAME, original.getStringExtra(EXTRA_PARTNER_NAME))
            .putExtra(EXTRA_PARTNER_UID,  original.getStringExtra(EXTRA_PARTNER_UID))
            .putExtra(EXTRA_MESSAGE,      original.getStringExtra(EXTRA_MESSAGE));

        PendingIntent pi = PendingIntent.getBroadcast(ctx, notifId,
            fireIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long triggerAt = System.currentTimeMillis() + delayMs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    // ── Chat notification reshow ─────────────────────────────────────────
    private void reshowChatNotification(Context ctx, int notifId,
                                         String partnerName, String chatId, String message) {
        if (partnerName == null) partnerName = "Message";
        if (message == null)    message = "You have a snoozed message";

        Intent openIntent = new Intent();
        openIntent.setClassName(ctx, "com.callx.app.conversation.ChatActivity");
        openIntent.putExtra(Constants.EXTRA_CHAT_ID, chatId);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Constants.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("⏰ " + partnerName)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi);

        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifId, b.build());
    }

    // ── Missed call notification reshow (full — with call-back actions) ──
    // NOTE: Uses string-based Intents (setClassName / setAction only) to avoid
    // circular dependency — core cannot reference feature-calls classes directly.
    private static final String NAR_CLASS =
        "com.callx.app.services.NotificationActionReceiver";

    private void reshowMissedCallNotification(Context ctx, int notifId,
                                               String callerName, String callerUid,
                                               String callerPhoto, boolean isVideo) {
        if (callerName == null) callerName = "Unknown";

        // Tap → ChatActivity
        Intent openIntent = new Intent();
        openIntent.setClassName(ctx, "com.callx.app.conversation.ChatActivity");
        openIntent.putExtra(Constants.EXTRA_PARTNER_UID,  callerUid != null ? callerUid : "");
        openIntent.putExtra(Constants.EXTRA_PARTNER_NAME, callerName);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(ctx, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Voice call back — string-based, no class import needed
        Intent cbIntent = new Intent(Constants.ACTION_CALL_BACK);
        cbIntent.setClassName(ctx, NAR_CLASS);
        cbIntent.putExtra(Constants.EXTRA_PARTNER_UID,   callerUid != null ? callerUid : "");
        cbIntent.putExtra(Constants.EXTRA_PARTNER_NAME,  callerName);
        cbIntent.putExtra(Constants.EXTRA_PARTNER_PHOTO, callerPhoto);
        cbIntent.putExtra(Constants.EXTRA_IS_VIDEO,      false);
        cbIntent.putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
        PendingIntent callBackPi = PendingIntent.getBroadcast(ctx,
            ("snz_cb_" + callerUid).hashCode(), cbIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Video call back (only for video)
        PendingIntent videoCallBackPi = null;
        if (isVideo) {
            Intent vcbIntent = new Intent(Constants.ACTION_VIDEO_CALL_BACK);
            vcbIntent.setClassName(ctx, NAR_CLASS);
            vcbIntent.putExtra(Constants.EXTRA_PARTNER_UID,   callerUid != null ? callerUid : "");
            vcbIntent.putExtra(Constants.EXTRA_PARTNER_NAME,  callerName);
            vcbIntent.putExtra(Constants.EXTRA_PARTNER_PHOTO, callerPhoto);
            vcbIntent.putExtra(Constants.EXTRA_IS_VIDEO,      true);
            vcbIntent.putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
            videoCallBackPi = PendingIntent.getBroadcast(ctx,
                ("snz_vcb_" + callerUid).hashCode(), vcbIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        // Message (RemoteInput)
        androidx.core.app.RemoteInput missedReply = new androidx.core.app.RemoteInput.Builder(
                Constants.KEY_MISSED_CALL_REPLY)
            .setLabel("Write a message…")
            .build();
        Intent msgIntent = new Intent(Constants.ACTION_MISSED_CALL_MESSAGE);
        msgIntent.setClassName(ctx, NAR_CLASS);
        msgIntent.putExtra(Constants.EXTRA_PARTNER_UID,   callerUid != null ? callerUid : "");
        msgIntent.putExtra(Constants.EXTRA_PARTNER_NAME,  callerName);
        msgIntent.putExtra(Constants.EXTRA_PARTNER_PHOTO, callerPhoto);
        msgIntent.putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
        PendingIntent msgPi = PendingIntent.getBroadcast(ctx,
            ("snz_msg_" + callerUid).hashCode(), msgIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action msgAction = new NotificationCompat.Action.Builder(
                android.R.drawable.ic_dialog_email, "💬 Message", msgPi)
            .addRemoteInput(missedReply)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build();

        String title    = (isVideo ? "📹 Missed video call" : "📞 Missed call") + " from " + callerName;
        String bigText  = "Missed " + (isVideo ? "video call" : "voice call") + " • reminder";
        int    callIcon = isVideo ? R.drawable.ic_video_call : R.drawable.ic_call_notification;

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx,
                Constants.CHANNEL_CALLS_MISSED)
            .setSmallIcon(callIcon)
            .setContentTitle(title)
            .setContentText(bigText)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setGroup(Constants.GROUP_KEY_MISSED_CALLS)
            .addAction(R.drawable.ic_phone, "📞 Voice", callBackPi)
            .addAction(msgAction);

        if (isVideo && videoCallBackPi != null) {
            b.addAction(R.drawable.ic_video_call, "📹 Video", videoCallBackPi);
        }

        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifId, b.build());
    }

    /** Build Snooze action PendingIntents for use in chat notification builders. */
    public static PendingIntent snoozePi(Context ctx, String action, int notifId,
                                          String chatId, String partnerName,
                                          String partnerUid, String message) {
        Intent i = new Intent(ctx, NotificationSnoozeReceiver.class)
            .setAction(action)
            .putExtra(EXTRA_NOTIF_ID,     notifId)
            .putExtra(EXTRA_CHAT_ID,      chatId)
            .putExtra(EXTRA_PARTNER_NAME, partnerName)
            .putExtra(EXTRA_PARTNER_UID,  partnerUid)
            .putExtra(EXTRA_MESSAGE,      message);
        return PendingIntent.getBroadcast(ctx, (action + notifId).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Build missed call snooze fire PendingIntent — for use in missed call snooze scheduling. */
    public static PendingIntent missedCallSnoozePi(Context ctx, int notifId,
                                                    String callerUid, String callerName,
                                                    String callerPhoto, boolean isVideo) {
        Intent i = new Intent(ctx, NotificationSnoozeReceiver.class)
            .setAction(ACTION_MISSED_CALL_SNOOZE_FIRE)
            .putExtra(EXTRA_NOTIF_ID,      notifId)
            .putExtra(EXTRA_PARTNER_UID,   callerUid != null ? callerUid : "")
            .putExtra(EXTRA_PARTNER_NAME,  callerName != null ? callerName : "")
            .putExtra(EXTRA_PARTNER_PHOTO, callerPhoto != null ? callerPhoto : "")
            .putExtra(EXTRA_IS_VIDEO,      isVideo);
        return PendingIntent.getBroadcast(ctx, ("snz_mc_" + callerUid).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
