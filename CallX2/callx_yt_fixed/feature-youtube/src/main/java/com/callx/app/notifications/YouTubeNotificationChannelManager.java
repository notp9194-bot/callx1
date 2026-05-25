package com.callx.app.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/** Creates all notification channels for the YouTube module. */
public class YouTubeNotificationChannelManager {

    public static final String CHANNEL_YT_SUBSCRIPTIONS = "yt_subscriptions";
    public static final String CHANNEL_YT_COMMENTS      = "yt_comments";
    public static final String CHANNEL_YT_LIKES         = "yt_likes";
    public static final String CHANNEL_YT_LIVE          = "yt_live";
    public static final String CHANNEL_YT_GENERAL       = "yt_general";

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
            (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel subs = new NotificationChannel(
            CHANNEL_YT_SUBSCRIPTIONS, "YouTube — Subscriptions",
            NotificationManager.IMPORTANCE_HIGH);
        subs.setDescription("New videos from channels you subscribed");

        NotificationChannel comments = new NotificationChannel(
            CHANNEL_YT_COMMENTS, "YouTube — Comments & Replies",
            NotificationManager.IMPORTANCE_DEFAULT);

        NotificationChannel likes = new NotificationChannel(
            CHANNEL_YT_LIKES, "YouTube — Likes",
            NotificationManager.IMPORTANCE_LOW);

        NotificationChannel live = new NotificationChannel(
            CHANNEL_YT_LIVE, "YouTube — Live Streams",
            NotificationManager.IMPORTANCE_HIGH);
        live.setDescription("Alerts when channels go live");

        NotificationChannel general = new NotificationChannel(
            CHANNEL_YT_GENERAL, "YouTube — General",
            NotificationManager.IMPORTANCE_DEFAULT);

        nm.createNotificationChannel(subs);
        nm.createNotificationChannel(comments);
        nm.createNotificationChannel(likes);
        nm.createNotificationChannel(live);
        nm.createNotificationChannel(general);
    }
}
