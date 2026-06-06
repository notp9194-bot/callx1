package com.callx.app.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

/**
 * ReelNotificationChannelManager — Registers ALL reel notification channels.
 *
 * Call ensureChannels(context) once at app startup and from the FCM service.
 * All channels are idempotent (safe to call multiple times).
 *
 * Channel hierarchy:
 *  HIGH importance  → Likes, Comments, Mentions, Milestones, Collab, Live, Gifts
 *  DEFAULT importance → Follower posts, Upload, Scheduled, Sounds, Challenges
 *  LOW importance   → Recommendations, Digest, Saves/Downloads (background)
 */
public class ReelNotificationChannelManager {

    // ── Channel IDs ────────────────────────────────────────────────────────
    public static final String CHANNEL_REEL_LIKES              = "reel_likes";
    public static final String CHANNEL_REEL_COMMENTS           = "reel_comments";
    public static final String CHANNEL_REEL_COMMENT_LIKES      = "reel_comment_likes";
    public static final String CHANNEL_REEL_COMMENT_REPLIES    = "reel_comment_replies";
    public static final String CHANNEL_REEL_MENTIONS           = "reel_mentions";
    public static final String CHANNEL_REEL_SHARES             = "reel_shares";
    public static final String CHANNEL_REEL_NEW_FOLLOWER        = "reel_new_follower";
    public static final String CHANNEL_REEL_FOLLOWING_POSTED   = "reel_following_posted";
    public static final String CHANNEL_REEL_DUET               = "reel_duet";
    public static final String CHANNEL_REEL_STITCH             = "reel_stitch";
    public static final String CHANNEL_REEL_VIDEO_REPLY        = "reel_video_reply";
    public static final String CHANNEL_REEL_COLLAB_REQUEST     = "reel_collab_request";
    public static final String CHANNEL_REEL_COLLAB_ACCEPTED    = "reel_collab_accepted";
    public static final String CHANNEL_REEL_GIFTING            = "reel_gifting";
    public static final String CHANNEL_REEL_LIVE_STARTED       = "reel_live_started";
    public static final String CHANNEL_REEL_LIVE_MILESTONE     = "reel_live_milestone";
    public static final String CHANNEL_REEL_CLOSE_FRIEND_LIVE  = "reel_close_friend_live";
    public static final String CHANNEL_REEL_TRENDING           = "reel_trending";
    public static final String CHANNEL_REEL_VIRAL              = "reel_viral";
    public static final String CHANNEL_REEL_VIEW_MILESTONE     = "reel_view_milestone";
    public static final String CHANNEL_REEL_FOLLOWER_MILESTONE = "reel_follower_milestone";
    public static final String CHANNEL_REEL_UPLOAD_COMPLETE    = "reel_upload_complete";
    public static final String CHANNEL_REEL_UPLOAD_FAILED      = "reel_upload_failed";
    public static final String CHANNEL_REEL_SCHEDULED_POST     = "reel_scheduled_post";
    public static final String CHANNEL_REEL_SCHEDULED_REMINDER = "reel_scheduled_reminder";
    public static final String CHANNEL_REEL_PRODUCT_TAG        = "reel_product_tag";
    public static final String CHANNEL_REEL_CREATOR_FUND       = "reel_creator_fund";
    public static final String CHANNEL_REEL_MODERATION         = "reel_moderation";
    public static final String CHANNEL_REEL_REPORT_RESOLVED    = "reel_report_resolved";
    public static final String CHANNEL_REEL_SOUND_TRENDING     = "reel_sound_trending";
    public static final String CHANNEL_REEL_PINNED_COMMENT     = "reel_pinned_comment";
    public static final String CHANNEL_REEL_CLOSE_FRIEND_POST  = "reel_close_friend_post";
    public static final String CHANNEL_REEL_CHALLENGE          = "reel_challenge";
    public static final String CHANNEL_REEL_RECOMMENDATION     = "reel_recommendation";
    public static final String CHANNEL_REEL_WEEKLY_DIGEST      = "reel_weekly_digest";
    public static final String CHANNEL_REEL_SAVED              = "reel_saved";
    public static final String CHANNEL_REEL_DOWNLOADED         = "reel_downloaded";
    public static final String CHANNEL_REEL_COLLAB_LIVE        = "reel_collab_live";
    public static final String CHANNEL_REEL_FOREGROUND_SERVICE = "reel_foreground_service";
    // FIX: Repost channel was missing — repost notifications fell back to undefined channel
    public static final String CHANNEL_REEL_REPOSTS            = "reel_reposts";

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // HIGH importance (heads-up, sound, vibrate)
        registerChannel(nm, CHANNEL_REEL_LIKES,            "Reel Likes",            "When someone likes your reel",              NotificationManager.IMPORTANCE_HIGH,  0xFFFF3B5C);
        registerChannel(nm, CHANNEL_REEL_COMMENTS,         "Reel Comments",         "When someone comments on your reel",        NotificationManager.IMPORTANCE_HIGH,  0xFFFF3B5C);
        registerChannel(nm, CHANNEL_REEL_COMMENT_LIKES,    "Comment Likes",         "When someone likes your comment",           NotificationManager.IMPORTANCE_DEFAULT, 0xFFFF6B6B);
        registerChannel(nm, CHANNEL_REEL_COMMENT_REPLIES,  "Comment Replies",       "Replies to your reel comments",             NotificationManager.IMPORTANCE_HIGH,  0xFFFF3B5C);
        registerChannel(nm, CHANNEL_REEL_MENTIONS,         "Reel Mentions",         "When you are @mentioned in a reel",         NotificationManager.IMPORTANCE_HIGH,  0xFFFF3B5C);
        registerChannel(nm, CHANNEL_REEL_SHARES,           "Reel Shares",           "When someone shares your reel",             NotificationManager.IMPORTANCE_DEFAULT, 0xFFAAAAAA);
        registerChannel(nm, CHANNEL_REEL_NEW_FOLLOWER,     "New Followers",         "When someone follows you",                  NotificationManager.IMPORTANCE_HIGH,  0xFF34C759);
        registerChannel(nm, CHANNEL_REEL_FOLLOWING_POSTED, "Following Posted",      "When someone you follow posts a reel",      NotificationManager.IMPORTANCE_DEFAULT, 0xFF5AC8FA);
        registerChannel(nm, CHANNEL_REEL_DUET,             "Duets",                 "When someone creates a duet with your reel",NotificationManager.IMPORTANCE_HIGH,  0xFFFF9500);
        registerChannel(nm, CHANNEL_REEL_STITCH,           "Stitches",              "When someone stitches your reel",           NotificationManager.IMPORTANCE_HIGH,  0xFFFF9500);
        registerChannel(nm, CHANNEL_REEL_VIDEO_REPLY,      "Video Replies",         "When someone replies to your reel with video",NotificationManager.IMPORTANCE_HIGH, 0xFFFF9500);
        registerChannel(nm, CHANNEL_REEL_COLLAB_REQUEST,   "Collab Requests",       "Collaboration invitations",                 NotificationManager.IMPORTANCE_HIGH,  0xFF5856D6);
        registerChannel(nm, CHANNEL_REEL_COLLAB_ACCEPTED,  "Collab Accepted",       "When your collab request is accepted",      NotificationManager.IMPORTANCE_HIGH,  0xFF34C759);
        registerChannel(nm, CHANNEL_REEL_GIFTING,          "Gifts",                 "Gifts received on live or reels",           NotificationManager.IMPORTANCE_HIGH,  0xFFFF2D55);
        registerChannel(nm, CHANNEL_REEL_LIVE_STARTED,     "Live Started",          "When someone you follow goes live",         NotificationManager.IMPORTANCE_HIGH,  0xFFFF3B5C);
        registerChannel(nm, CHANNEL_REEL_LIVE_MILESTONE,   "Live Milestones",       "Viewer milestones during your live",        NotificationManager.IMPORTANCE_DEFAULT, 0xFFFFCC00);
        registerChannel(nm, CHANNEL_REEL_CLOSE_FRIEND_LIVE,"Close Friend Live",     "When a close friend goes live",             NotificationManager.IMPORTANCE_HIGH,  0xFF34C759);
        registerChannel(nm, CHANNEL_REEL_TRENDING,         "Trending Reels",        "When your reel starts trending",            NotificationManager.IMPORTANCE_HIGH,  0xFFFF3B5C);
        registerChannel(nm, CHANNEL_REEL_VIRAL,            "Viral Reels",           "When your reel goes viral",                 NotificationManager.IMPORTANCE_HIGH,  0xFFFF3B5C);
        registerChannel(nm, CHANNEL_REEL_VIEW_MILESTONE,   "View Milestones",       "Reach 1K, 10K, 100K, 1M views",            NotificationManager.IMPORTANCE_HIGH,  0xFFFFD700);
        registerChannel(nm, CHANNEL_REEL_FOLLOWER_MILESTONE,"Follower Milestones",  "Reach 100, 1K, 10K followers",             NotificationManager.IMPORTANCE_HIGH,  0xFFFFD700);
        registerChannel(nm, CHANNEL_REEL_UPLOAD_COMPLETE,  "Upload Complete",       "Your reel finished uploading",              NotificationManager.IMPORTANCE_DEFAULT, 0xFF34C759);
        registerChannel(nm, CHANNEL_REEL_UPLOAD_FAILED,    "Upload Failed",         "Your reel upload encountered an error",     NotificationManager.IMPORTANCE_HIGH,  0xFFFF3B5C);
        registerChannel(nm, CHANNEL_REEL_SCHEDULED_POST,   "Scheduled Post",        "Your scheduled reel was posted",            NotificationManager.IMPORTANCE_DEFAULT, 0xFF5AC8FA);
        registerChannel(nm, CHANNEL_REEL_SCHEDULED_REMINDER,"Scheduled Reminder",   "Reminder before your scheduled post",       NotificationManager.IMPORTANCE_HIGH,  0xFFFFCC00);
        registerChannel(nm, CHANNEL_REEL_PRODUCT_TAG,      "Product Tags",          "Product tag activity on your reels",        NotificationManager.IMPORTANCE_DEFAULT, 0xFF34C759);
        registerChannel(nm, CHANNEL_REEL_CREATOR_FUND,     "Creator Fund",          "Earnings and payout notifications",         NotificationManager.IMPORTANCE_HIGH,  0xFFFFD700);
        registerChannel(nm, CHANNEL_REEL_MODERATION,       "Moderation",            "Content moderation decisions",              NotificationManager.IMPORTANCE_HIGH,  0xFFFF3B5C);
        registerChannel(nm, CHANNEL_REEL_REPORT_RESOLVED,  "Reports",               "Updates on your content reports",           NotificationManager.IMPORTANCE_DEFAULT, 0xFFAAAAAA);
        registerChannel(nm, CHANNEL_REEL_SOUND_TRENDING,   "Sound Trending",        "When a sound you used starts trending",     NotificationManager.IMPORTANCE_DEFAULT, 0xFF5AC8FA);
        registerChannel(nm, CHANNEL_REEL_PINNED_COMMENT,   "Pinned Comment",        "When your comment is pinned by creator",    NotificationManager.IMPORTANCE_DEFAULT, 0xFF5856D6);
        registerChannel(nm, CHANNEL_REEL_CLOSE_FRIEND_POST,"Close Friend Posted",   "When a close friend posts a reel",          NotificationManager.IMPORTANCE_DEFAULT, 0xFF34C759);
        registerChannel(nm, CHANNEL_REEL_CHALLENGE,        "Challenges",            "Challenge invites and updates",             NotificationManager.IMPORTANCE_DEFAULT, 0xFFFF9500);
        registerChannel(nm, CHANNEL_REEL_RECOMMENDATION,   "Recommendations",       "Personalised reel recommendations",         NotificationManager.IMPORTANCE_LOW,   0xFFAAAAAA);
        registerChannel(nm, CHANNEL_REEL_WEEKLY_DIGEST,    "Creator Digest",        "Weekly and monthly creator summaries",      NotificationManager.IMPORTANCE_LOW,   0xFF5AC8FA);
        registerChannel(nm, CHANNEL_REEL_SAVED,            "Reel Saves",            "When someone saves your reel",             NotificationManager.IMPORTANCE_LOW,   0xFFAAAAAA);
        registerChannel(nm, CHANNEL_REEL_DOWNLOADED,       "Reel Downloads",        "When someone downloads your reel",          NotificationManager.IMPORTANCE_LOW,   0xFFAAAAAA);
        registerChannel(nm, CHANNEL_REEL_COLLAB_LIVE,      "Collab Live",           "Multi-creator live session updates",        NotificationManager.IMPORTANCE_HIGH,  0xFF5856D6);
        registerChannel(nm, CHANNEL_REEL_REPOSTS, "Reel Reposts", "When someone reposts your reel", NotificationManager.IMPORTANCE_HIGH, 0xFF34C759);
        registerSilentServiceChannel(nm);
    }

    private static void registerChannel(NotificationManager nm, String id, String name,
                                        String desc, int importance, int lightColor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(id, name, importance);
        ch.setDescription(desc);
        ch.enableLights(true);
        ch.setLightColor(lightColor);
        ch.enableVibration(importance >= NotificationManager.IMPORTANCE_DEFAULT);
        if (importance >= NotificationManager.IMPORTANCE_DEFAULT) {
            ch.setVibrationPattern(new long[]{0, 200, 100, 200});
        }
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    private static void registerSilentServiceChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_REEL_FOREGROUND_SERVICE,
            "Reel Notification Service",
            NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("Background service keeping reel notifications alive");
        ch.setShowBadge(false);
        ch.enableLights(false);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }
}
