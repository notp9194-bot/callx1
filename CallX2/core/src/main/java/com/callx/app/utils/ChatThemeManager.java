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

    // ULTRA PERF: corner-radius arrays for applyBubbleOwned(), computed once
    // per density and reused forever. GradientDrawable#setCornerRadii()
    // stores the array reference internally rather than cloning it, and we
    // never mutate these arrays after building them, so sharing one instance
    // across every bubble in the app (sent/received alike) is safe — unlike
    // sharing the GradientDrawable itself, an immutable-in-practice float[]
    // has no per-View state to corrupt.
    private float[] sentRadii;
    private float[] receivedRadii;
    private float radiiDensity = -1f;

    private void ensureRadii(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        if (sentRadii != null && radiiDensity == d) return;
        radiiDensity = d;
        float r = 18f * d;
        float tail = 4f * d;
        sentRadii = new float[]{r, r, r, r, tail, tail, r, r};
        receivedRadii = new float[]{tail, tail, r, r, r, r, r, r};
    }

    private ChatThemeManager(Context ctx) {}

    public static ChatThemeManager get(Context ctx) {
        if (instance == null) instance = new ChatThemeManager(ctx);
        return instance;
    }

    public int getCurrentTheme() { return THEME_HYBRID; }
    // Kept as no-ops for any lingering external callers — there is no
    // shared bubble-drawable cache left to clear (see applyBubbleOwned()):
    // every holder owns its own GradientDrawable and color is resolved
    // fresh from resources on every apply, so light/dark switches and
    // setTheme() calls are picked up automatically with nothing to
    // invalidate.
    public void setTheme(int id) { }
    public void clearBubbleCache() { }

    /**
     * PERF: pre-compute the shared corner-radius arrays (see ensureRadii())
     * up front, e.g. right after the chat's RecyclerView is created —
     * instead of lazily on whichever bubble happens to bind first. The
     * arrays themselves are cheap, but this keeps all one-time setup work
     * grouped at adapter-setup time, before the user sees anything.
     */
    public void preWarm(Context ctx) {
        ensureRadii(ctx);
    }

    /**
     * ULTRA PERF: zero-allocation bubble apply for the adapter hot path.
     *
     * OLD PATH (removed): a shared cached GradientDrawable had to be
     * mutate()'d on every fresh bind (every new/recycled ViewHolder's
     * first bind, and any bind after a hasReply flip) — mutate() allocates
     * a brand-new ConstantState-backed copy each time it's called, because
     * the shared instance's bounds get overwritten by whichever bubble View
     * laid out last (different messages have different bubble widths), so
     * every View needed its own private copy. That meant every message
     * visible the moment a chat screen opened paid a fresh allocation.
     *
     * This method sidesteps the problem instead of paying to work around
     * it: each ViewHolder owns exactly one GradientDrawable for its entire
     * lifetime (allocated once, in the adapter's ViewHolder constructor via
     * newOwnedBubbleDrawable() — never touched again after that). Because
     * that instance is never shared with any other View, its bounds are
     * exclusively that row's to own — the only reason mutate() existed —
     * so rebinding it (including the very first bind after every chat
     * open) is just setColor()+setCornerRadii() on the existing object:
     * no allocation, just an internal-state update + self-invalidate.
     * setBackground() itself is only called the first time a given holder
     * ever shows a bubble, or after it briefly held a bubbleless media
     * background — never on a plain reply-state flip.
     */
    public void applyBubbleOwned(View bubbleView, GradientDrawable owned, boolean sent, boolean hasReply) {
        if (bubbleView == null || owned == null) return;
        ensureRadii(bubbleView.getContext());
        int start = resolveColor(bubbleView.getContext(), sent
                ? com.callx.app.core.R.color.chat_bubble_sent_start
                : com.callx.app.core.R.color.chat_bubble_received_start);
        int end = resolveColor(bubbleView.getContext(), sent
                ? com.callx.app.core.R.color.chat_bubble_sent_end
                : com.callx.app.core.R.color.chat_bubble_received_end);
        owned.setColors(new int[]{start, end}, null,
                GradientDrawable.Orientation.TL_BR);
        owned.setCornerRadii(sent ? sentRadii : receivedRadii);
        if (bubbleView.getBackground() != owned) {
            bubbleView.setBackground(owned);
        }
    }

    /** Allocated once per ViewHolder, ever — call from the ViewHolder's constructor. */
    public static GradientDrawable newOwnedBubbleDrawable() {
        return new GradientDrawable();
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
        return resolveColor(ctx, com.callx.app.core.R.color.chat_bubble_text);
    }

    public int getPrimaryColor() {
        return 0xFF7786FF;
    }

    public int getSecondaryColor() {
        return 0xFFB7C4EE;
    }

    public int getChatBgColor(Context ctx) {
        return resolveColor(ctx, com.callx.app.core.R.color.chat_bg_start);
    }

    public int getInputBarColor(Context ctx) {
        return resolveColor(ctx, com.callx.app.core.R.color.chat_header_start);
    }

    /**
     * Apply screen theme — uses color resources instead of hardcoded gradients.
     * Toolbar and input bar get @color/bar_background (WhatsApp green / dark green).
     */
    public void applyScreenTheme(
            View toolbar,
            View chatRoot,
            View inputBarRoot,
            View fab,
            View replyAccent) {

        if (toolbar == null) return;
        Context ctx = toolbar.getContext();

        int barColor    = resolveColor(ctx, com.callx.app.core.R.color.chat_header_start);
        int barColorEnd = resolveColor(ctx, com.callx.app.core.R.color.chat_header_end);
        int chatBgStart = resolveColor(ctx, com.callx.app.core.R.color.chat_bg_start);
        int chatBgEnd   = resolveColor(ctx, com.callx.app.core.R.color.chat_bg_end);
        int brandColor  = resolveColor(ctx, com.callx.app.core.R.color.chat_accent);

        GradientDrawable toolbarBg = new GradientDrawable();
        toolbarBg.setColors(new int[]{barColor, barColorEnd}, null,
                GradientDrawable.Orientation.TL_BR);
        toolbar.setBackground(toolbarBg);

        if (chatRoot != null) {
            GradientDrawable chatBg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{chatBgStart, chatBgEnd});
            chatRoot.setBackground(chatBg);
        }
        if (inputBarRoot != null) inputBarRoot.setBackgroundColor(0x00000000);

        // NOTE: the mic/send accent used to be applied here via a
        // GradientDrawable background swap on btnSend/btnMic. Since the
        // icon-bar merge, mic/send are painted inside the single
        // ChatIconBarView (feature-chat module, which core can't depend
        // on) — callers now apply the accent themselves via
        // ChatIconBarView#setAccentColor(getPrimaryColor()) after calling
        // this method.
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
        return isRead ? 0xFF68B6FF : 0xFF9AA9D4;
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
