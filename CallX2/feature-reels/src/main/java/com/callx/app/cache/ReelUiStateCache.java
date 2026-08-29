package com.callx.app.cache;

import androidx.annotation.Nullable;

import com.callx.app.models.ReelModel;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
        /** Ready-to-bind hashtag strings; built before the reel reaches the swipe frame. */
        public final String[] hashtagLabels;
        /** Raw hashtag values used as click payloads without substring allocations. */
        public final String[] hashtagTags;
        public final String duetLabel;
        public final String stitchLabel;

        State(String likesText, String commentsText, String sharesText,
              String repostText, String captionText, String[] hashtagLabels,
              String[] hashtagTags, String duetLabel, String stitchLabel) {
            this.likesText = likesText;
            this.commentsText = commentsText;
            this.sharesText = sharesText;
            this.repostText = repostText;
            this.captionText = captionText;
            this.hashtagLabels = hashtagLabels;
            this.hashtagTags = hashtagTags;
            this.duetLabel = duetLabel;
            this.stitchLabel = stitchLabel;
        }
    }

    /**
     * Match ReelModel's existing rendering limit. Keeping this value aligned
     * preserves every currently visible hashtag and click target while the
     * row's views/layout params are reused.
     */
    public static final int MAX_VISIBLE_HASHTAG_CHIPS = 30;

    /** Bound on entry count. The comment above (kept for history) argued this
     *  didn't need an LRU because the prewarm window is "a handful at a
     *  time" — true in steady state, but a long scroll session across a big
     *  feed with switchFeed()/loadMoreReels() churn still only ever *adds*
     *  entries, never removes them, so the map grows for the lifetime of the
     *  process. Capping it with access-order eviction keeps the intended
     *  "cheap prewarm cache" behavior while putting a hard ceiling on it. */
    private static final int MAX_ENTRIES = 64;

    private static final Object lock = new Object();
    private static final Map<String, State> cache =
        new LinkedHashMap<String, State>(MAX_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, State> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

    private ReelUiStateCache() { }

    @Nullable
    public static State get(@Nullable String reelId) {
        if (reelId == null) return null;
        synchronized (lock) {
            return cache.get(reelId);
        }
    }

    /** Computes and stores the state for a single reel — safe to call off the main thread. */
    public static State compute(ReelModel reel) {
        String likes    = formatCount(reel.likesCount);
        String comments = formatCount(reel.commentsCount);
        String shares   = formatCount(reel.sharesCount);
        String repost   = formatCount(reel.repostCount);

        // Guard: reel.caption is Firebase-POJO-mapped (reflection-set,
        // bypasses ReelModel's constructor truncation) so it can still be
        // huge here. Cap it before it gets cached and later handed straight
        // to a TextView by ReelUiController — an oversized caption ending up
        // as a View's saved instance state is what trips
        // TransactionTooLargeException when the Activity is stopped.
        String captionText = ReelModel.safeCaption(reel.caption != null ? reel.caption : "");
        if (reel.duetOf != null && !reel.duetOf.isEmpty()) {
            captionText = "\uD83D\uDD00 Duet \u00B7 " + captionText;
        }

        java.util.List<String> sourceTags = reel.hashtags;
        if (sourceTags == null || sourceTags.isEmpty()) {
            sourceTags = ReelModel.extractHashtags(reel.caption);
        }
        int visibleTagCount = Math.min(MAX_VISIBLE_HASHTAG_CHIPS, sourceTags.size());
        String[] hashtagLabels = new String[visibleTagCount];
        String[] hashtagTags = new String[visibleTagCount];
        int tagIndex = 0;
        for (int i = 0; i < sourceTags.size() && tagIndex < visibleTagCount; i++) {
            String tag = sourceTags.get(i);
            if (tag == null || tag.isEmpty()) continue;
            hashtagTags[tagIndex] = tag;
            hashtagLabels[tagIndex] = "#" + tag;
            tagIndex++;
        }
        if (tagIndex != visibleTagCount) {
            hashtagLabels = java.util.Arrays.copyOf(hashtagLabels, tagIndex);
            hashtagTags = java.util.Arrays.copyOf(hashtagTags, tagIndex);
        }

        String duetLabel = reel.duetCount > 0
            ? "\uD83D\uDD00 " + formatCount(reel.duetCount) + " Duet"
                + (reel.duetCount == 1 ? "" : "s") + "  \u203A"
            : null;
        String stitchLabel = reel.stitchCount > 0
            ? "\u2702\uFE0F " + formatCount(reel.stitchCount) + " Stitch"
                + (reel.stitchCount == 1 ? "" : "es") + "  \u203A"
            : null;

        State state = new State(likes, comments, shares, repost, captionText,
            hashtagLabels, hashtagTags, duetLabel, stitchLabel);
        if (reel.reelId != null) {
            synchronized (lock) {
                cache.put(reel.reelId, state);
            }
        }
        return state;
    }

    /** Same formatting rule as ReelPlayerFragment.formatCount — kept in sync intentionally. */
    private static String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    public static void clear() {
        synchronized (lock) {
            cache.clear();
        }
    }
}
