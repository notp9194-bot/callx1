package com.callx.app.cache;

/**
 * FlingLandingEstimator — turns two consecutive scroll-velocity samples
 * into a predicted landing position, instead of AvatarPrefetcher just
 * reading "fast vs slow" off a single current-velocity snapshot.
 *
 * A fling decelerates at a roughly constant rate while it settles (that's
 * what ViewPager2/RecyclerView's fling friction curve does in practice
 * over the short window between two consecutive onPageScrolled calls) —
 * so two (velocity, timestamp) samples give a real deceleration rate,
 * and standard kinematics (v² = u² + 2as, solved for distance at v=0)
 * gives the remaining scroll distance before the fling actually stops.
 * Converting that distance to page units gives the exact reel the user
 * is about to land on — not just "prefetch depth 1 vs depth 4".
 */
public final class FlingLandingEstimator {

    /** Hard cap — a runaway estimate (noisy sample, near-zero deceleration) shouldn't warm 50 reels. */
    private static final int MAX_ESTIMATE_REELS = 6;

    private FlingLandingEstimator() {}

    /**
     * @param velocityPxPerMs   current sample (most recent onPageScrolled velocity)
     * @param accelPxPerMs2     (currentVelocity - previousVelocity) / dtMs — negative while decelerating
     * @param pageHeightPx      ViewPager2's height (one reel page == one screen height, vertical pager)
     * @return estimated additional reels this fling travels before settling, or -1 if the
     *         data isn't trustworthy yet (still accelerating / first sample / no page height).
     */
    public static int estimateLandingReels(float velocityPxPerMs, float accelPxPerMs2, int pageHeightPx) {
        if (pageHeightPx <= 0) return -1;
        if (velocityPxPerMs <= 0f) return 0; // already stopped
        if (accelPxPerMs2 >= 0f) return -1;  // still speeding up / flat — not a real settle curve yet

        float distancePx = (velocityPxPerMs * velocityPxPerMs) / (2f * Math.abs(accelPxPerMs2));
        int reels = Math.round(distancePx / pageHeightPx);
        return Math.max(0, Math.min(reels, MAX_ESTIMATE_REELS));
    }
}
