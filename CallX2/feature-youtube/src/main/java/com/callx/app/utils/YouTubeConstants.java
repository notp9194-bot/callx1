package com.callx.app.utils;

/**
 * YouTube feature constants — centralised here so the feature module
 * compiles independently without depending on the core module.
 * If core/Constants.java is available in the build path, use that instead.
 */
public class YouTubeConstants {
    /** Cloudinary unsigned upload preset for video uploads. */
    public static final String CLOUDINARY_PRESET = "callx_unsigned";

    /** Notification channel IDs for YouTube feature. */
    public static final String NOTIF_CHANNEL_YT_NEW_VIDEO  = "yt_new_video";
    public static final String NOTIF_CHANNEL_YT_COMMENT    = "yt_comment";
    public static final String NOTIF_CHANNEL_YT_SUBSCRIBE  = "yt_subscribe";
    public static final String NOTIF_CHANNEL_YT_LIVE       = "yt_live";

    /** Deep link schemes. */
    public static final String DEEP_LINK_VIDEO   = "callx://youtube/video/";
    public static final String DEEP_LINK_CHANNEL = "callx://youtube/channel/";
    public static final String DEEP_LINK_SHORT   = "callx://youtube/short/";
}
