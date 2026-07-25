package com.callx.app.cache;

import androidx.annotation.Nullable;

import com.callx.app.models.ReelModel;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReelUiStateCache — PERF advance: "precompute next reel's UI state (like
 * counts, captions) before swipe completes".
 *
 * offscreenPageLimit=4 (see ReelsFragment) already keeps N-1..N+3 fragments
 * alive, but each fragment still does its own string formatting the moment
 * it binds — turning reel.likesCount/commentsCount/sharesCount/repostCount
 * into "12.3K"-style strings (ReelSocialController.populateCounts) and
 * building the duet/caption display text (ReelUiController). That work is
 * cheap per call, but it's still main-thread work landing right as a page
 * becomes current — exactly the frame where ReelChoreographerSnapSync is
 * also trying to keep the snap animation smooth. Precomputing it during
 * onPageSelected for the *next* few reels (see ReelUiStatePrecomputer) and
 * caching the already-formatted strings here means the actual bind, when
 * the swipe completes, is a plain field read instead of a format() call.
 *
 * Keyed by reelId. Intentionally unbounded-but-tiny: this only ever holds
 * entries for reels within the positional prewarm window (a handful at a
 * time), and ReelsFragment.loadMoreReels()/switchFeed() churn means stale
 * entries for reels long since scrolled past just sit here harmlessly (a
 * few formatted strings each) until the process dies — not worth the
 * complexity of an LRU for this size.
 */
public final class ReelUiStateCache {

    /** Precomputed, ready-to-bind UI strings for one reel. */
    public static final class State {
        public final String likesText;
        public final String commentsText;
        public final String sharesText;
        public final String repostText;
        public final String captionText;

        State(String likesText, String commentsText, String sharesText,
              String repostText, String captionText) {
            this.likesText = likesText;
            this.commentsText = commentsText;
            this.sharesText = sharesText;
            this.repostText = repostText;
            this.captionText = captionText;
        }
    }

    private static final Map<String, State> cache = new ConcurrentHashMap<>();

    private ReelUiStateCache() { }

    @Nullable
    public static State get(@Nullable String reelId) {
        if (reelId == null) return null;
        return cache.get(reelId);
    }

    /** Computes and stores the state for a single reel — safe to call off the main thread. */
    public static State compute(ReelModel reel) {
        String likes    = formatCount(reel.likesCount);
        String comments = formatCount(reel.commentsCount);
        String shares   = formatCount(reel.sharesCount);
        String repost   = formatCount(reel.repostCount);

        String captionText = reel.caption != null ? reel.caption : "";
        if (reel.duetOf != null && !reel.duetOf.isEmpty()) {
            captionText = "\uD83D\uDD00 Duet \u00B7 " + captionText;
        }

        State state = new State(likes, comments, shares, repost, captionText);
        if (reel.reelId != null) cache.put(reel.reelId, state);
        return state;
    }

    /** Same formatting rule as ReelPlayerFragment.formatCount — kept in sync intentionally. */
    private static String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    public static void clear() {
        cache.clear();
    }
}
