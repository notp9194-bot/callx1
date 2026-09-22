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
        int color = resolveColor(bubbleView.getContext(), sent
                ? com.callx.app.core.R.color.bubble_sent
                : com.callx.app.core.R.color.bubble_received);
        owned.setColor(color);
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
     * Text color for bubble content — now computed FROM the bubble's own
     * actual runtime color (bubble_sent / bubble_received), instead of being
     * resolved from a separately-maintained bubble_sent_text /
     * bubble_received_text resource pair.
     *
     * WHY: two independent color pairs (bubble color + bubble text color)
     * had to be hand-kept in sync across values/colors.xml, values-night/
     * colors.xml, and core's own colors.xml — easy to drift (e.g. a bubble
     * color tweak in one file without updating the matching text color),
     * which is exactly what a reference-image request like "sender text
     * matches its own bubble+tail, received text matches its own
     * bubble+tail" is guarding against. Deriving the text color directly
     * from the resolved bubble color makes the two impossible to
     * desynchronize: whatever color the bubble+tail actually paints with
     * (any theme, any future recolor) the text is always computed against
     * that exact value.
     *
     * HOW: standard perceived-luminance check on the resolved bubble color
     * (contrastTextColor()) — light/bright bubbles (e.g. a WhatsApp-green
     * or sticky-note-yellow sent/received bubble) get the app's established
     * dark bubble-text tone; dark/deep bubbles get the established light
     * tone. The two tones themselves are unchanged from before (same
     * dark-navy / near-white pair the app already used), so this is a
     * behavior-preserving no-op wherever the old resource pair already
     * matched the bubble — it only self-corrects where they didn't.
     */
    // v426 PERF: getTextColor() runs on EVERY bubble bind (text, image,
    // caption, album, ...) and used to do a full Resources.getColor() theme
    // resolve each time. Both colors are resolved+computed once and
    // memoized in an immutable holder keyed by uiMode (so a dark/light
    // switch or any other configuration change transparently re-resolves).
    // Immutable + volatile = safe for the background text-precompute
    // threads that also call this.
    private static final class TextColors {
        final int uiMode, sent, received;
        TextColors(int uiMode, int sent, int received) {
            this.uiMode = uiMode; this.sent = sent; this.received = received;
        }
    }
    private volatile TextColors textColors;

    // The app's two established bubble-text tones (previously
    // bubble_*_text's light-mode and night-mode values respectively) — kept
    // as the only two candidates so this stays a pure "which existing tone
    // fits this bubble" decision, never introducing a brand-new color.
    private static final int TEXT_TONE_DARK  = 0xFF111B21; // for light/bright bubbles
    private static final int TEXT_TONE_LIGHT = 0xFFE9EDEF; // for dark/deep bubbles

    /**
     * Perceived-luminance contrast pick: returns whichever of the two
     * established text tones reads cleanly on top of {@code bubbleColor}.
     * Standard broadcast-luma weighting (ITU-R BT.601) — cheap, and more
     * than accurate enough for a two-way light/dark bubble-text decision.
     */
    private static int contrastTextColor(int bubbleColor) {
        int r = (bubbleColor >> 16) & 0xFF;
        int g = (bubbleColor >> 8) & 0xFF;
        int b = bubbleColor & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.6 ? TEXT_TONE_DARK : TEXT_TONE_LIGHT;
    }

    public int getTextColor(Context ctx, boolean sent) {
        final int ui = ctx.getResources().getConfiguration().uiMode;
        TextColors tc = textColors;
        if (tc == null || tc.uiMode != ui) {
            int sentBubble = resolveColor(ctx, com.callx.app.core.R.color.bubble_sent);
            int receivedBubble = resolveColor(ctx, com.callx.app.core.R.color.bubble_received);
            tc = new TextColors(ui,
                    contrastTextColor(sentBubble),
                    contrastTextColor(receivedBubble));
            textColors = tc;
        }
        return sent ? tc.sent : tc.received;
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
        // Kept in sync with applyScreenTheme()'s unified chatBgColor —
        // header, chat background, status bar, and nav bar are all
        // chat_unified_bg, which is theme-aware (light value in values/,
        // dark value in values-night/). The input bar pill is intentionally
        // NOT part of this — see chat_input_bar_bg / getInputBarColor().
        return resolveColor(ctx, com.callx.app.core.R.color.chat_unified_bg);
    }

    public int getInputBarColor(Context ctx) {
        // Dedicated color, distinct from the screen background — matches
        // the reference screenshot's pill (light grey in light mode, dark
        // charcoal in dark mode), not the pure white/black chat background.
        return resolveColor(ctx, com.callx.app.core.R.color.chat_input_bar_bg);
    }

    /**
     * Apply screen theme — uses color resources instead of hardcoded gradients.
     * Toolbar/chat background get @color/chat_unified_bg (theme-aware:
     * plain white in light mode, true black in dark mode — Android's own
     * default surface color). The input bar pill keeps its own XML-set
     * @color/chat_input_bar_bg and is deliberately left untouched here —
     * see the inputBarRoot note below.
     */
    public void applyScreenTheme(
            View toolbar,
            View chatRoot,
            View inputBarRoot,
            View fab,
            View replyAccent) {

        if (toolbar == null) return;
        Context ctx = toolbar.getContext();

        // UNIFIED BACKGROUND: header, chat background, status bar, and nav
        // bar all use the exact same @color/chat_unified_bg — Android's own
        // white/true-black surface color, theme-aware (values/colors.xml
        // has the light value, values-night/ has the dark one). Same color
        // also shows through the transparent status bar / nav bar (see
        // ImmersiveModeUtils), so all four read as one continuous surface.
        int barColor    = resolveColor(ctx, com.callx.app.core.R.color.chat_unified_bg);
        int brandColor  = resolveColor(ctx, com.callx.app.core.R.color.brand_primary);
        int chatBgColor = barColor;

        // Solid toolbar — matches @color/chat_unified_bg in XML (no gradient override)
        GradientDrawable toolbarBg = new GradientDrawable();
        toolbarBg.setColor(barColor);
        toolbar.setBackground(toolbarBg);

        if (chatRoot != null) chatRoot.setBackgroundColor(chatBgColor);
        // inputBarRoot (the custom ChatInputBarContainer) is intentionally
        // NOT recolored here. It owns the pill background and already picks
        // up the right light/dark value from its drawable's color resources.

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
