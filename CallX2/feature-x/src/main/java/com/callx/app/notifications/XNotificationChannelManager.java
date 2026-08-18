package com.callx.app.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * X feature ke liye sabhi notification channels create karta hai.
 *
 * Channels:
 *   CHANNEL_X_GENERAL  — poll ended, list added (default priority)
 *   CHANNEL_X_MENTIONS — likes, reposts, replies, mentions, quotes, follow, spaces (high)
 *   CHANNEL_X_DM       — direct messages (high)
 */
public class XNotificationChannelManager {

    public static final String CHANNEL_X_GENERAL  = "x_general";
    public static final String CHANNEL_X_MENTIONS = "x_mentions";
    public static final String CHANNEL_X_DM       = "x_dm";

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel general = new NotificationChannel(
                CHANNEL_X_GENERAL,
                "X — General",
                NotificationManager.IMPORTANCE_DEFAULT);
        general.setDescription("Poll results, list additions aur baaki X activity");
        nm.createNotificationChannel(general);

        NotificationChannel mentions = new NotificationChannel(
                CHANNEL_X_MENTIONS,
                "X — Mentions & Interactions",
                NotificationManager.IMPORTANCE_HIGH);
        mentions.setDescription("Likes, reposts, replies, mentions, quotes, follows, Spaces");
        nm.createNotificationChannel(mentions);

        NotificationChannel dm = new NotificationChannel(
                CHANNEL_X_DM,
                "X — Direct Messages",
                NotificationManager.IMPORTANCE_HIGH);
        dm.setDescription("X feature ke private messages");
        nm.createNotificationChannel(dm);
    }
}
