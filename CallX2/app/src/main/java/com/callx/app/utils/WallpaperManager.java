package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

/**
 * Feature 14: Chat Wallpaper / Theme
 * Stores per-chat wallpaper URI or solid color.
 * Key format: wallpaper_{chatId}
 */
public class WallpaperManager {
    private static final String PREFS          = "chat_wallpapers";
    private static final String PREFIX_URI     = "uri:";
    private static final String PREFIX_COLOR   = "color:";
    private static final String DEFAULT_GLOBAL = "global_default";

    private static WallpaperManager instance;
    private final SharedPreferences prefs;

    private WallpaperManager(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized WallpaperManager getInstance(Context ctx) {
        if (instance == null) instance = new WallpaperManager(ctx);
        return instance;
    }

    public void setWallpaperUri(String chatId, String uri) {
        prefs.edit().putString(key(chatId), PREFIX_URI + uri).apply();
    }

    public void setWallpaperColor(String chatId, @ColorInt int color) {
        prefs.edit().putString(key(chatId), PREFIX_COLOR + color).apply();
    }

    public void setGlobalDefault(String uriOrColor) {
        prefs.edit().putString(DEFAULT_GLOBAL, uriOrColor).apply();
    }

    public void clearWallpaper(String chatId) {
        prefs.edit().remove(key(chatId)).apply();
    }

    /** @return null = use system default */
    @Nullable public String getWallpaperUri(String chatId) {
        String v = prefs.getString(key(chatId), null);
        if (v == null) v = prefs.getString(DEFAULT_GLOBAL, null);
        if (v != null && v.startsWith(PREFIX_URI)) return v.substring(PREFIX_URI.length());
        return null;
    }

    /** @return null if no color set */
    @Nullable public Integer getWallpaperColor(String chatId) {
        String v = prefs.getString(key(chatId), null);
        if (v == null) v = prefs.getString(DEFAULT_GLOBAL, null);
        if (v != null && v.startsWith(PREFIX_COLOR)) {
            try { return Integer.parseInt(v.substring(PREFIX_COLOR.length())); }
            catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public boolean hasCustomWallpaper(String chatId) {
        return prefs.contains(key(chatId));
    }

    private String key(String chatId) { return "wallpaper_" + chatId; }

    // Predefined theme colors
    public static final int[] THEME_COLORS = {
        Color.parseColor("#ECE5DD"), // WhatsApp classic
        Color.parseColor("#1A1A2E"), // Dark midnight
        Color.parseColor("#0F3460"), // Dark navy
        Color.parseColor("#16213E"), // Dark space
        Color.parseColor("#F5F5F5"), // Light grey
        Color.parseColor("#E8F5E9"), // Soft green
        Color.parseColor("#E3F2FD"), // Soft blue
        Color.parseColor("#FCE4EC"), // Soft pink
        Color.parseColor("#FFF8E1"), // Soft amber
        Color.parseColor("#F3E5F5"), // Soft purple
    };

    public static final String[] THEME_NAMES = {
        "Classic", "Midnight", "Navy", "Space", "Light",
        "Nature", "Ocean", "Rose", "Amber", "Lavender"
    };
}
