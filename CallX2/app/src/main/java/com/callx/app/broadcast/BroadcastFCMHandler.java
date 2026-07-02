package com.callx.app.broadcast;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.callx.app.R;

import java.util.Map;

/**
 * BroadcastFCMHandler — handles incoming "broadcast_message" FCM payloads.
 *
 * Payload keys (sent by PushNotify.notifyBroadcastComplete() via the server's
 * POST /notify/broadcast route — see index.js):
 *   type         = "broadcast_message"
 *   list_id      — broadcast list ID
 *   list_name    — broadcast list name
 *   delivered    — recipients successfully delivered to
 *   total        — total recipient count
 *   skipped      — recipients skipped (e.g. they blocked the sender)
 *   status       — "sent" | "failed"
 *   msg_type     — text | image | video | audio | file
 *   last_message — preview text of the broadcast message
 *
 * Called from CallxMessagingService.onMessageReceived() when
 *   data.get("type").equals("broadcast_message")
 *
 * This is the SENDER-side, background/killed-safe delivery confirmation.
 * BroadcastDeliveryWorker already shows an identical notification directly
 * (zero network round-trip) on the device that actually ran the fan-out —
 * this FCM path exists so the sender's OTHER signed-in devices also learn
 * that delivery finished, even if that device was backgrounded or killed.
 *
 * NOTE: Recipients of the broadcast itself never see this notification —
 * they receive the message as a normal 1-on-1 chat push (PushNotify.notifyMessage
 * → type="text"/"image"/etc → CallxMessagingService.showMessage()), same as
 * any other chat message, so they can't tell it came from a broadcast list
 * (same privacy model as WhatsApp Broadcast).
 */
public class BroadcastFCMHandler {

    private static final String CHANNEL_ID   = "callx_broadcast";
    private static final String CHANNEL_NAME = "Broadcast Messages";
    private static final int    NOTIF_BASE   = 90000; // offset to avoid conflicts

    public static void handle(Context ctx, Map<String, String> data) {
        String listId   = data.getOrDefault("list_id",   "");
        String listName = data.getOrDefault("list_name", "Broadcast");
        String status    = data.getOrDefault("status",   "sent");
        int delivered    = parseIntSafe(data.get("delivered"));
        int total        = parseIntSafe(data.get("total"));
        int skipped      = parseIntSafe(data.get("skipped"));

        boolean failed = "failed".equals(status);

        String title = failed ? "⚠️ Broadcast Failed" : "📢 Broadcast Sent";
        String body;
        if (failed) {
            body = "\"" + listName + "\" ko message bhejna fail ho gaya. Retry karne ke liye tap karo.";
        } else {
            body = listName + " — " + delivered + "/" + total + " ko delivered";
            if (skipped > 0) body += " • " + skipped + " skipped";
        }

        // Tap intent — open the broadcast list's chat screen directly.
        Intent tapIntent = new Intent(ctx, BroadcastChatActivity.class);
        tapIntent.putExtra(BroadcastListsActivity.EXTRA_LIST_ID,   listId);
        tapIntent.putExtra(BroadcastListsActivity.EXTRA_LIST_NAME, listName);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                ctx, listId.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        ensureChannel(ctx);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_message_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi);

        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_BASE + Math.abs(listId.hashCode() % 1000), builder.build());
        }
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Broadcast list delivery confirmations");
        nm.createNotificationChannel(ch);
    }
}
