package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * XPrefs — Centralized helper to read all X settings.
 * Har activity/fragment yahan se settings read kare — ek jagah.
 * Mirrors YouTubePrefs pattern exactly.
 */
public class XPrefs {

    private final SharedPreferences displayPrefs;
    private final SharedPreferences privacyPrefs;
    private final SharedPreferences notifPrefs;
    private final SharedPreferences dataPrefs;
    private final SharedPreferences accessibilityPrefs;

    public XPrefs(Context ctx) {
        displayPrefs      = ctx.getSharedPreferences("x_display_prefs",       Context.MODE_PRIVATE);
        privacyPrefs      = ctx.getSharedPreferences("x_privacy_prefs",       Context.MODE_PRIVATE);
        notifPrefs        = ctx.getSharedPreferences("x_notif_prefs",         Context.MODE_PRIVATE);
        dataPrefs         = ctx.getSharedPreferences("x_data_prefs",          Context.MODE_PRIVATE);
        accessibilityPrefs= ctx.getSharedPreferences("x_accessibility_prefs", Context.MODE_PRIVATE);
    }

    // ── Display ───────────────────────────────────────────────────────────────
    /** 0=Default, 1=Small, 2=Large, 3=Largest */
    public int getFontSizeIndex()          { return displayPrefs.getInt("font_size", 0); }
    /** 0=Blue, 1=Yellow, 2=Red, 3=Purple, 4=Orange */
    public int getColorThemeIndex()        { return displayPrefs.getInt("color_theme", 0); }
    /** 0=On, 1=Wi-Fi only, 2=Off */
    public int getAutoplayMode()           { return displayPrefs.getInt("autoplay", 0); }
    /** 0=Automatic, 1=High, 2=Medium */
    public int getImageQualityIndex()      { return displayPrefs.getInt("image_quality", 0); }
    public boolean isShowSensitiveMedia()  { return displayPrefs.getBoolean("sensitive_media", false); }

    // ── Privacy ───────────────────────────────────────────────────────────────
    public boolean isProtectedPosts()      { return privacyPrefs.getBoolean("protect_posts",   false); }
    public boolean isFilterDm()            { return privacyPrefs.getBoolean("filter_dm",        true); }
    public boolean isDiscoverableByEmail() { return privacyPrefs.getBoolean("find_by_email",    true); }
    public boolean isDiscoverableByPhone() { return privacyPrefs.getBoolean("find_by_phone",    true); }
    /** 0=Everyone, 1=People you follow, 2=Nobody */
    public int getDmAllowIndex()           { return privacyPrefs.getInt("dm_allow", 0); }
    /** 0=Anyone, 1=People you follow, 2=Nobody */
    public int getPhotoTaggingIndex()      { return privacyPrefs.getInt("photo_tagging", 0); }

    // ── Notifications ─────────────────────────────────────────────────────────
    public boolean isPushEnabled()         { return notifPrefs.getBoolean("notif_push",    true); }
    public boolean isNotifLikes()          { return notifPrefs.getBoolean("notif_likes",   true); }
    public boolean isNotifReposts()        { return notifPrefs.getBoolean("notif_reposts", true); }
    public boolean isNotifFollow()         { return notifPrefs.getBoolean("notif_follow",  true); }
    public boolean isNotifReply()          { return notifPrefs.getBoolean("notif_reply",   true); }
    public boolean isNotifDm()             { return notifPrefs.getBoolean("notif_dm",      true); }
    public boolean isNotifSound()          { return notifPrefs.getBoolean("notif_sound",   true); }
    public boolean isNotifVibrate()        { return notifPrefs.getBoolean("notif_vibrate", true); }

    // ── Data usage ────────────────────────────────────────────────────────────
    public boolean isDataSaver()           { return dataPrefs.getBoolean("data_saver",       false); }
    public boolean isAutoplayOnMobile()    { return dataPrefs.getBoolean("autoplay_mobile",  true); }
    public boolean isHqOnWifiOnly()        { return dataPrefs.getBoolean("hq_wifi_only",     false); }
    /** 0=Automatic, 1=High, 2=Medium, 3=Low */
    public int getUploadQualityIndex()     { return dataPrefs.getInt("upload_quality", 0); }

    // ── Accessibility ─────────────────────────────────────────────────────────
    public boolean isReduceMotion()        { return accessibilityPrefs.getBoolean("reduce_motion",  false); }
    public boolean isHighContrast()        { return accessibilityPrefs.getBoolean("high_contrast",  false); }
    public boolean isAltTextReminder()     { return accessibilityPrefs.getBoolean("alt_text",       false); }
    public boolean isAutoplayGifs()        { return accessibilityPrefs.getBoolean("autoplay_gif",   true); }
}
