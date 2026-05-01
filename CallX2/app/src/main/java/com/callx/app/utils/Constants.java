package com.callx.app.utils;
public class Constants {
    public static final String DB_URL =
        "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
    public static final String SERVER_URL =
        "https://callx-server.onrender.com";
    public static final String CLOUDINARY_CLOUD_NAME = "dvqqgqdls";

    // Notification channels
    public static final String CHANNEL_CALLS          = "callx_calls";
    public static final String CHANNEL_MESSAGES       = "callx_messages";
    public static final String CHANNEL_GROUPS         = "callx_groups";
    public static final String CHANNEL_GROUPS_MUTED   = "callx_groups_muted";
    public static final String CHANNEL_GROUP_MENTION  = "callx_group_mention";   // @mention priority bypass
    public static final String CHANNEL_GROUP_PRIORITY = "callx_group_priority";  // admin-flagged priority
    public static final String CHANNEL_STATUS         = "callx_status";
    public static final String CHANNEL_REQUESTS       = "callx_requests";
    public static final String CHANNEL_BLOCK          = "callx_block";
    public static final String CHANNEL_MUTED          = "callx_muted";

    public static final String GROUP_KEY_MESSAGES = "callx_group_messages";
    public static final String GROUP_KEY_GROUPS   = "callx_group_groups";

    public static final int HTTP_TIMEOUT_MS = 20000;
    public static final long STATUS_TTL_MS = 24L * 60 * 60 * 1000;

    // Notification action intents
    public static final String ACTION_REPLY            = "com.callx.app.ACTION_REPLY";
    public static final String ACTION_MARK_READ        = "com.callx.app.ACTION_MARK_READ";
    public static final String ACTION_MUTE             = "com.callx.app.ACTION_MUTE";
    public static final String ACTION_BLOCK            = "com.callx.app.ACTION_BLOCK";
    public static final String ACTION_UNBLOCK          = "com.callx.app.ACTION_UNBLOCK";
    public static final String ACTION_PERMA_BLOCK      = "com.callx.app.ACTION_PERMA_BLOCK";
    public static final String ACTION_SPECIAL_UNBLOCK  = "com.callx.app.ACTION_SPECIAL_UNBLOCK";

    // Group action intents
    public static final String ACTION_GROUP_REPLY     = "com.callx.app.ACTION_GROUP_REPLY";
    public static final String ACTION_GROUP_MARK_READ = "com.callx.app.ACTION_GROUP_MARK_READ";
    public static final String ACTION_GROUP_MUTE      = "com.callx.app.ACTION_GROUP_MUTE";
    public static final String ACTION_GROUP_UNMUTE    = "com.callx.app.ACTION_GROUP_UNMUTE";
    public static final String ACTION_GROUP_LEAVE     = "com.callx.app.ACTION_GROUP_LEAVE";

    public static final String EXTRA_CHAT_ID       = "extra_chat_id";
    public static final String EXTRA_PARTNER_UID   = "extra_partner_uid";
    public static final String EXTRA_PARTNER_NAME  = "extra_partner_name";
    public static final String EXTRA_PARTNER_PHOTO = "extra_partner_photo";
    public static final String EXTRA_GROUP_ID      = "extra_group_id";
    public static final String EXTRA_GROUP_NAME    = "extra_group_name";
    public static final String EXTRA_GROUP_ICON    = "extra_group_icon";
    public static final String EXTRA_NOTIF_ID      = "extra_notif_id";
    public static final String EXTRA_IS_MENTION    = "extra_is_mention";
    public static final String EXTRA_SENDER_NAME   = "extra_sender_name";

    public static final String KEY_TEXT_REPLY       = "key_text_reply";
    public static final String KEY_GROUP_TEXT_REPLY = "key_group_text_reply";

    // Online window
    public static final long ONLINE_WINDOW_MS = 60_000L;

    // Call notification IDs
    public static final int  CALL_RING_NOTIF_ID    = 1001;
    public static final int  CALL_ONGOING_NOTIF_ID = 9001;

    // Call channels
    public static final String CHANNEL_CALLS_INCOMING = "callx_calls_incoming";

    // Call actions (broadcast)
    public static final String ACTION_ACCEPT_CALL  = "com.callx.app.ACCEPT_CALL";
    public static final String ACTION_DECLINE_CALL = "com.callx.app.DECLINE_CALL";
    public static final String ACTION_END_CALL     = "com.callx.app.END_CALL";

    // Call extras
    public static final String EXTRA_CALL_ID       = "callId";
    public static final String EXTRA_IS_VIDEO      = "isVideo";

    // Call timeout
    public static final long   CALL_TIMEOUT_MS     = 60_000L;

    // Production WebRTC / Video quality
    public static final int VIDEO_WIDTH_HD   = 1280;
    public static final int VIDEO_HEIGHT_HD  = 720;
    public static final int VIDEO_WIDTH_VGA  = 640;
    public static final int VIDEO_HEIGHT_VGA = 480;
    public static final int VIDEO_FPS        = 30;

    // ICE reconnection
    public static final long ICE_RECONNECT_TIMEOUT_MS = 15_000L;
    public static final int  ICE_MAX_RESTARTS         = 3;

    // TURN credential endpoint (GET /turn/credentials)
    public static final String TURN_CREDENTIALS_PATH = "/turn/credentials";

    // Fallback public STUN
    public static final String STUN_GOOGLE_1 = "stun:stun.l.google.com:19302";
    public static final String STUN_GOOGLE_2 = "stun:stun1.l.google.com:19302";

    // Group notification FCM payload keys
    public static final String GROUP_NOTIF_KEY_MENTION  = "isMention";
    public static final String GROUP_NOTIF_KEY_PRIORITY = "isPriority";
    public static final String GROUP_NOTIF_KEY_SENDER   = "senderName";
    public static final String GROUP_NOTIF_KEY_MSG_ID   = "msgId";

    // DND prefs file prefix
    public static final String DND_PREFS_PREFIX = "group_settings_";

    private Constants() {}
}
