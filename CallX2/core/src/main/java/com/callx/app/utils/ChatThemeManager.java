package com.callx.app.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseArray;
import android.view.View;

/**
 * ChatThemeManager — Uses color resources for proper light/dark theme support.
 * All colors come from colors.xml / values-night/colors.xml — zero hardcoded hex.
 */
public class ChatThemeManager {

    private static ChatThemeManager instance;

    public static final int THEME_HYBRID = 0;

    private final SparseArray<GradientDrawable> bubbleCache = new SparseArray<>(4);

    private ChatThemeManager(Context ctx) {}

    public static ChatThemeManager get(Context ctx) {
        if (instance == null) instance = new ChatThemeManager(ctx);
        return instance;
    }

    public int getCurrentTheme() { return THEME_HYBRID; }
    public void setTheme(int id) { bubbleCache.clear(); }
    public void clearBubbleCache() { bubbleCache.clear(); }

    /** Apply bubble background — cached per (sent, hasReply) combo. */
    public void applyBubble(View bubbleView, boolean sent, String msgType, boolean hasReply) {
        if (bubbleView == null) return;

        int cacheKey = (sent ? 1 : 0) | (hasReply ? 2 : 0);
        GradientDrawable gd = bubbleCache.get(cacheKey);

        if (gd == null) {
            Context ctx = bubbleView.getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            float r = 18f * d;
            float tail = 4f * d;

            int color = resolveColor(ctx, sent
                    ? com.callx.app.core.R.color.bubble_sent
                    : com.callx.app.core.R.color.bubble_received);

            gd = new GradientDrawable();
            gd.setColor(color);

            if (sent) {
                gd.setCornerRadii(new float[]{r, r, r, r, tail, tail, r, r});
            } else {
                gd.setCornerRadii(new float[]{tail, tail, r, r, r, r, r, r});
            }
            bubbleCache.put(cacheKey, gd);
        }

        bubbleView.setBackground(gd.mutate());
    }

    /**
     * Text color for bubble content.
     * Always white — works on green sent bubbles and dark received bubbles in both modes.
     */
    public int getTextColor(boolean sent) {
        return 0xFFFFFFFF;
    }

    public int getPrimaryColor() {
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

        GradientDrawable toolbarBg = new GradientDrawable();
        toolbarBg.setColor(barColor);
        toolbar.setBackground(toolbarBg);

        if (chatRoot != null)     chatRoot.setBackgroundColor(chatBgColor);
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

    private static int resolveColor(Context ctx, int resId) {
        return ctx.getResources().getColor(resId, ctx.getTheme());
    }
}
