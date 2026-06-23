package com.callx.app.utils;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/**
 * ChatThemeManager — Minimal clean chat bubbles. No theme system.
 * Sent: brand green. Received: white/light grey.
 */
public class ChatThemeManager {

    private static ChatThemeManager instance;

    // Kept for any lingering references — noop
    public static final int THEME_HYBRID = 0;

    private ChatThemeManager(Context ctx) {}

    public static ChatThemeManager get(Context ctx) {
        if (instance == null) instance = new ChatThemeManager(ctx);
        return instance;
    }

    public int getCurrentTheme() { return THEME_HYBRID; }
    public void setTheme(int id) {}
    public void clearBubbleCache() {}

    public void applyBubble(android.view.View bubbleView, boolean sent, String msgType, boolean hasReply) {
        if (bubbleView == null) return;
        float d = bubbleView.getContext().getResources().getDisplayMetrics().density;
        float r = 18f * d;
        float tail = 4f * d;

        GradientDrawable gd = new GradientDrawable();
        if (sent) {
            gd.setColor(0xFF075E54); // WhatsApp-style dark green for sent
            gd.setCornerRadii(new float[]{r, r, tail, tail, r, r, r, r});
        } else {
            gd.setColor(0xFFFFFFFF); // white for received
            gd.setCornerRadii(new float[]{tail, tail, r, r, r, r, r, r});
        }
        bubbleView.setBackground(gd);
    }

    public int getTextColor(boolean sent) {
        return sent ? 0xFFFFFFFF : 0xFF111111;
    }

    public int getPrimaryColor() { return 0xFF075E54; }
    public int getSecondaryColor() { return 0xFF25D366; }

    public int getChatBgColor(Context ctx) {
        boolean dark = isDarkMode(ctx);
        return dark ? 0xFF0B141A : 0xFFECE5DD;
    }

    public int getInputBarColor(Context ctx) {
        boolean dark = isDarkMode(ctx);
        return dark ? 0xFF1F2C34 : 0xFFFFFFFF;
    }

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

        GradientDrawable toolbarGd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{primary, secondary});
        toolbar.setBackground(toolbarGd);

        android.content.Context ctx = toolbar.getContext();
        if (chatRoot != null)    chatRoot.setBackgroundColor(getChatBgColor(ctx));
        if (inputBarRoot != null) inputBarRoot.setBackgroundColor(getInputBarColor(ctx));

        if (btnSend != null) {
            GradientDrawable sd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{primary, secondary});
            sd.setShape(GradientDrawable.OVAL);
            btnSend.setBackground(sd);
        }
        if (btnMic != null) {
            GradientDrawable md = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{primary, secondary});
            md.setShape(GradientDrawable.OVAL);
            btnMic.setBackground(md);
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
        return isRead ? 0xFF34B7F1 : 0xFF8FAF9F;
    }

    private boolean isDarkMode(Context ctx) {
        int flags = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}
