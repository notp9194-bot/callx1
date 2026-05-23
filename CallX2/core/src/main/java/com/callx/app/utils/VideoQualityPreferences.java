package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * VideoQualityPreferences — Per-chat + global quality settings.
 *
 * Quality levels:
 *   AUTO      → Smart selection based on file size and network
 *   LOW       → 360p, 400 kbps  (~1 MB/min)
 *   STANDARD  → 540p, 800 kbps  (~2 MB/min)
 *   HD        → 720p, 1.5 Mbps  (~3.5 MB/min)
 *   FULL_HD   → 1080p, 3 Mbps   (~7 MB/min)
 *   ORIGINAL  → No compression
 */
public class VideoQualityPreferences {

    private static final String PREFS_NAME    = "callx_video_quality";
    private static final String KEY_GLOBAL    = "global_quality";
    private static final String KEY_CHAT_PFX  = "chat_quality_";
    private static final String KEY_WIFI_ONLY = "hd_wifi_only";
    private static final String KEY_DATA_SAVE = "data_saver_mode";
    private static final String KEY_STATS_SAVED   = "total_bytes_saved";
    private static final String KEY_STATS_COUNT    = "total_videos_compressed";

    public enum Quality {
        AUTO("Auto", -1, -1),
        LOW("Low (360p)", 360, 400_000),
        STANDARD("Standard (540p)", 540, 800_000),
        HD("HD (720p)", 720, 1_500_000),
        FULL_HD("Full HD (1080p)", 1080, 3_000_000),
        ORIGINAL("Original", Integer.MAX_VALUE, Integer.MAX_VALUE);

        public final String label;
        public final int    maxPx;
        public final int    bitrate;

        Quality(String label, int maxPx, int bitrate) {
            this.label   = label;
            this.maxPx   = maxPx;
            this.bitrate = bitrate;
        }

        public static Quality fromName(String name) {
            try { return Quality.valueOf(name); }
            catch (Exception e) { return AUTO; }
        }
    }

    private final SharedPreferences prefs;

    public VideoQualityPreferences(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public Quality getGlobalQuality() {
        return Quality.fromName(prefs.getString(KEY_GLOBAL, Quality.AUTO.name()));
    }

    public void setGlobalQuality(Quality q) {
        prefs.edit().putString(KEY_GLOBAL, q.name()).apply();
    }

    public Quality getChatQuality(String chatId) {
        String val = prefs.getString(KEY_CHAT_PFX + chatId, null);
        if (val == null) return getGlobalQuality();
        return Quality.fromName(val);
    }

    public void setChatQuality(String chatId, Quality q) {
        prefs.edit().putString(KEY_CHAT_PFX + chatId, q.name()).apply();
    }

    public void clearChatQuality(String chatId) {
        prefs.edit().remove(KEY_CHAT_PFX + chatId).apply();
    }

    public boolean isHdOnWifiOnly() {
        return prefs.getBoolean(KEY_WIFI_ONLY, false);
    }

    public void setHdOnWifiOnly(boolean val) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, val).apply();
    }

    public boolean isDataSaverMode() {
        return prefs.getBoolean(KEY_DATA_SAVE, false);
    }

    public void setDataSaverMode(boolean val) {
        prefs.edit().putBoolean(KEY_DATA_SAVE, val).apply();
    }

    public void recordCompression(long originalBytes, long compressedBytes) {
        long saved  = Math.max(0, originalBytes - compressedBytes);
        long total  = prefs.getLong(KEY_STATS_SAVED, 0) + saved;
        int  count  = prefs.getInt(KEY_STATS_COUNT, 0) + 1;
        prefs.edit().putLong(KEY_STATS_SAVED, total).putInt(KEY_STATS_COUNT, count).apply();
    }

    public long getTotalBytesSaved()    { return prefs.getLong(KEY_STATS_SAVED, 0); }
    public int  getTotalVideosCompressed() { return prefs.getInt(KEY_STATS_COUNT, 0); }

    public String getStatsSummary() {
        long savedMB = getTotalBytesSaved() / (1024 * 1024);
        int  count   = getTotalVideosCompressed();
        return count + " videos compressed, " + savedMB + " MB total saved";
    }

    /**
     * Resolve effective quality for a chat, considering network + data-saver.
     */
    public Quality resolveEffectiveQuality(Context ctx, String chatId) {
        Quality q = getChatQuality(chatId);

        if (isDataSaverMode()) return Quality.LOW;

        if (q == Quality.AUTO) {
            boolean onWifi = NetworkUtils.isWifi(ctx);
            if (isHdOnWifiOnly()) {
                return onWifi ? Quality.HD : Quality.STANDARD;
            }
            return Quality.STANDARD;
        }

        if (q == Quality.HD || q == Quality.FULL_HD) {
            if (isHdOnWifiOnly() && !NetworkUtils.isWifi(ctx)) {
                return Quality.STANDARD;
            }
        }
        return q;
    }

    /**
     * Estimate output file size in bytes for a given quality and duration.
     * duration in seconds.
     */
    public static long estimateOutputBytes(Quality q, long durationSec) {
        if (q == Quality.ORIGINAL) return -1;
        if (q == Quality.AUTO) return estimateOutputBytes(Quality.STANDARD, durationSec);
        long bitsPerSec = q.bitrate + 128_000; // audio ~128kbps
        return (bitsPerSec / 8) * durationSec;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0)           return "—";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024f);
        return String.format("%.1f MB", bytes / (1024f * 1024f));
    }
}
