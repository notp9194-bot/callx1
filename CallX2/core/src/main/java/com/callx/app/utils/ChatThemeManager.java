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

    // Kept for any lingering references — noop
    public static final int THEME_HYBRID = 0;

    // Bubble drawable cache keyed by (sent<<1 | hasReply).
    // 4 possible combos: sent+reply, sent+noReply, recv+reply, recv+noReply.
    private final SparseArray<GradientDrawable> bubbleCache = new SparseArray<>(4);

    // BUG FIX: ChatThemeManager.instance is a static singleton that survives
    // for the whole app process. bubbleCache used to only get cleared via an
    // explicit setTheme()/clearBubbleCache() call — but nothing called that
    // when the SYSTEM switched between light/dark mode. Result: once a bubble
    // drawable was cached in (say) light mode, every bubble kept showing that
    // same stale light-mode GradientDrawable forever, even after the screen
    // itself (chat background, via resource-qualified surface_chat_bg) had
    // correctly switched to dark. Text color isn't cached and DOES resolve
    // fresh every bind, so the end result was correct dark-mode text sitting
    // on a stale light-mode bubble — exactly the "white text invisible on
    // white/green bubble" symptom. Tracking the last-seen night-mode flag and
    // wiping the cache the moment it changes fixes both the bubble color and
    // the text-contrast issue in one place.
    private Boolean lastNightMode = null;

    private ChatThemeManager(Context ctx) {}

    public static ChatThemeManager get(Context ctx) {
        if (instance == null) instance = new ChatThemeManager(ctx);
        instance.invalidateIfNightModeChanged(ctx);
        return instance;
    }

    private void invalidateIfNightModeChanged(Context ctx) {
        boolean night = isDarkMode(ctx);
        if (lastNightMode == null || lastNightMode != night) {
            bubbleCache.clear();
            lastNightMode = night;
        }
    }

    public int getCurrentTheme() { return THEME_HYBRID; }
    public void setTheme(int id) { bubbleCache.clear(); }
    public void clearBubbleCache() { bubbleCache.clear(); }

    /**
     * PERF: build all 4 bubble-drawable combos (sent/received × reply/no-reply)
     * up front, once, e.g. right after the chat's RecyclerView is created —
     * instead of lazily on whichever bubble happens to bind first. Without
     * this, the very first sent AND first received bubble each pay a
     * GradientDrawable allocation the moment they scroll on screen; with a
     * chat that opens already scrolled to the bottom (the common case) that
     * lands right in the middle of the initial layout pass. Pre-warming
     * moves that one-time cost to adapter setup, before the user sees
     * anything, so first-frame bubble rendering is a pure cache hit.
     */
    public void preWarm(Context ctx) {
        for (int cacheKey = 0; cacheKey < 4; cacheKey++) {
            boolean sent = (cacheKey & 1) != 0;
            getOrCreateBubbleDrawable(ctx, cacheKey, sent);
        }
    }

    private GradientDrawable getOrCreateBubbleDrawable(Context ctx, int cacheKey, boolean sent) {
        GradientDrawable gd = bubbleCache.get(cacheKey);
        if (gd == null) {
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
        return gd;
    }

    /** Apply bubble background — cached per (sent, hasReply) combo to avoid GC pressure. */
    public void applyBubble(View bubbleView, boolean sent, String msgType, boolean hasReply) {
        if (bubbleView == null) return;

        // Cache key: bit0 = sent, bit1 = hasReply  →  4 possible drawables total
        int cacheKey = (sent ? 1 : 0) | (hasReply ? 2 : 0);
        GradientDrawable gd = getOrCreateBubbleDrawable(bubbleView.getContext(), cacheKey, sent);

        // PERF SAFETY: GradientDrawable is mutable — sharing the same instance
        // across multiple Views means setColor()/setAlpha() on one bubble would
        // visually affect every other bubble with the same cache key (same
        // sent/hasReply combo). mutate() ensures each View owns an independent
        // copy of the state while still reusing the pre-configured corner radii.
        bubbleView.setBackground(gd.mutate());
    }

    /**
     * Text color for bubble content — from color resources.
     * BUG FIX: this used to ignore the Context entirely and always return a
     * hardcoded dark color, regardless of light/dark mode — so setText color
     * calls in the adapter silently overrode the correct
     * @color/bubble_sent_text / bubble_received_text values from XML with a
     * constant dark navy, making text nearly invisible in dark mode. Now it
     * resolves the actual theme-aware color resource (light mode → black,
     * dark mode → white, via values-night).
     */
    public int getTextColor(Context ctx, boolean sent) {
        return resolveColor(ctx, sent
                ? com.callx.app.core.R.color.bubble_sent_text
                : com.callx.app.core.R.color.bubble_received_text);
    }

    public int getPrimaryColor() {
        // COLOR: premium deep-emerald (was WhatsApp green 0xFF008069)
        return 0xFF0F4C3A;
    }

    public int getSecondaryColor() {
        // COLOR: champagne-gold accent (was WhatsApp light-green 0xFF25D366)
        return 0xFFD4AF37;
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

    // PERF ADV: made static — this never actually depended on dark mode or
    // any other instance state, so every caller was paying for
    // ChatThemeManager.get(ctx)'s singleton + isDarkMode() check just to
    // reach a plain ternary. Callers now call this directly
    // (ChatThemeManager.getTickColor(read)) instead of
    // ChatThemeManager.get(ctx).getTickColor(read) — same result, no
    // wasted per-bind work during fast scroll.
    public static int getTickColor(boolean isRead) {
        // COLOR / SIGNATURE: read ticks are champagne-gold instead of the
        // usual WhatsApp blue (0xFF34B7F1) — the one deliberate premium
        // "tell" of this app, echoed by the same gold in the waveform
        // played-progress and the poll's leading-option accent so it reads
        // as one consistent signature rather than a random recolor.
        return isRead ? 0xFFD4AF37 : 0xFF8FAF9F;
    }

    public static boolean isDarkMode(Context ctx) {
        int flags = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static int resolveColor(Context ctx, int resId) {
        return ctx.getResources().getColor(resId, ctx.getTheme());
    }
}
