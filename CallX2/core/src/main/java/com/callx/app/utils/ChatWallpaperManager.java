package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * ChatWallpaperManager — Per-chat and global wallpaper storage.
 *
 * Only stores/retrieves URI strings. Loading into ImageView is done
 * by the caller (ChatActivity / GroupChatActivity) using Glide,
 * so this class stays Glide-free and safe for the :core module.
 *
 * Priority: per-chat > global > none (theme bg shows)
 *
 * Usage:
 *   String uri = ChatWallpaperManager.get(ctx).getEffectiveWallpaper(chatId);
 *   ChatWallpaperManager.get(ctx).setWallpaper(chatId, uri);
 *   ChatWallpaperManager.get(ctx).setGlobalWallpaper(uri);
 *   ChatWallpaperManager.get(ctx).clearWallpaper(chatId);
 *   ChatWallpaperManager.get(ctx).clearGlobalWallpaper();
 */
public class ChatWallpaperManager {

    private static final String PREF_NAME       = "chat_wallpaper_prefs";
    private static final String KEY_GLOBAL      = "wallpaper_global";
    private static final String PREFIX_PER_CHAT = "wallpaper_";

    // ── Solid-color wallpapers ───────────────────────────────────────────
    // Stored as "color:AARRGGBB" in the SAME string slot as an image URI —
    // getEffectiveWallpaper() stays a single string lookup either way.
    // A solid color is the lowest-cost wallpaper option that exists: no
    // Bitmap decode, no Glide disk/memory cache entry (nothing to evict
    // from the LRU), no GPU texture upload beyond a 1x1 fill — the caller
    // (ChatThemeController#applyWallpaper / GroupChatActivity#applyWallpaper)
    // detects this prefix and sets a plain ColorDrawable instead of loading
    // an image, which is what actually keeps scrolling smooth on top of it.
    private static final String COLOR_PREFIX = "color:";

    private static ChatWallpaperManager instance;
    private final SharedPreferences prefs;

    private ChatWallpaperManager(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static ChatWallpaperManager get(Context ctx) {
        if (instance == null) instance = new ChatWallpaperManager(ctx);
        return instance;
    }

    // ── Save ──────────────────────────────────────────────────────────────

    public void setWallpaper(String chatId, Uri uri) {
        if (chatId == null || uri == null) return;
        prefs.edit().putString(PREFIX_PER_CHAT + chatId, uri.toString()).apply();
    }

    public void setGlobalWallpaper(Uri uri) {
        if (uri == null) return;
        prefs.edit().putString(KEY_GLOBAL, uri.toString()).apply();
    }

    /** Sets a flat, low-CPU/GPU solid-color wallpaper for one chat/group. */
    public void setWallpaperColor(String chatId, int color) {
        if (chatId == null) return;
        prefs.edit().putString(PREFIX_PER_CHAT + chatId, COLOR_PREFIX + Integer.toHexString(color)).apply();
    }

    /** Sets a flat, low-CPU/GPU solid-color wallpaper as the global default. */
    public void setGlobalWallpaperColor(int color) {
        prefs.edit().putString(KEY_GLOBAL, COLOR_PREFIX + Integer.toHexString(color)).apply();
    }

    // ── Clear ─────────────────────────────────────────────────────────────

    public void clearWallpaper(String chatId) {
        if (chatId == null) return;
        prefs.edit().remove(PREFIX_PER_CHAT + chatId).apply();
    }

    public void clearGlobalWallpaper() {
        prefs.edit().remove(KEY_GLOBAL).apply();
    }

    // ── Query ─────────────────────────────────────────────────────────────

    /**
     * Returns the effective URI string for this chat, or null if none set.
     * Per-chat takes priority over global.
     */
    public String getEffectiveWallpaper(String chatId) {
        if (chatId != null) {
            String perChat = prefs.getString(PREFIX_PER_CHAT + chatId, null);
            if (perChat != null) return perChat;
        }
        return prefs.getString(KEY_GLOBAL, null);
    }

    public boolean hasPerChatWallpaper(String chatId) {
        return chatId != null && prefs.contains(PREFIX_PER_CHAT + chatId);
    }

    public boolean hasGlobalWallpaper() {
        return prefs.contains(KEY_GLOBAL);
    }

    // ── Solid-color helpers ──────────────────────────────────────────────

    /** True if the stored value (from getEffectiveWallpaper) is a solid color, not an image URI. */
    public static boolean isColorValue(String value) {
        return value != null && value.startsWith(COLOR_PREFIX);
    }

    /** Parses a "color:AARRGGBB" value back into an Android color int. Caller must check isColorValue() first. */
    public static int parseColor(String value) {
        return (int) Long.parseLong(value.substring(COLOR_PREFIX.length()), 16);
    }
}
