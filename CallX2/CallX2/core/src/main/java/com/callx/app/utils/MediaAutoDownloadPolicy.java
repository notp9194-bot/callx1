package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

/**
 * MediaAutoDownloadPolicy — WhatsApp-style per-network auto-download settings.
 *
 * Three independent controls, one per media type:
 *   • Images  : downloaded automatically on WiFi, manually on data
 *   • Videos  : always manual (too large for silent background fetch)
 *   • GIFs    : same as Images
 *
 * The active policy (WiFi / MobileData / Never) is stored in SharedPreferences
 * and readable from any thread.
 *
 * Usage:
 *   boolean autoNow = MediaAutoDownloadPolicy.shouldAutoDownload(ctx, "image");
 */
public final class MediaAutoDownloadPolicy {

    private MediaAutoDownloadPolicy() {}

    private static final String PREFS       = "media_auto_dl";
    private static final String KEY_IMAGE   = "policy_image";
    private static final String KEY_VIDEO   = "policy_video";
    private static final String KEY_GIF     = "policy_gif";
    private static final String KEY_AUDIO   = "policy_audio";

    // Policy values stored as strings for human-readability in prefs.
    public static final String POLICY_WIFI       = "wifi";       // auto-download on WiFi only
    public static final String POLICY_WIFI_DATA  = "wifi_data";  // auto-download always
    public static final String POLICY_NEVER      = "never";      // always manual tap

    // ── Defaults (match WhatsApp: images auto on WiFi, videos never) ──────
    private static final String DEFAULT_IMAGE = POLICY_WIFI;
    private static final String DEFAULT_VIDEO = POLICY_NEVER;
    private static final String DEFAULT_GIF   = POLICY_WIFI;
    private static final String DEFAULT_AUDIO = POLICY_WIFI;

    // ── Read current policy ───────────────────────────────────────────────

    public static String getPolicy(Context ctx, String mediaType) {
        SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = keyFor(mediaType);
        return prefs.getString(key, defaultFor(mediaType));
    }

    // ── Write policy (called from settings screen) ────────────────────────

    public static void setPolicy(Context ctx, String mediaType, String policy) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(keyFor(mediaType), policy)
                .apply();
    }

    // ── Main decision point ───────────────────────────────────────────────

    /**
     * Returns true if the item should be downloaded automatically right now,
     * without the user tapping the download pill.
     *
     * @param mediaType "image" | "video" | "gif" | "audio"
     */
    public static boolean shouldAutoDownload(Context ctx, String mediaType) {
        String policy = getPolicy(ctx, mediaType);
        switch (policy) {
            case POLICY_NEVER:
                return false;
            case POLICY_WIFI_DATA:
                return hasInternet(ctx);
            case POLICY_WIFI:
            default:
                return isOnWifi(ctx);
        }
    }

    // ── Network helpers ───────────────────────────────────────────────────

    /** True if there is any usable internet connection right now. */
    public static boolean hasInternet(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** True if the active connection is WiFi (not cellular/data). */
    public static boolean isOnWifi(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String keyFor(String mediaType) {
        if (mediaType == null) return KEY_IMAGE;
        switch (mediaType) {
            case "video": return KEY_VIDEO;
            case "gif":   return KEY_GIF;
            case "audio": return KEY_AUDIO;
            default:      return KEY_IMAGE;
        }
    }

    private static String defaultFor(String mediaType) {
        if (mediaType == null) return DEFAULT_IMAGE;
        switch (mediaType) {
            case "video": return DEFAULT_VIDEO;
            case "gif":   return DEFAULT_GIF;
            case "audio": return DEFAULT_AUDIO;
            default:      return DEFAULT_IMAGE;
        }
    }
}
