package com.callx.app.youtube.core.utils;

/** Shared constants across all yt-* modules */
public final class YTConstants {
    private YTConstants() {}

    // Intent extras
    public static final String EXTRA_VIDEO_ID    = "video_id";
    public static final String EXTRA_CHANNEL_UID = "channel_uid";
    public static final String EXTRA_OPEN_COMMENTS = "open_comments";
    public static final String EXTRA_SHORTS_POS    = "shorts_start_position";

    // Firebase root
    public static final String YT_ROOT = "youtube";

    // Notification channels
    public static final String NOTIF_CH_SUBSCRIPTIONS = "yt_subscriptions";
    public static final String NOTIF_CH_LIKES         = "yt_likes";
    public static final String NOTIF_CH_COMMENTS      = "yt_comments";
    public static final String NOTIF_CH_DOWNLOADS     = "yt_downloads";
}
