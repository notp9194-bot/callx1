package com.callx.app.voicerooms;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.NotificationCompat;

import java.util.Map;

/**
 * VrPushHandler — "Riya ne room start kiya" push (server: vrNotifyFollowers, data key
 * `vr_notif_type`). CallxMessagingService isi ko delegate karta hai.
 */
public final class VrPushHandler {
    private static final String CHANNEL = "vr_room_live_push";
    private VrPushHandler() {}

    public static void handle(Context ctx, Map<String, String> data) {
        if (!"room_live".equals(data.get("vr_notif_type"))) return;
        String roomId = data.get("roomId");
        if (roomId == null || roomId.isEmpty()) return;
        String host = data.get("hostName");
        String title = data.get("title");
        if (host == null || host.isEmpty()) host = "Someone";

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Live voice rooms",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return;

        Intent open = new Intent(ctx, VoiceRoomActivity.class);
        open.putExtra(VoiceRoomActivity.EXTRA_ID, roomId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, roomId.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(host + " started a voice room \uD83C\uDF99\uFE0F")
                .setContentText(title == null || title.isEmpty() ? "Tap to join" : title)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(ctx).notify(("vr_" + roomId).hashCode(), b.build());
        } catch (SecurityException ignored) { }
    }
}
