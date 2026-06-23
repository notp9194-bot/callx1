package com.callx.app.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;

/**
 * ChatThemeManager — Lightweight, allocation-free chat theming.
 *
 * KEY PERF FIX: applyBubble() now does NOTHING — bubble backgrounds are
 * already set via XML drawables (bubble_sent.xml / bubble_received.xml).
 * Removing the per-bind GradientDrawable allocation eliminates the single
 * biggest source of mid-scroll object churn and redundant layout invalidation.
 *
 * applyScreenTheme() now caches all GradientDrawable instances so they are
 * built once per process lifetime, not rebuilt on every call.
 */
public class ChatThemeManager {

    private static ChatThemeManager instance;

    // Not used at runtime — kept for any lingering references
    public static final int THEME_HYBRID = 0;

    // ── Cached drawables — built once, reused forever ─────────────────────
    private GradientDrawable cachedToolbarGd;
    private GradientDrawable cachedSendBtnGd;
    private GradientDrawable cachedMicBtnGd;

    private ChatThemeManager(Context ctx) {}

    public static ChatThemeManager get(Context ctx) {
        if (instance == null) instance = new ChatThemeManager(ctx);
        return instance;
    }

    public int getCurrentTheme() { return THEME_HYBRID; }
    public void setTheme(int id) {}
    public void clearBubbleCache() {}

    /**
     * PERF: No-op. Bubble shape/colour is already handled by XML drawables
     * (bubble_sent.xml = #8B5CF6 purple, bubble_received.xml = #2D2D3A dark).
     * Calling this previously allocated a new GradientDrawable on every
     * RecyclerView bind — up to ~60× per second while scrolling.
     */
    public void applyBubble(android.view.View bubbleView, boolean sent, String msgType, boolean hasReply) {
        // Intentional no-op: background already set by XML drawable.
    }

    public int getTextColor(boolean sent) {
        return sent ? 0xFFFFFFFF : 0xFF111111;
    }

    public int getPrimaryColor()   { return 0xFF075E54; }
    public int getSecondaryColor() { return 0xFF25D366; }

    public int getChatBgColor(Context ctx) {
        return isDarkMode(ctx) ? 0xFF0B141A : 0xFFECE5DD;
    }

    public int getInputBarColor(Context ctx) {
        return isDarkMode(ctx) ? 0xFF1F2C34 : 0xFFFFFFFF;
    }

    /**
     * PERF: GradientDrawables are built once and reused.
     * Previously rebuilt from scratch on every call (e.g. every time
     * the activity resumed or a theme was re-applied).
     */
    public void applyScreenTheme(
            android.view.View toolbar,
            android.view.View chatRoot,
            android.view.View inputBarRoot,
            android.view.View btnSend,
            android.view.View btnMic,
            android.view.View fab,
            android.view.View replyAccent) {

        int primary   = getPrimaryColor();
        int secondary = getSecondaryColor();

        // Toolbar gradient — build once
        if (cachedToolbarGd == null) {
            cachedToolbarGd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{primary, secondary});
        }
        toolbar.setBackground(cachedToolbarGd);

        android.content.Context ctx = toolbar.getContext();
        if (chatRoot != null)     chatRoot.setBackgroundColor(getChatBgColor(ctx));
        if (inputBarRoot != null) inputBarRoot.setBackgroundColor(getInputBarColor(ctx));

        // Send button gradient — build once
        if (btnSend != null) {
            if (cachedSendBtnGd == null) {
                cachedSendBtnGd = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[]{primary, secondary});
                cachedSendBtnGd.setShape(GradientDrawable.OVAL);
            }
            btnSend.setBackground(cachedSendBtnGd);
        }

        // Mic button gradient — build once
        if (btnMic != null) {
            if (cachedMicBtnGd == null) {
                cachedMicBtnGd = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[]{primary, secondary});
                cachedMicBtnGd.setShape(GradientDrawable.OVAL);
            }
            btnMic.setBackground(cachedMicBtnGd);
        }

        if (fab != null) {
            fab.setBackgroundTintList(ColorStateList.valueOf(primary));
        }
        if (replyAccent != null) {
            replyAccent.setBackgroundColor(primary);
        }
    }

    public int getTickColor(boolean isRead) {
        return isRead ? 0xFF34B7F1 : 0xFF8FAF9F;
    }

    private boolean isDarkMode(Context ctx) {
        int flags = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}
