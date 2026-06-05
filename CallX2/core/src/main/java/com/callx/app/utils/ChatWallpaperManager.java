package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

/**
 * ChatWallpaperManager — Per-chat and global wallpaper system.
 *
 * Storage:
 *   Per-chat  → SharedPrefs key = "wallpaper_<chatId>"
 *   Global    → SharedPrefs key = "wallpaper_global"
 *
 * Priority: per-chat > global > none (theme bg color)
 *
 * Usage:
 *   ChatWallpaperManager.get(ctx).applyWallpaper(ivWallpaper, chatId);
 *   ChatWallpaperManager.get(ctx).setWallpaper(chatId, uri);    // per-chat
 *   ChatWallpaperManager.get(ctx).setGlobalWallpaper(uri);      // global
 *   ChatWallpaperManager.get(ctx).clearWallpaper(chatId);       // remove per-chat
 *   ChatWallpaperManager.get(ctx).clearGlobalWallpaper();       // remove global
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

    /** Set per-chat wallpaper. uri = content:// or file:// URI string. */
    public void setWallpaper(String chatId, Uri uri) {
        if (chatId == null || uri == null) return;
        prefs.edit().putString(PREFIX_PER_CHAT + chatId, uri.toString()).apply();
    }

    /** Set global wallpaper (fallback for all chats). */
    public void setGlobalWallpaper(Uri uri) {
        if (uri == null) return;
        prefs.edit().putString(KEY_GLOBAL, uri.toString()).apply();
    }

    // ── Clear ─────────────────────────────────────────────────────────────

    /** Remove per-chat wallpaper (falls back to global or none). */
    public void clearWallpaper(String chatId) {
        if (chatId == null) return;
        prefs.edit().remove(PREFIX_PER_CHAT + chatId).apply();
    }

    /** Remove global wallpaper. */
    public void clearGlobalWallpaper() {
        prefs.edit().remove(KEY_GLOBAL).apply();
    }

    // ── Query ─────────────────────────────────────────────────────────────

    /** Returns the effective URI string for a chat, or null if none set. */
    public String getEffectiveWallpaper(String chatId) {
        // Per-chat first
        if (chatId != null) {
            String perChat = prefs.getString(PREFIX_PER_CHAT + chatId, null);
            if (perChat != null) return perChat;
        }
        // Global fallback
        return prefs.getString(KEY_GLOBAL, null);
    }

    public boolean hasPerChatWallpaper(String chatId) {
        return chatId != null && prefs.contains(PREFIX_PER_CHAT + chatId);
    }

    public boolean hasGlobalWallpaper() {
        return prefs.contains(KEY_GLOBAL);
    }

    // ── Apply to ImageView ────────────────────────────────────────────────

    /**
     * Loads and displays wallpaper into the background ImageView.
     * If no wallpaper is set, hides the ImageView so the theme bg color shows.
     *
     * @param ivWallpaper  ImageView placed behind the RecyclerView (scaleType=centerCrop)
     * @param chatId       current chat/group ID
     */
    public void applyWallpaper(ImageView ivWallpaper, String chatId) {
        if (ivWallpaper == null) return;
        String uriStr = getEffectiveWallpaper(chatId);
        if (uriStr == null) {
            ivWallpaper.setVisibility(android.view.View.GONE);
            ivWallpaper.setImageDrawable(null);
            return;
        }
        ivWallpaper.setVisibility(android.view.View.VISIBLE);
        Glide.with(ivWallpaper.getContext())
             .load(Uri.parse(uriStr))
             .diskCacheStrategy(DiskCacheStrategy.ALL)
             .centerCrop()
             .into(ivWallpaper);
    }
}
