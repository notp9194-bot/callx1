package com.callx.app.youtube.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * YouTubeNotificationChannelManager — v3 (Like HUN fix)
 *
 * v3 changes:
 *   - CHANNEL_YT_LIKES: IMPORTANCE_DEFAULT → IMPORTANCE_HIGH (HUN ke liye zaroori)
 *   - Channel ID "yt_likes_v2" → "yt_likes_v3" (Android cached importance force-refresh)
 *
 * Android ek baar registered channel ki importance update nahi karta.
 * Isliye jab bhi importance change karni ho, naya channel ID banana padta hai.
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

        // ── Subscriptions ─────────────────────────────────────────────────────
        NotificationChannel subs = new NotificationChannel(
            CHANNEL_YT_SUBSCRIPTIONS,
            "YouTube — Subscriptions",
            NotificationManager.IMPORTANCE_HIGH);
        subs.setDescription("New videos from channels you subscribed to");
        subs.enableVibration(true);
        subs.setShowBadge(true);

        // ── Comments & Replies ────────────────────────────────────────────────
        NotificationChannel comments = new NotificationChannel(
            CHANNEL_YT_COMMENTS,
            "YouTube — Comments & Replies",
            NotificationManager.IMPORTANCE_HIGH);
        comments.setDescription("Comments and replies on your videos");
        comments.enableVibration(true);
        comments.setShowBadge(true);

        // ── Likes milestone — v3: IMPORTANCE_HIGH ────────────────────────────
        NotificationChannel likes = new NotificationChannel(
            CHANNEL_YT_LIKES,
            "YouTube — Likes",
            NotificationManager.IMPORTANCE_HIGH);   // was IMPORTANCE_DEFAULT → HUN fix
        likes.setDescription("Like milestones on your videos");
        likes.enableVibration(true);
        likes.setShowBadge(true);

        // ── Live streams ──────────────────────────────────────────────────────
        NotificationChannel live = new NotificationChannel(
            CHANNEL_YT_LIVE,
            "YouTube — Live Streams",
            NotificationManager.IMPORTANCE_HIGH);
        live.setDescription("Alerts when channels you follow go live");
        live.enableVibration(true);
        live.setShowBadge(true);

        // ── General ───────────────────────────────────────────────────────────
        NotificationChannel general = new NotificationChannel(
            CHANNEL_YT_GENERAL,
            "YouTube — General",
            NotificationManager.IMPORTANCE_HIGH);
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
