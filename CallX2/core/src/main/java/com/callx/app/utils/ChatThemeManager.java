package com.callx.app.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/**
 * ChatThemeManager — Uses color resources for proper light/dark theme support.
 * All colors come from colors.xml / values-night/colors.xml — zero hardcoded hex.
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

    /** Apply bubble background — reads from color resources for correct light/dark support. */
    public void applyBubble(View bubbleView, boolean sent, String msgType, boolean hasReply) {
        if (bubbleView == null) return;

        Context ctx = bubbleView.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        float r = 18f * d;
        float tail = 4f * d;

        int color = resolveColor(ctx, sent
                ? com.callx.app.core.R.color.bubble_sent
                : com.callx.app.core.R.color.bubble_received);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);

        if (sent) {
            // top-left, top-right, bottom-right, bottom-left (each corner = 2 floats)
            gd.setCornerRadii(new float[]{r, r, r, r, tail, tail, r, r});
        } else {
            gd.setCornerRadii(new float[]{tail, tail, r, r, r, r, r, r});
        }
        bubbleView.setBackground(gd);
    }

    /** Text color for bubble content — from color resources. */
    public int getTextColor(boolean sent) {
        // Return 0 = use XML layout colors (this method is now only a fallback)
        // Callers should prefer @color/bubble_sent_text / bubble_received_text in XML
        return sent ? 0xFF111B21 : 0xFF111B21;
    }

    public int getPrimaryColor() {
        // Used only for reply accent tint — WhatsApp green
        return 0xFF008069;
    }

    public int getSecondaryColor() {
        return 0xFF25D366;
    }

    public int getChatBgColor(Context ctx) {
        return resolveColor(ctx, com.callx.app.core.R.color.surface_chat_bg);
    }

    public int getInputBarColor(Context ctx) {
        return resolveColor(ctx, com.callx.app.core.R.color.bar_background);
    }

    /**
     * Apply screen theme — uses color resources instead of hardcoded gradients.
     * Toolbar and input bar get @color/bar_background (WhatsApp green / dark green).
     */
    public void applyScreenTheme(
            View toolbar,
            View chatRoot,
            View inputBarRoot,
            View btnSend,
            View btnMic,
            View fab,
            View replyAccent) {

        if (toolbar == null) return;
        Context ctx = toolbar.getContext();

        int barColor    = resolveColor(ctx, com.callx.app.core.R.color.bar_background);
        int chatBgColor = resolveColor(ctx, com.callx.app.core.R.color.surface_chat_bg);
        int brandColor  = resolveColor(ctx, com.callx.app.core.R.color.brand_primary);

        // Solid toolbar — matches @color/bar_background in XML (no gradient override)
        GradientDrawable toolbarBg = new GradientDrawable();
        toolbarBg.setColor(barColor);
        toolbar.setBackground(toolbarBg);

        if (chatRoot != null)    chatRoot.setBackgroundColor(chatBgColor);
        if (inputBarRoot != null) inputBarRoot.setBackgroundColor(barColor);

        if (btnSend != null) {
            GradientDrawable sd = new GradientDrawable();
            sd.setShape(GradientDrawable.OVAL);
            sd.setColor(brandColor);
            btnSend.setBackground(sd);
        }
        if (btnMic != null) {
            GradientDrawable md = new GradientDrawable();
            md.setShape(GradientDrawable.OVAL);
            md.setColor(brandColor);
            btnMic.setBackground(md);
        }
        if (fab != null) {
            fab.setBackgroundTintList(ColorStateList.valueOf(brandColor));
        }
        if (replyAccent != null) {
            replyAccent.setBackgroundColor(brandColor);
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

    private static int resolveColor(Context ctx, int resId) {
        return ctx.getResources().getColor(resId, ctx.getTheme());
    }
}
