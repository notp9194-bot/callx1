package com.callx.app.notifications;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import com.callx.app.notifications.NotificationChannelHelper;

/**
 * XNotificationChannelManager — X feature ke liye sabhi notification channels create karta hai.
 *
 * Registration logic: NotificationChannelHelper (core) ko delegate karta hai —
 * duplicate channel-creation boilerplate yahan se remove kiya gaya hai.
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

        NotificationChannelHelper.registerChannel(nm,
            CHANNEL_X_GENERAL,
            "X — General",
            "Poll results, list additions aur baaki X activity",
            NotificationManager.IMPORTANCE_DEFAULT,
            0xFF1DA1F2);

        NotificationChannelHelper.registerChannel(nm,
            CHANNEL_X_MENTIONS,
            "X — Mentions & Interactions",
            "Likes, reposts, replies, mentions, quotes, follows, Spaces",
            NotificationManager.IMPORTANCE_HIGH,
            0xFF1DA1F2);

        NotificationChannelHelper.registerChannel(nm,
            CHANNEL_X_DM,
            "X — Direct Messages",
            "X feature ke private messages",
            NotificationManager.IMPORTANCE_HIGH,
            0xFF1DA1F2);
    }
}
