package com.callx.app.feed;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.viewpager2.widget.ViewPager2;

/**
 * ReelPageTransformer — Instagram-style scale+fade page transform for the
 * reels ViewPager2 (PERF/UX advance — "gesture-based snap animation").
 *
 * Positional prewarm/player-pool work makes the PLAYBACK feel instant on
 * swipe; this makes the SWIPE ITSELF feel intentional and snappy rather
 * than a flat 1:1 page slide. As a page moves off-screen it shrinks
 * slightly and fades, so the incoming page reads as "arriving" instead of
 * the two pages just trading places — the same effect Instagram/TikTok
 * use on their vertical feeds.
 *
 * Pure view-property transform (scale/alpha via setPageTransformer) — it
 * has zero interaction with playback/prewarm logic, which is exactly why
 * it's safe to layer on top of everything else in this feed: nothing here
 * touches ExoPlayer, the player pool, or any prewarm/preload path.
 */
public final class ReelPageTransformer implements ViewPager2.PageTransformer {

    /** Smallest scale a fully off-screen (position = ±1) page shrinks to. */
    private static final float MIN_SCALE = 0.92f;
    /** Lowest alpha a fully off-screen page fades to. */
    private static final float MIN_ALPHA = 0.55f;

    @Override
    public void transformPage(@NonNull View page, float position) {
        float absPos = Math.min(Math.abs(position), 1f);

        // Scale: 1f at position=0 (fully visible/current page) down to
        // MIN_SCALE at |position|=1 (fully off-screen).
        float scale = MIN_SCALE + (1f - MIN_SCALE) * (1f - absPos);
        page.setScaleX(scale);
        page.setScaleY(scale);

        // Fade to match — keeps the shrink from looking like a hard crop.
        page.setAlpha(MIN_ALPHA + (1f - MIN_ALPHA) * (1f - absPos));

        // Pivot at the page's own center so the scale reads as "receding"
        // rather than sliding toward a corner.
        page.setPivotX(page.getWidth()  * 0.5f);
        page.setPivotY(page.getHeight() * 0.5f);
    }
}
