package com.callx.app.youtube.core.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * YouTubePrefs — Centralized helper to read all YouTube settings.
 * Har activity/fragment yahan se settings read kare — ek jagah.
 */
public class YouTubePrefs {

    // General prefs
    private final SharedPreferences generalPrefs;
    // Playback prefs
    private final SharedPreferences playbackPrefs;
    // Quality prefs
    private final SharedPreferences qualityPrefs;
    // Data saving prefs
    private final SharedPreferences dataPrefs;
    // Download prefs
    private final SharedPreferences downloadPrefs;

    public YouTubePrefs(Context ctx) {
        generalPrefs  = ctx.getSharedPreferences("yt_general_prefs",  Context.MODE_PRIVATE);
        playbackPrefs = ctx.getSharedPreferences("yt_playback_prefs", Context.MODE_PRIVATE);
        qualityPrefs  = ctx.getSharedPreferences("yt_quality_prefs",  Context.MODE_PRIVATE);
        dataPrefs     = ctx.getSharedPreferences("yt_data_prefs",     Context.MODE_PRIVATE);
        downloadPrefs = ctx.getSharedPreferences("yt_download_prefs", Context.MODE_PRIVATE);
    }

    // ── General ──────────────────────────────────────────────────────────────
    /** 0=System default, 1=Light, 2=Dark */
    public int getThemeMode()          { return generalPrefs.getInt("theme", 0); }
    public boolean isRestrictedMode()  { return generalPrefs.getBoolean("restricted_mode", false); }
    public boolean isRemindBreak()     { return generalPrefs.getBoolean("remind_break", true); }
    public boolean isEarnBadges()      { return generalPrefs.getBoolean("earn_badges", true); }
    /** 0=On, 1=Wi-Fi only, 2=Off */
    public int getPlaybackInFeeds()    { return generalPrefs.getInt("playback_feeds", 0); }

    // ── Playback ─────────────────────────────────────────────────────────────
    public boolean isAutoplay()        { return playbackPrefs.getBoolean("autoplay", false); }
    /** Returns seek duration in ms: 5000, 10000, 15000, 20000, 30000 */
    public long getSeekIncrementMs() {
        int idx = playbackPrefs.getInt("double_tap_seek", 1);
        long[] opts = {5_000L, 10_000L, 15_000L, 20_000L, 30_000L};
        return (idx >= 0 && idx < opts.length) ? opts[idx] : 10_000L;
    }
    public boolean isZoomToFill()      { return playbackPrefs.getBoolean("zoom_fill", true); }
    public boolean isPip()             { return playbackPrefs.getBoolean("pip", false); }

    // ── Video quality ─────────────────────────────────────────────────────────
    /**
     * Returns ExoPlayer max height based on saved quality pref.
     * 0 = Auto (Integer.MAX_VALUE), 1 = Higher (1080), 2 = Data saver (480)
     */
    public int getMobileQualityMaxHeight() {
        int q = qualityPrefs.getInt("mobile_quality", 2); // default: data saver
        if (q == 1) return 1080;
        if (q == 2) return 480;
        return Integer.MAX_VALUE; // auto
    }
    public int getWifiQualityMaxHeight() {
        int q = qualityPrefs.getInt("wifi_quality", 2); // default: data saver
        if (q == 1) return 1080;
        if (q == 2) return 480;
        return Integer.MAX_VALUE;
    }

    // ── Data saving ───────────────────────────────────────────────────────────
    public boolean isDataSavingMode()      { return dataPrefs.getBoolean("data_saving_mode",     false); }
    public boolean isReduceVideoQuality()  { return dataPrefs.getBoolean("reduce_video_quality", true); }
    public boolean isWifiOnlyDownloads()   { return dataPrefs.getBoolean("wifi_only_downloads",  false); }

    // ── Downloads ─────────────────────────────────────────────────────────────
    /** 0=Low(144p), 1=Medium(360p), 2=High(720p), 3=Full HD(1080p) */
    public int getDownloadQualityIndex()   { return downloadPrefs.getInt("download_quality", 0); }
    public boolean isDownloadWifiOnly()    { return downloadPrefs.getBoolean("download_wifi_only", false); }
    public boolean isRecommendDownloads()  { return downloadPrefs.getBoolean("recommend_downloads", false); }

    // ── Combined quality logic ────────────────────────────────────────────────
    /**
     * Returns max video height considering both quality pref AND data saving mode.
     * Data saving mode overrides quality — it forces 480p max.
     */
    public int getEffectiveMaxHeight(boolean isOnWifi) {
        if (isDataSavingMode() || isReduceVideoQuality()) return 480;
        return isOnWifi ? getWifiQualityMaxHeight() : getMobileQualityMaxHeight();
    }
}
