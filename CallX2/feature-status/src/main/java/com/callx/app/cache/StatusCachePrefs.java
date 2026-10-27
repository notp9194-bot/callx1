package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;

/** User / bookkeeping prefs for the status media cache. */
public final class StatusCachePrefs {
    private StatusCachePrefs() {}
    private static final String PREFS = "status_cache_prefs";
    private static final String KEY_WIFI_AUTO = "auto_download_wifi";
    private static final String KEY_LAST_UID  = "last_uid";

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    /** WhatsApp-style "auto-download on Wi-Fi" — default ON. Off → only the 2MB warm-up, no background worker. */
    public static boolean isAutoDownloadOnWifi(Context c) { return p(c).getBoolean(KEY_WIFI_AUTO, true); }
    public static void setAutoDownloadOnWifi(Context c, boolean on) { p(c).edit().putBoolean(KEY_WIFI_AUTO, on).apply(); }
    static String getLastUid(Context c) { return p(c).getString(KEY_LAST_UID, ""); }
    static void setLastUid(Context c, String uid) { p(c).edit().putString(KEY_LAST_UID, uid == null ? "" : uid).apply(); }
}
