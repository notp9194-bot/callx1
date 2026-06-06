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
}
