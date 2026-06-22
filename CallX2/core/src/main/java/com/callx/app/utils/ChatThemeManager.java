package com.callx.app.utils;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/**
 * ChatThemeManager — Single minimal Midnight theme. No switching.
 */
public class ChatThemeManager {

    public static final int THEME_HYBRID    = 0;
    public static final int THEME_OCEAN     = 1;
    public static final int THEME_FOREST    = 2;
    public static final int THEME_SUNSET    = 3;
    public static final int THEME_LAVENDER  = 4;
    public static final int THEME_MIDNIGHT  = 5;
    public static final int THEME_CLASSIC   = 6;
    public static final int THEME_MONO      = 7;
    public static final int THEME_CHERRY    = 8;
    public static final int THEME_AURORA    = 9;
    public static final int THEME_COFFEE    = 10;
    public static final int THEME_NEON      = 11;
    public static final int THEME_ROYAL     = 12;
    public static final int THEME_GALAXY    = 13;
    public static final int THEME_CANDY     = 14;
    public static final int THEME_FIRE      = 15;
    public static final int THEME_ICE       = 16;
    public static final int THEME_JUNGLE    = 17;

    public static final String[] THEME_NAMES = {
        "\uD83C\uDF19 Midnight"
    };

    private static final float R_LARGE = 14f;

    private static ChatThemeManager instance;

    private final java.util.Map<Long, GradientDrawable> bubbleDrawableCache =
            new java.util.HashMap<>();

    private ChatThemeManager(Context ctx) {
    }

    public static ChatThemeManager get(Context ctx) {
        if (instance == null) instance = new ChatThemeManager(ctx);
        return instance;
    }

    public int getCurrentTheme() { return THEME_MIDNIGHT; }

    public void setTheme(int themeId) {
        clearBubbleCache();
    }

    public void applyBubble(android.view.View bubbleView,
                            boolean sent, String msgType, boolean hasReply) {
        if (bubbleView == null) return;

        int shape = BubbleShapeManager.get(bubbleView.getContext()).getCurrentShape();
        long cacheKey = ((long) THEME_MIDNIGHT << 16) | ((long) shape << 1) | (sent ? 1L : 0L);

        GradientDrawable cached = bubbleDrawableCache.get(cacheKey);
        if (cached == null) {
            float density = bubbleView.getContext().getResources().getDisplayMetrics().density;
            float[] corners = BubbleShapeManager.get(bubbleView.getContext())
                                  .getCornerRadii(sent, density);
            int color = sent ? 0xFF1E293B : 0xFF334155;

            GradientDrawable gd = new GradientDrawable();
            gd.setColor(color);
            gd.setCornerRadii(corners);

            cached = gd;
            bubbleDrawableCache.put(cacheKey, cached);
        }

        bubbleView.setBackground(cached);
    }

    public void clearBubbleCache() {
        bubbleDrawableCache.clear();
    }

    public int getTextColor(boolean sent) {
        return 0xFFFFFFFF;
    }

    public int getPrimaryColor() {
        return 0xFF1E293B;
    }

    public int getSecondaryColor() {
        return 0xFF0F172A;
    }

    public int getChatBgColor(android.content.Context ctx) {
        return 0xFF050A12;
    }

    public int getInputBarColor(android.content.Context ctx) {
        return 0xFF0D1117;
    }

    public void applyScreenTheme(
            android.view.View toolbar,
            android.view.View chatRoot,
            android.view.View inputBarRoot,
            android.view.View btnSend,
            android.view.View btnMic,
            android.view.View fab,
            android.view.View replyAccent) {

        int primary   = 0xFF1E293B;
        int secondary = 0xFF0F172A;

        android.graphics.drawable.GradientDrawable toolbarGd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        new int[]{primary, secondary});
        toolbar.setBackground(toolbarGd);

        android.content.Context ctx = toolbar.getContext();
        if (chatRoot != null)    chatRoot.setBackgroundColor(getChatBgColor(ctx));
        if (inputBarRoot != null) inputBarRoot.setBackgroundColor(getInputBarColor(ctx));

        if (btnSend != null) {
            android.graphics.drawable.GradientDrawable sendGd =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            new int[]{primary, secondary});
            sendGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            btnSend.setBackground(sendGd);
        }

        if (btnMic != null) {
            android.graphics.drawable.GradientDrawable micGd =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            new int[]{primary, secondary});
            micGd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            btnMic.setBackground(micGd);
        }

        if (fab != null) {
            fab.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(primary));
        }

        if (replyAccent != null) {
            replyAccent.setBackgroundColor(primary);
        }
    }

    public int getTickColor(boolean isRead) {
        return isRead ? 0xFF60A5FA : 0xFF9CA3AF;
    }
}
