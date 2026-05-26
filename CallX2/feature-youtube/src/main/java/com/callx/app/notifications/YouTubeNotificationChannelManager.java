package com.callx.app.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * YouTubeNotificationChannelManager — v2 (Heads-Up fix)
 *
 * IMPORTANT: Sabhi channels IMPORTANCE_HIGH hain taaki Android system
 * automatically upar se neeche slide karke notification dikhaye
 * (exactly YouTube jaise heads-up notification).
 *
 * Channel IDs mein "_v2" suffix add kiya gaya hai taaki pehle ke
 * IMPORTANCE_DEFAULT channels override ho jaayein — Android ek baar
 * registered channel ki importance ko update nahi karta, isliye
 * naye channel IDs zaroori hain.
 */
public class YouTubeNotificationChannelManager {

    // v2 channel IDs — importance fix ke liye naye IDs
    public static final String CHANNEL_YT_SUBSCRIPTIONS = "yt_subscriptions_v2";
    public static final String CHANNEL_YT_COMMENTS      = "yt_comments_v2";
    public static final String CHANNEL_YT_LIKES         = "yt_likes_v2";
    public static final String CHANNEL_YT_LIVE          = "yt_live_v2";
    public static final String CHANNEL_YT_GENERAL       = "yt_general_v2";

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
            (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // ── Subscriptions — new video upload ─────────────────────────────────
        NotificationChannel subs = new NotificationChannel(
            CHANNEL_YT_SUBSCRIPTIONS,
            "YouTube — Subscriptions",
            NotificationManager.IMPORTANCE_HIGH);
        subs.setDescription("New videos from channels you subscribed to");
        subs.enableVibration(true);
        subs.setShowBadge(true);

        // ── Comments & Replies — IMPORTANCE_HIGH for heads-up ────────────────
        NotificationChannel comments = new NotificationChannel(
            CHANNEL_YT_COMMENTS,
            "YouTube — Comments & Replies",
            NotificationManager.IMPORTANCE_HIGH);   // was IMPORTANCE_DEFAULT
        comments.setDescription("Comments and replies on your videos");
        comments.enableVibration(true);
        comments.setShowBadge(true);

        // ── Likes milestone ───────────────────────────────────────────────────
        NotificationChannel likes = new NotificationChannel(
            CHANNEL_YT_LIKES,
            "YouTube — Likes",
            NotificationManager.IMPORTANCE_DEFAULT);
        likes.setDescription("Like milestones on your videos");
        likes.setShowBadge(true);

        // ── Live streams — IMPORTANCE_HIGH ───────────────────────────────────
        NotificationChannel live = new NotificationChannel(
            CHANNEL_YT_LIVE,
            "YouTube — Live Streams",
            NotificationManager.IMPORTANCE_HIGH);
        live.setDescription("Alerts when channels you follow go live");
        live.enableVibration(true);
        live.setShowBadge(true);

        // ── General (subscribe etc.) — IMPORTANCE_HIGH ───────────────────────
        NotificationChannel general = new NotificationChannel(
            CHANNEL_YT_GENERAL,
            "YouTube — General",
            NotificationManager.IMPORTANCE_HIGH);   // was IMPORTANCE_DEFAULT
        general.setDescription("Subscriber alerts and general YouTube notifications");
        general.enableVibration(true);
        general.setShowBadge(true);

        nm.createNotificationChannel(subs);
        nm.createNotificationChannel(comments);
        nm.createNotificationChannel(likes);
        nm.createNotificationChannel(live);
        nm.createNotificationChannel(general);
    }
}
