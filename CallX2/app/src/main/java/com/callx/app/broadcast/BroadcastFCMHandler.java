package com.callx.app.broadcast;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.callx.app.R;
import com.callx.app.activities.MainActivity;

import java.util.Map;

/**
 * BroadcastFCMHandler — handles incoming "broadcast_message" FCM payloads.
 *
 * Payload keys:
 *   type           = "broadcast_message"
 *   sender_uid     — sender UID
 *   sender_name    — sender display name
 *   list_id        — broadcast list ID
 *   list_name      — broadcast list name
 *   text           — message text
 *   msg_type       — text | image | video | audio | file
 *   chat_id        — personal chatId to open on tap
 *
 * Called from CallxMessagingService.onMessageReceived() when
 *   data.get("type").equals("broadcast_message")
 *
 * NOTE: Recipients see this as a normal message in their personal chat.
 * The broadcast_message type is only used for the sender's own confirmation push.
 */
public class BroadcastFCMHandler {

    private static final String CHANNEL_ID   = "callx_broadcast";
    private static final String CHANNEL_NAME = "Broadcast Messages";
    private static final int    NOTIF_BASE   = 90000; // offset to avoid conflicts

    public static void handle(Context ctx, Map<String, String> data) {
        String senderName = data.getOrDefault("sender_name", "User");
        String listName   = data.getOrDefault("list_name",   "Broadcast");
        String text       = data.getOrDefault("text",        "");
        String msgType    = data.getOrDefault("msg_type",    "text");
        String senderUid  = data.getOrDefault("sender_uid",  "");
        String chatId     = data.getOrDefault("chat_id",     "");

        // Build preview text
        String preview;
        if ("text".equals(msgType) && !text.isEmpty()) {
            preview = text;
        } else {
            switch (msgType) {
                case "image": preview = "📷 Photo"; break;
                case "video": preview = "🎥 Video"; break;
                case "audio": preview = "🎤 Voice Message"; break;
                case "file":  preview = "📄 Document"; break;
                default:      preview = "📢 Broadcast Message"; break;
            }
        }

        // Tap intent — open ChatActivity with the sender
        Intent tapIntent = new Intent(ctx, MainActivity.class);
        tapIntent.putExtra("open_tab",   "chats");
        tapIntent.putExtra("open_uid",   senderUid);
        tapIntent.putExtra("open_chat_id", chatId);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                ctx, senderUid.hashCode(), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        ensureChannel(ctx);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_message_notification)
                .setContentTitle("📢 " + senderName)
                .setContentText(preview)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(preview)
                        .setSummaryText(listName))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi);

        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_BASE + Math.abs(senderUid.hashCode() % 1000), builder.build());
        }
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Messages received from broadcast lists");
        nm.createNotificationChannel(ch);
    }
}
