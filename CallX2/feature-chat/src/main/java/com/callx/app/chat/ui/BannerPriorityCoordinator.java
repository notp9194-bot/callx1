package com.callx.app.chat.ui;

import android.view.View;

/**
 * BannerPriorityCoordinator — resolves the visual-hierarchy collision when
 * both floating banners are active at once:
 *
 *   ll_watching_banner  (top|center)   — "X aapko dekh rha hai" / overlapping
 *                                        avatars. Ambient/passive info: nice
 *                                        to know, not time-sensitive.
 *   ll_typing_strip     (bottom|start) — "X typing" + animated dots.
 *                                        Time-sensitive/actionable: a reply
 *                                        is actively being composed right now.
 *
 * They don't overlap spatially (top vs bottom), but showing both at full
 * visual weight simultaneously splits attention across two competing
 * "someone is doing something" signals. Typing wins: it's the more urgent,
 * more actionable of the two. While both are visible, the watching banner
 * recedes (dimmed + slightly shrunk) so the eye lands on the typing strip
 * first; it returns to full prominence the moment typing stops, with no
 * change to its own show/hide logic or timers.
 *
 * Purely cosmetic and reversible — never touches either banner's underlying
 * visibility, content, or Firebase-driven state, only alpha/scale. Safe to
 * call redundantly; both methods are idempotent against the current state.
 */
public final class BannerPriorityCoordinator {

    private static final float RECEDED_ALPHA = 0.55f;
    private static final float RECEDED_SCALE = 0.92f;

    private BannerPriorityCoordinator() {}

    /** Call whenever the typing strip becomes visible (or the watching
     *  banner becomes visible while typing is already showing). If both
     *  are now on screen, dims the watching banner toward the typing strip. */
    public static void onTypingStripShown(View watchingBanner, View typingStrip) {
        if (watchingBanner == null || typingStrip == null) return;
        if (watchingBanner.getVisibility() != View.VISIBLE) return;
        if (typingStrip.getVisibility() != View.VISIBLE) return;
        recede(watchingBanner);
    }

    /** Call whenever the typing strip is hidden. Restores the watching
     *  banner to full prominence if it's still on screen. */
    public static void onTypingStripHidden(View watchingBanner) {
        if (watchingBanner == null) return;
        if (watchingBanner.getVisibility() != View.VISIBLE) return;
        restore(watchingBanner);
    }

    /** Call whenever the watching banner is freshly shown (its own pop-in
     *  animation already plays out separately) — if typing is already
     *  active, apply the same recede instead of letting it briefly flash
     *  at full prominence before the next typing tick dims it. */
    public static void applyCurrentPriority(View watchingBanner, View typingStrip) {
        if (watchingBanner == null) return;
        if (watchingBanner.getVisibility() != View.VISIBLE) return;
        boolean typingActive = typingStrip != null && typingStrip.getVisibility() == View.VISIBLE;
        if (typingActive) recede(watchingBanner); else restore(watchingBanner);
    }

    private static void recede(View banner) {
        if (banner.getAlpha() <= RECEDED_ALPHA + 0.01f) return;
        banner.setAlpha(RECEDED_ALPHA);
        banner.setScaleX(RECEDED_SCALE);
        banner.setScaleY(RECEDED_SCALE);
    }

    private static void restore(View banner) {
        if (banner.getAlpha() >= 0.99f) return;
        banner.setAlpha(1f);
        banner.setScaleX(1f);
        banner.setScaleY(1f);
    }
}
