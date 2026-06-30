package com.callx.app.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * NotificationChannelHelper — Shared utility for registering Android notification channels.
 *
 * Saare feature modules (reels, x, youtube) is helper ko use karte hain channel
 * registration ke liye — duplicate channel-creation code remove kiya gaya hai.
 *
 * All methods are idempotent (safe to call multiple times — Android ignores duplicates).
 *
 * Usage:
 *   NotificationChannelHelper.registerChannel(nm, id, name, desc, importance, lightColor);
 *   NotificationChannelHelper.registerSilentChannel(nm, id, name, desc);
 */
public final class NotificationChannelHelper {

    private NotificationChannelHelper() {}

    /**
     * Standard channel with lights, vibration, and badge.
     *
     * @param nm          NotificationManager instance
     * @param id          Unique channel ID (e.g. "reel_likes")
     * @param name        User-visible name (e.g. "Reel Likes")
     * @param desc        User-visible description
     * @param importance  NotificationManager.IMPORTANCE_* constant
     * @param lightColor  LED light color as ARGB int (e.g. 0xFFFF3B5C)
     */
    public static void registerChannel(NotificationManager nm, String id, String name,
                                       String desc, int importance, int lightColor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(id, name, importance);
        ch.setDescription(desc);
        ch.enableLights(true);
        ch.setLightColor(lightColor);
        ch.enableVibration(importance >= NotificationManager.IMPORTANCE_DEFAULT);
        if (importance >= NotificationManager.IMPORTANCE_DEFAULT) {
            ch.setVibrationPattern(new long[]{0, 200, 100, 200});
        }
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    /**
     * Minimal silent channel for foreground services — no sound, no badge, no lights.
     *
     * @param nm   NotificationManager instance
     * @param id   Unique channel ID (e.g. "reel_foreground_service")
     * @param name User-visible name
     * @param desc User-visible description
     */
    public static void registerSilentChannel(NotificationManager nm, String id,
                                             String name, String desc) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(id, name,
                NotificationManager.IMPORTANCE_MIN);
        ch.setDescription(desc);
        ch.setShowBadge(false);
        ch.enableLights(false);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }
}
