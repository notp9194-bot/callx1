package com.callx.app.utils;

public class Constants {

    // ── Firebase / Server ────────────────────────────────────────────────────
    public static final String DB_URL        = "https://callx-default-rtdb.firebaseio.com/";
    public static final String SERVER_URL    = "https://callx-server.onrender.com";
    public static final String TENOR_API_KEY = "YOUR_TENOR_API_KEY_HERE";
    public static final String MAPS_API_KEY  = "YOUR_GOOGLE_MAPS_API_KEY_HERE";
    public static final String CLOUDINARY_CLOUD_NAME = "YOUR_CLOUDINARY_CLOUD_NAME";
    public static final String TURN_CREDENTIALS_PATH = "/turn-credentials";

    // ── WebRTC / ICE ─────────────────────────────────────────────────────────
    public static final String STUN_GOOGLE_1           = "stun:stun.l.google.com:19302";
    public static final String STUN_GOOGLE_2           = "stun:stun1.l.google.com:19302";
    public static final int    ICE_MAX_RESTARTS         = 3;
    public static final long   ICE_RECONNECT_TIMEOUT_MS = 5_000L;

    // ── Video quality ────────────────────────────────────────────────────────
    public static final int VIDEO_WIDTH_HD  = 1280;
    public static final int VIDEO_HEIGHT_HD =  720;
    public static final int VIDEO_WIDTH_VGA =  640;
    public static final int VIDEO_HEIGHT_VGA=  480;
    public static final int VIDEO_FPS       =   30;

    // ── Timing ───────────────────────────────────────────────────────────────
    public static final long CALL_TIMEOUT_MS    = 60_000L;
    public static final long ONLINE_WINDOW_MS   =  5 * 60 * 1000L;
    public static final long STATUS_TTL_MS      = 24 * 60 * 60 * 1000L;

    // ── Notification channel IDs ─────────────────────────────────────────────
    public static final String CHANNEL_MESSAGES          = "ch_messages";
    public static final String CHANNEL_MUTED             = "ch_muted";
    public static final String CHANNEL_CALLS_INCOMING    = "ch_calls_incoming";
    public static final String CHANNEL_CALLS             = "ch_calls";
    public static final String CHANNEL_REQUESTS          = "ch_requests";
    public static final String CHANNEL_STATUS            = "ch_status";
    public static final String CHANNEL_BLOCK             = "ch_block";
    public static final String CHANNEL_GROUPS            = "ch_groups";
    public static final String CHANNEL_GROUPS_MUTED      = "ch_groups_muted";
    public static final String CHANNEL_GROUP_MENTION     = "ch_group_mention";
    public static final String CHANNEL_GROUP_PRIORITY    = "ch_group_priority";
    public static final String CHANNEL_GROUP_CALLS_INCOMING = "ch_group_calls_incoming";
    public static final String CHANNEL_GROUP_CALLS_ONGOING  = "ch_group_calls_ongoing";
    public static final String CHANNEL_GROUP_CALLS_MISSED   = "ch_group_calls_missed";

    // ── Notification IDs ─────────────────────────────────────────────────────
    public static final int CALL_RING_NOTIF_ID          = 1001;
    public static final int CALL_ONGOING_NOTIF_ID       = 1002;
    public static final int GROUP_CALL_RING_NOTIF_ID    = 1003;
    public static final int GROUP_CALL_ONGOING_NOTIF_ID = 1004;
    public static final int GROUP_CALL_MISSED_NOTIF_ID  = 1005;

    // ── Notification group keys ───────────────────────────────────────────────
    public static final String GROUP_KEY_MESSAGES = "grp_messages";
    public static final String GROUP_KEY_GROUPS   = "grp_groups";

    // ── Intent extra keys ────────────────────────────────────────────────────
    public static final String EXTRA_CALL_ID           = "extra_call_id";
    public static final String EXTRA_CHAT_ID           = "extra_chat_id";
    public static final String EXTRA_PARTNER_UID       = "extra_partner_uid";
    public static final String EXTRA_PARTNER_NAME      = "extra_partner_name";
    public static final String EXTRA_PARTNER_PHOTO     = "extra_partner_photo";
    public static final String EXTRA_IS_VIDEO          = "extra_is_video";
    public static final String EXTRA_NOTIF_ID          = "extra_notif_id";
    public static final String EXTRA_GROUP_ID          = "extra_group_id";
    public static final String EXTRA_GROUP_NAME        = "extra_group_name";
    public static final String EXTRA_GROUP_ICON        = "extra_group_icon";
    public static final String EXTRA_GROUP_CALLER_UID  = "extra_group_caller_uid";
    public static final String EXTRA_GROUP_CALLER_NAME = "extra_group_caller_name";

    // ── RemoteInput keys ─────────────────────────────────────────────────────
    public static final String KEY_TEXT_REPLY       = "key_text_reply";
    public static final String KEY_GROUP_TEXT_REPLY = "key_group_text_reply";

    // ── Broadcast action strings ──────────────────────────────────────────────
    public static final String ACTION_REPLY          = "com.callx.app.ACTION_REPLY";
    public static final String ACTION_MARK_READ      = "com.callx.app.ACTION_MARK_READ";
    public static final String ACTION_MUTE           = "com.callx.app.ACTION_MUTE";
    public static final String ACTION_BLOCK          = "com.callx.app.ACTION_BLOCK";
    public static final String ACTION_UNBLOCK        = "com.callx.app.ACTION_UNBLOCK";
    public static final String ACTION_PERMA_BLOCK    = "com.callx.app.ACTION_PERMA_BLOCK";
    public static final String ACTION_SPECIAL_UNBLOCK= "com.callx.app.ACTION_SPECIAL_UNBLOCK";
    public static final String ACTION_DECLINE_CALL   = "com.callx.app.ACTION_DECLINE_CALL";
    public static final String ACTION_ACCEPT_CALL    = "com.callx.app.ACTION_ACCEPT_CALL";
    public static final String ACTION_END_CALL       = "com.callx.app.ACTION_END_CALL";
    public static final String ACTION_GROUP_REPLY      = "com.callx.app.ACTION_GROUP_REPLY";
    public static final String ACTION_GROUP_MARK_READ  = "com.callx.app.ACTION_GROUP_MARK_READ";
    public static final String ACTION_GROUP_MUTE       = "com.callx.app.ACTION_GROUP_MUTE";
    public static final String ACTION_GROUP_UNMUTE     = "com.callx.app.ACTION_GROUP_UNMUTE";
    public static final String ACTION_GROUP_DECLINE_CALL = "com.callx.app.ACTION_GROUP_DECLINE_CALL";
    public static final String ACTION_GROUP_END_CALL   = "com.callx.app.ACTION_GROUP_END_CALL";
    public static final String ACTION_GROUP_LEAVE      = "com.callx.app.ACTION_GROUP_LEAVE";

    // ── FCM payload keys — group call ─────────────────────────────────────────
    public static final String GCALL_FCM_TYPE        = "group_call";
    public static final String GCALL_FCM_CALL_ID     = "gcall_call_id";
    public static final String GCALL_FCM_GROUP_ID    = "gcall_group_id";
    public static final String GCALL_FCM_GROUP_NAME  = "gcall_group_name";
    public static final String GCALL_FCM_GROUP_ICON  = "gcall_group_icon";
    public static final String GCALL_FCM_CALLER_UID  = "gcall_caller_uid";
    public static final String GCALL_FCM_CALLER_NAME = "gcall_caller_name";
    public static final String GCALL_FCM_IS_VIDEO    = "gcall_is_video";

    // ── Group notification FCM keys ───────────────────────────────────────────
    public static final String GROUP_NOTIF_KEY_MENTION  = "mention";
    public static final String GROUP_NOTIF_KEY_PRIORITY = "priority";
    public static final String GROUP_NOTIF_KEY_SENDER   = "fromUid";

    // ── Group call settings SharedPreferences ─────────────────────────────────
    public static final String GROUP_CALL_SETTINGS_PREFS  = "gcall_settings";
    public static final String GSCALL_PREF_ALLOW_FROM     = "pref_allow_from";

    // ── Group call SharedPreferences keys ────────────────────────────────────
    public static final String GCALL_PREF_ALLOW_FROM       = "pref_allow_from";
    public static final String GCALL_PREF_AUTO_GAIN        = "pref_auto_gain";
    public static final String GCALL_PREF_AUTO_SPEAKER     = "pref_auto_speaker";
    public static final String GCALL_PREF_BLOCK_UNKNOWN    = "pref_block_unknown";
    public static final String GCALL_PREF_DATA_SAVER       = "pref_data_saver";
    public static final String GCALL_PREF_ECHO_CANCELLATION= "pref_echo_cancellation";
    public static final String GCALL_PREF_FPS              = "pref_fps";
    public static final String GCALL_PREF_FRONT_CAMERA     = "pref_front_camera";
    public static final String GCALL_PREF_KEEP_SCREEN_ON   = "pref_keep_screen_on";
    public static final String GCALL_PREF_LOCKSCREEN_NAME  = "pref_lockscreen_name";
    public static final String GCALL_PREF_MAX_PARTICIPANTS  = "pref_max_participants";
    public static final String GCALL_PREF_MISSED_NOTIF     = "pref_missed_notif";
    public static final String GCALL_PREF_NOISE_SUPPRESSION= "pref_noise_suppression";
    public static final String GCALL_PREF_ROUTING          = "pref_routing";
    public static final String GCALL_PREF_SHOW_STATUS      = "pref_show_status";
    public static final String GCALL_PREF_SILENT_RINGTONE  = "pref_silent_ringtone";
    public static final String GCALL_PREF_VIDEO_QUALITY    = "pref_video_quality";

    // ── DND SharedPreferences prefix ─────────────────────────────────────────
    public static final String DND_PREFS_PREFIX = "dnd_group_";
}
