package com.callx.app.utils;

public class Constants {
    public static final String DB_URL =
        "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
    public static final String SERVER_URL =
        "https://callx-server.onrender.com";
    public static final String CLOUDINARY_CLOUD_NAME = "dvqqgqdls";

    // ── Notification channels ────────────────────────────────────────────
    public static final String CHANNEL_CALLS          = "callx_calls";
    public static final String CHANNEL_MESSAGES       = "callx_messages";
    public static final String CHANNEL_GROUPS         = "callx_groups";
    public static final String CHANNEL_GROUPS_MUTED   = "callx_groups_muted";
    public static final String CHANNEL_GROUP_MENTION  = "callx_group_mention";
    public static final String CHANNEL_GROUP_PRIORITY = "callx_group_priority";
    public static final String CHANNEL_STATUS         = "callx_status";
    public static final String CHANNEL_REQUESTS       = "callx_requests";
    public static final String CHANNEL_BLOCK          = "callx_block";
    public static final String CHANNEL_MUTED          = "callx_muted";

    // Group call notification channels
    public static final String CHANNEL_GROUP_CALLS_INCOMING = "callx_group_calls_incoming";
    public static final String CHANNEL_GROUP_CALLS_ONGOING  = "callx_group_calls_ongoing";
    public static final String CHANNEL_GROUP_CALLS_MISSED   = "callx_group_calls_missed";

    // Background status service foreground channel
    public static final String CHANNEL_STATUS_BG_SERVICE = "callx_status_bg";

    // ── Notification group keys ──────────────────────────────────────────
    public static final String GROUP_KEY_MESSAGES = "callx_group_messages";
    public static final String GROUP_KEY_GROUPS   = "callx_group_groups";
    public static final String GROUP_KEY_STATUS   = "callx_status_group";

    public static final int HTTP_TIMEOUT_MS = 20000;

    // ── Status TTL ────────────────────────────────────────────────────────
    /** Default status expiry: 24 hours */
    public static final long STATUS_TTL_MS = 24L * 60 * 60 * 1000;

    // ── Status notification IDs ───────────────────────────────────────────
    /** Base offset — actual ID = STATUS_NOTIF_BASE + fromUid.hashCode() & 0x7FFF */
    public static final int STATUS_NOTIF_BASE    = 7000;
    /** Foreground service notification ID for StatusBackgroundService */
    public static final int STATUS_BG_SERVICE_ID = 8001;
    /** Summary grouping notification */
    public static final int STATUS_SUMMARY_ID    = Integer.MAX_VALUE - 1;

    // ── Status FCM payload keys ───────────────────────────────────────────
    public static final String STATUS_FCM_TYPE       = "status";
    public static final String STATUS_FCM_FROM_UID   = "fromUid";
    public static final String STATUS_FCM_FROM_NAME  = "fromName";
    public static final String STATUS_FCM_FROM_PHOTO = "fromPhoto";
    public static final String STATUS_FCM_STATUS_TYPE = "statusType";
    public static final String STATUS_FCM_TEXT       = "text";
    public static final String STATUS_FCM_MEDIA_URL  = "mediaUrl";

    // ── Notification action intents ──────────────────────────────────────
    public static final String ACTION_REPLY            = "com.callx.app.ACTION_REPLY";
    public static final String ACTION_MARK_READ        = "com.callx.app.ACTION_MARK_READ";
    public static final String ACTION_MUTE             = "com.callx.app.ACTION_MUTE";
    public static final String ACTION_BLOCK            = "com.callx.app.ACTION_BLOCK";
    public static final String ACTION_UNBLOCK          = "com.callx.app.ACTION_UNBLOCK";
    public static final String ACTION_PERMA_BLOCK      = "com.callx.app.ACTION_PERMA_BLOCK";
    public static final String ACTION_SPECIAL_UNBLOCK  = "com.callx.app.ACTION_SPECIAL_UNBLOCK";

    // Group chat action intents
    public static final String ACTION_GROUP_REPLY     = "com.callx.app.ACTION_GROUP_REPLY";
    public static final String ACTION_GROUP_MARK_READ = "com.callx.app.ACTION_GROUP_MARK_READ";
    public static final String ACTION_GROUP_MUTE      = "com.callx.app.ACTION_GROUP_MUTE";
    public static final String ACTION_GROUP_UNMUTE    = "com.callx.app.ACTION_GROUP_UNMUTE";
    public static final String ACTION_GROUP_LEAVE     = "com.callx.app.ACTION_GROUP_LEAVE";

    // Group call action intents (broadcast)
    public static final String ACTION_GROUP_DECLINE_CALL = "com.callx.app.ACTION_GROUP_DECLINE_CALL";
    public static final String ACTION_GROUP_END_CALL     = "com.callx.app.ACTION_GROUP_END_CALL";
    public static final String ACTION_GROUP_JOIN_CALL    = "com.callx.app.ACTION_GROUP_JOIN_CALL";

    // ── Extras ───────────────────────────────────────────────────────────
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

    // Group call extras
    public static final String EXTRA_GROUP_CALLER_UID  = "extra_gcall_caller_uid";
    public static final String EXTRA_GROUP_CALLER_NAME = "extra_gcall_caller_name";

    public static final String KEY_TEXT_REPLY       = "key_text_reply";
    public static final String KEY_GROUP_TEXT_REPLY = "key_group_text_reply";

    // ── Online window ────────────────────────────────────────────────────
    public static final long ONLINE_WINDOW_MS = 60_000L;

    // ── 1:1 Call notification IDs ────────────────────────────────────────
    public static final int  CALL_RING_NOTIF_ID    = 1001;
    public static final int  CALL_ONGOING_NOTIF_ID = 9001;

    // ── Group call notification IDs ──────────────────────────────────────
    public static final int  GROUP_CALL_RING_NOTIF_ID    = 1002;
    public static final int  GROUP_CALL_ONGOING_NOTIF_ID = 9002;
    public static final int  GROUP_CALL_MISSED_NOTIF_ID  = 5002;

    // ── 1:1 Call channels ────────────────────────────────────────────────
    public static final String CHANNEL_CALLS_INCOMING = "callx_calls_incoming";

    // ── 1:1 Call actions (broadcast) ─────────────────────────────────────
    public static final String ACTION_ACCEPT_CALL  = "com.callx.app.ACCEPT_CALL";
    public static final String ACTION_DECLINE_CALL = "com.callx.app.DECLINE_CALL";
    public static final String ACTION_END_CALL     = "com.callx.app.END_CALL";

    // ── Call extras ──────────────────────────────────────────────────────
    public static final String EXTRA_CALL_ID       = "callId";
    public static final String EXTRA_IS_VIDEO      = "isVideo";

    // ── Call timeout ─────────────────────────────────────────────────────
    public static final long   CALL_TIMEOUT_MS     = 60_000L;

    // ── WebRTC / Video quality ───────────────────────────────────────────
    public static final int VIDEO_WIDTH_HD   = 1280;
    public static final int VIDEO_HEIGHT_HD  = 720;
    public static final int VIDEO_WIDTH_VGA  = 640;
    public static final int VIDEO_HEIGHT_VGA = 480;
    public static final int VIDEO_FPS        = 30;

    // ── ICE reconnection ─────────────────────────────────────────────────
    public static final long ICE_RECONNECT_TIMEOUT_MS = 15_000L;
    public static final int  ICE_MAX_RESTARTS         = 3;

    // ── TURN credential endpoint ─────────────────────────────────────────
    public static final String TURN_CREDENTIALS_PATH = "/turn/credentials";

    // ── Fallback public STUN ─────────────────────────────────────────────
    public static final String STUN_GOOGLE_1 = "stun:stun.l.google.com:19302";
    public static final String STUN_GOOGLE_2 = "stun:stun1.l.google.com:19302";

    // ── Group message notification FCM payload keys ───────────────────────
    public static final String GROUP_NOTIF_KEY_MENTION  = "isMention";
    public static final String GROUP_NOTIF_KEY_PRIORITY = "isPriority";
    public static final String GROUP_NOTIF_KEY_SENDER   = "senderName";
    public static final String GROUP_NOTIF_KEY_MSG_ID   = "msgId";

    // ── Group call FCM payload keys ───────────────────────────────────────
    public static final String GCALL_FCM_TYPE         = "group_call";
    public static final String GCALL_FCM_CALL_ID      = "gcallId";
    public static final String GCALL_FCM_GROUP_ID     = "gcallGroupId";
    public static final String GCALL_FCM_GROUP_NAME   = "gcallGroupName";
    public static final String GCALL_FCM_GROUP_ICON   = "gcallGroupIcon";
    public static final String GCALL_FCM_CALLER_UID   = "gcallCallerUid";
    public static final String GCALL_FCM_CALLER_NAME  = "gcallCallerName";
    public static final String GCALL_FCM_IS_VIDEO     = "gcallIsVideo";

    // ── Group call settings SharedPreferences ─────────────────────────────
    public static final String GROUP_CALL_SETTINGS_PREFS = "group_call_settings";

    // Audio
    public static final String GCALL_PREF_NOISE_SUPPRESSION = "noise_suppression";
    public static final String GCALL_PREF_ECHO_CANCELLATION = "echo_cancellation";
    public static final String GCALL_PREF_AUTO_GAIN         = "auto_gain";

    // Video
    public static final String GCALL_PREF_VIDEO_QUALITY     = "video_quality";
    public static final String GCALL_PREF_FPS               = "fps";
    public static final String GCALL_PREF_FRONT_CAMERA      = "front_camera";

    // Routing
    public static final String GCALL_PREF_ROUTING           = "routing";
    public static final String GCALL_PREF_AUTO_SPEAKER      = "auto_speaker";

    // Notifications
    public static final String GCALL_PREF_MISSED_NOTIF      = "missed_notif";
    public static final String GCALL_PREF_LOCKSCREEN_NAME   = "lockscreen_name";
    public static final String GCALL_PREF_SILENT_RINGTONE   = "silent_ringtone";

    // Privacy
    public static final String GCALL_PREF_ALLOW_FROM        = "allow_from";
    public static final String GCALL_PREF_SHOW_STATUS       = "show_status";
    public static final String GCALL_PREF_BLOCK_UNKNOWN     = "block_unknown";

    // Advanced
    public static final String GCALL_PREF_MAX_PARTICIPANTS  = "max_participants";
    public static final String GCALL_PREF_DATA_SAVER        = "data_saver";
    public static final String GCALL_PREF_KEEP_SCREEN_ON    = "keep_screen_on";

    // ── DND prefs ─────────────────────────────────────────────────────────
    public static final String DND_PREFS_PREFIX = "group_settings_";

    private Constants() {}
}
