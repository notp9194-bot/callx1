package com.callx.app.notifications;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import com.callx.app.notifications.NotificationChannelHelper;

/**
 * YouTubeNotificationChannelManager — v3 (Like HUN fix)
 *
 * v3 changes:
 *   - CHANNEL_YT_LIKES: IMPORTANCE_DEFAULT → IMPORTANCE_HIGH (HUN ke liye zaroori)
 *   - Channel ID "yt_likes_v2" → "yt_likes_v3" (Android cached importance force-refresh)
 *
 * Android ek baar registered channel ki importance update nahi karta.
 * Isliye jab bhi importance change karni ho, naya channel ID banana padta hai.
 *
 * Registration logic: NotificationChannelHelper (core) ko delegate karta hai —
 * duplicate channel-creation boilerplate yahan se remove kiya gaya hai.
 */
public class YouTubeNotificationChannelManager {

    // v3 channel IDs
    public static final String CHANNEL_YT_SUBSCRIPTIONS = "yt_subscriptions_v2";
    public static final String CHANNEL_YT_COMMENTS      = "yt_comments_v2";
    public static final String CHANNEL_YT_LIKES         = "yt_likes_v3";   // v2→v3: IMPORTANCE_HIGH fix
    public static final String CHANNEL_YT_LIVE          = "yt_live_v2";
    public static final String CHANNEL_YT_GENERAL       = "yt_general_v2";

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
            (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannelHelper.registerChannel(nm,
            CHANNEL_YT_SUBSCRIPTIONS,
            "YouTube — Subscriptions",
            "New videos from channels you subscribed to",
            NotificationManager.IMPORTANCE_HIGH,
            0xFFFF0000);

        NotificationChannelHelper.registerChannel(nm,
            CHANNEL_YT_COMMENTS,
            "YouTube — Comments & Replies",
            "Comments and replies on your videos",
            NotificationManager.IMPORTANCE_HIGH,
            0xFFFF0000);

        // v3: IMPORTANCE_HIGH fix (was IMPORTANCE_DEFAULT — HUN nahi ata tha)
        NotificationChannelHelper.registerChannel(nm,
            CHANNEL_YT_LIKES,
            "YouTube — Likes",
            "Like milestones on your videos",
            NotificationManager.IMPORTANCE_HIGH,
            0xFFFF0000);

        NotificationChannelHelper.registerChannel(nm,
            CHANNEL_YT_LIVE,
            "YouTube — Live Streams",
            "Alerts when channels you follow go live",
            NotificationManager.IMPORTANCE_HIGH,
            0xFFFF0000);

        NotificationChannelHelper.registerChannel(nm,
            CHANNEL_YT_GENERAL,
            "YouTube — General",
            "Subscriber alerts and general YouTube notifications",
            NotificationManager.IMPORTANCE_HIGH,
            0xFFFF0000);
    }
}
