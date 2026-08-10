package com.callx.app.ranking;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.ReelModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * FeedRankingEngine (v2) — single, shared ranking implementation used by
 * both the Reels tab (ReelsFragment For-You) and the Home tab (HomeFragment
 * For-You).
 *
 * This is a client-side heuristic — there is no trained ML ranking model
 * here, and none of this is claimed to match Instagram's actual ranker byte
 * for byte. What it does do is combine the same *categories* of signal, and
 * the same overall shape (normalize -> score -> filter -> sort -> guarantee
 * exploration -> diversify), a real production feed ranker uses:
 *
 *   1. Engagement x recency decay   - ReelModel#trendingScore(), LOG-SCALED
 *                                     so one viral outlier can't swamp every
 *                                     personalization signal below it (a
 *                                     100k-like reel would otherwise out-
 *                                     score everything by raw magnitude
 *                                     alone, regardless of relevance)
 *   2. Relationship / affinity      - is this creator followed, and how much
 *                                     has this user actually watched them
 *                                     before (real watch-history counts, not
 *                                     a guess)
 *   3. Watch-time / interest proxy  - avgCompletionRate (recorded on the
 *                                     reel from real viewer sessions) plus a
 *                                     views/duration proxy for reels that
 *                                     don't have completion data yet
 *   4. Explicit topic personalization - boosts hashtags the user opted into
 *                                     and hard-filters ones marked
 *                                     "Not Interested" (users/{uid}/feedSettings)
 *   5. Implicit topic affinity      - hashtags the user has actually watched
 *                                     through, learned from watch history
 *                                     (not something they had to configure),
 *                                     plus a soft "fatigue" penalty for tags
 *                                     they keep bailing out of early
 *   6. Content-type preference      - implicit video-vs-photo-slideshow
 *                                     completion-rate preference, same
 *                                     watch-history source
 *   7. Freshness / cold-start boost - brand-new posts (<2h old) get a flat
 *                                     boost independent of engagement, so
 *                                     they aren't buried before they've had
 *                                     time to accumulate likes/comments
 *   8. Discovery / exploration      - a scoring boost for creators the user
 *                                     has never watched, PLUS a guaranteed
 *                                     exploration slot every N positions
 *                                     (classic epsilon-greedy exploration) so
 *                                     new/small creators get real placements,
 *                                     not just a nudge that a viral post can
 *                                     still outscore
 *   9. Repeat suppression           - a small penalty for reels the user
 *                                     already watched to completion recently
 *  10. Diversity re-ranking         - a second pass over the sorted list so
 *                                     the same creator can't appear twice
 *                                     within a short window AND the same
 *                                     media type (video vs photo) doesn't
 *                                     run more than 2 in a row, even when
 *                                     that would otherwise be the highest-
 *                                     scoring order
 */
public final class FeedRankingEngine {

    private FeedRankingEngine() {}

    // -- Tunable weights -----------------------------------------------------
    private static final float W_ENGAGEMENT_LOG_SCALE     = 12f;   // multiplies log1p(trendingScore)
    private static final float W_FOLLOWED                 = 40f;
    private static final float W_CREATOR_AFFINITY_STEP     = 3f;   // per prior watch
    private static final float W_CREATOR_AFFINITY_CAP      = 30f;
    private static final float W_COMPLETION_RATE           = 20f;  // avgCompletionRate is 0..1
    private static final float W_VIEWS_PROXY                = 0.015f;
    private static final float W_DURATION_PROXY             = 0.05f;
    private static final float W_PREFERRED_TOPIC            = 25f;
    private static final float W_IMPLICIT_HASHTAG_STEP      = 4f;  // per completion-weighted watch
    private static final float W_IMPLICIT_HASHTAG_CAP       = 20f;
    private static final float PENALTY_HASHTAG_FATIGUE      = 12f;
    private static final float W_MEDIA_TYPE_AFFINITY        = 10f; // scaled by 0..1 completion rate
    private static final float W_FRESHNESS                  = 18f; // flat, decays to 0 by FRESHNESS_WINDOW_MS
    private static final long  FRESHNESS_WINDOW_MS           = 2L * 60 * 60 * 1000; // 2h
    private static final float W_DISCOVERY_NEW_CREATOR      = 8f;
    private static final float PENALTY_ALREADY_WATCHED      = 15f;

    /** Sentinel score for "Not Interested" hits - filtered out, never shown. */
    private static final float FILTERED = -1_000_000f;

    /** Default window for the creator-diversity pass: no repeated creator within N cards. */
    public static final int DEFAULT_DIVERSITY_WINDOW = 4;
    /** Default window for the media-type-diversity pass: no more than 2 of the same type in a row. */
    public static final int DEFAULT_MEDIA_TYPE_WINDOW = 2;
    /** Every Nth output slot is reserved for the best available discovery (unseen-creator) pick. */
    public static final int DEFAULT_EXPLORATION_INTERVAL = 5;

    /**
     * Scores a single reel against a user's ranking profile. Higher is
     * better. Returns a large negative sentinel for reels matching a
     * "Not Interested" topic - callers should treat any score at or below
     * that as "exclude from feed", which {@link #buildRankedFeed} already
     * does for you.
     */
    public static float score(@Nullable ReelModel reel, @Nullable RankingProfile profile) {
        if (reel == null) return FILTERED;

        // Log-scaled so a single viral reel (10k+ likes) doesn't mathematically
        // drown out every personalization signal below it - diminishing
        // returns on raw popularity, same principle real rankers use to keep
        // the feed from being "just a leaderboard".
        float engagementRecency = (float) Math.log1p(Math.max(0f, reel.trendingScore())) * W_ENGAGEMENT_LOG_SCALE;

        boolean isFollowed = profile != null && profile.followedUids != null
                && reel.uid != null && profile.followedUids.contains(reel.uid);
        float relationship = isFollowed ? W_FOLLOWED : 0f;

        int priorWatches = (profile != null && reel.uid != null)
                ? intOrZero(profile.creatorWatchCounts.get(reel.uid)) : 0;
        float creatorAffinity = Math.min(priorWatches * W_CREATOR_AFFINITY_STEP, W_CREATOR_AFFINITY_CAP);

        float completionSignal = clamp01(reel.avgCompletionRate) * W_COMPLETION_RATE;
        float watchTimeProxy = Math.min(reel.viewsCount, 8000) * W_VIEWS_PROXY
                + Math.min(reel.duration, 90) * W_DURATION_PROXY;

        float topicScore = 0f;
        if (reel.hashtags != null && profile != null) {
            for (String tag : reel.hashtags) {
                String norm = RankingProfile.normalizeTopic(tag);
                if (norm.isEmpty()) continue;
                if (profile.notInterestedTopics.contains(norm)) return FILTERED;
                if (profile.preferredTopics.contains(norm)) topicScore += W_PREFERRED_TOPIC;

                // Implicit signal, learned from watch behavior rather than an
                // explicit setting: tags the user tends to watch through
                // score positively (capped so one hyper-watched tag can't
                // dominate), tags they consistently bail on early are
                // penalized.
                Float implicitAffinity = profile.hashtagAffinity.get(norm);
                if (implicitAffinity != null) {
                    topicScore += Math.min(implicitAffinity * W_IMPLICIT_HASHTAG_STEP, W_IMPLICIT_HASHTAG_CAP);
                }
                if (profile.hashtagFatigue.contains(norm)) topicScore -= PENALTY_HASHTAG_FATIGUE;
            }
        }

        float mediaTypeScore = 0f;
        if (profile != null && reel.mediaType != null) {
            Float affinity = profile.mediaTypeAffinity.get(reel.mediaType);
            if (affinity != null) mediaTypeScore = affinity * W_MEDIA_TYPE_AFFINITY;
        }

        float freshness = freshnessBoost(reel.timestamp);

        float discovery = (priorWatches == 0 && !isFollowed) ? W_DISCOVERY_NEW_CREATOR : 0f;

        float repeatPenalty = (profile != null && reel.reelId != null
                && profile.highlyWatchedReelIds.contains(reel.reelId)) ? PENALTY_ALREADY_WATCHED : 0f;

        return engagementRecency + relationship + creatorAffinity + completionSignal
                + watchTimeProxy + topicScore + mediaTypeScore + freshness + discovery - repeatPenalty;
    }

    /** Linear decay from W_FRESHNESS at post-time to 0 at FRESHNESS_WINDOW_MS old. */
    private static float freshnessBoost(long timestamp) {
        long ageMs = System.currentTimeMillis() - timestamp;
        if (ageMs < 0 || ageMs >= FRESHNESS_WINDOW_MS) return 0f;
        return W_FRESHNESS * (1f - (ageMs / (float) FRESHNESS_WINDOW_MS));
    }

    /**
     * Builds the final feed order using the default windows: scores every
     * reel, drops "Not Interested" matches, sorts by score descending,
     * guarantees a discovery slot every {@link #DEFAULT_EXPLORATION_INTERVAL}
     * positions, then runs a two-dimensional diversity re-rank pass (creator
     * + media type). Prefer this over calling {@link #score} and sorting
     * manually.
     */
    @NonNull
    public static List<ReelModel> buildRankedFeed(@NonNull List<ReelModel> source,
                                                    @Nullable RankingProfile profile) {
        return buildRankedFeed(source, profile, DEFAULT_DIVERSITY_WINDOW,
                DEFAULT_MEDIA_TYPE_WINDOW, DEFAULT_EXPLORATION_INTERVAL);
    }

    @NonNull
    public static List<ReelModel> buildRankedFeed(@NonNull List<ReelModel> source,
                                                    @Nullable RankingProfile profile,
                                                    int creatorDiversityWindow,
                                                    int mediaTypeDiversityWindow,
                                                    int explorationInterval) {
        List<Scored> scored = new ArrayList<>(source.size());
        for (ReelModel r : source) {
            float s = score(r, profile);
            if (s <= FILTERED / 2f) continue; // "Not Interested" - excluded, not just demoted
            scored.add(new Scored(r, s));
        }
        scored.sort((a, b) -> Float.compare(b.score, a.score));
        List<Scored> withExploration = injectExplorationSlots(scored, profile, explorationInterval);
        return diversify(withExploration, creatorDiversityWindow, mediaTypeDiversityWindow);
    }

    /**
     * Epsilon-greedy exploration: guarantees that every {@code interval}-th
     * output position is filled by the best-scoring reel from a creator the
     * user has never watched (a "discovery" candidate), pulling it forward
     * from wherever it landed in the relevance sort. Without this, the
     * per-score discovery boost alone can still be out-scored by any
     * moderately popular post from a familiar creator, and new/small
     * creators would rarely surface even though the intent is to give them
     * real placements, not just better odds.
     *
     * No-op (returns the input unchanged) when there's no unseen-creator
     * candidate available, or fewer reels than one interval.
     */
    @NonNull
    private static List<Scored> injectExplorationSlots(List<Scored> ranked,
                                                         @Nullable RankingProfile profile,
                                                         int interval) {
        if (interval <= 0 || ranked.size() <= interval || profile == null) return ranked;

        LinkedList<Scored> remaining = new LinkedList<>(ranked);
        List<Scored> result = new ArrayList<>(ranked.size());
        int position = 0;

        while (!remaining.isEmpty()) {
            position++;
            Scored next;
            if (position % interval == 0) {
                next = pullBestDiscoveryCandidate(remaining, profile);
                if (next == null) next = remaining.removeFirst();
            } else {
                next = remaining.removeFirst();
            }
            result.add(next);
        }
        return result;
    }

    @Nullable
    private static Scored pullBestDiscoveryCandidate(LinkedList<Scored> pool, RankingProfile profile) {
        Iterator<Scored> it = pool.iterator();
        while (it.hasNext()) {
            Scored candidate = it.next();
            String uid = candidate.reel.uid;
            boolean neverWatched = uid != null && !profile.creatorWatchCounts.containsKey(uid);
            boolean notFollowed = uid == null || !profile.followedUids.contains(uid);
            if (neverWatched && notFollowed) {
                it.remove();
                return candidate;
            }
        }
        return null;
    }

    /**
     * Second-pass re-ranking across two dimensions: walks the score-sorted
     * list and greedily picks the next-highest-scoring reel whose creator
     * hasn't appeared in the last {@code creatorWindow} picks AND whose
     * media type hasn't appeared {@code mediaWindow} times in a row.
     * Relaxes the media-type constraint first if nothing satisfies both
     * (creator repetition is the more jarring problem), then falls back to
     * the single best remaining reel if nothing qualifies at all - so a feed
     * with very few distinct creators still renders everything instead of
     * stalling.
     */
    @NonNull
    private static List<ReelModel> diversify(List<Scored> ranked, int creatorWindow, int mediaWindow) {
        LinkedList<Scored> pool = new LinkedList<>(ranked);
        List<ReelModel> result = new ArrayList<>(pool.size());
        Deque<String> recentUids = new ArrayDeque<>();
        Deque<String> recentMediaTypes = new ArrayDeque<>();

        while (!pool.isEmpty()) {
            Scored pick = pickFirstSatisfying(pool, recentUids, recentMediaTypes, mediaWindow, true);
            if (pick == null) pick = pickFirstSatisfying(pool, recentUids, recentMediaTypes, mediaWindow, false);
            if (pick == null) pick = pool.removeFirst();

            result.add(pick.reel);
            recentUids.addLast(pick.reel.uid);
            if (recentUids.size() > creatorWindow) recentUids.removeFirst();

            String mediaType = pick.reel.mediaType;
            recentMediaTypes.addLast(mediaType);
            if (recentMediaTypes.size() > mediaWindow) recentMediaTypes.removeFirst();
        }
        return result;
    }

    /**
     * @param enforceMediaType when true, also requires the media-type window
     *                         constraint; when false, only the creator
     *                         constraint (used as the fallback relaxation).
     */
    @Nullable
    private static Scored pickFirstSatisfying(LinkedList<Scored> pool, Deque<String> recentUids,
                                               Deque<String> recentMediaTypes, int mediaWindow,
                                               boolean enforceMediaType) {
        boolean mediaWindowFull = enforceMediaType && recentMediaTypes.size() >= mediaWindow
                && allSame(recentMediaTypes);

        Iterator<Scored> it = pool.iterator();
        while (it.hasNext()) {
            Scored candidate = it.next();
            String uid = candidate.reel.uid;
            boolean uidOk = uid == null || !recentUids.contains(uid);
            if (!uidOk) continue;

            if (enforceMediaType && mediaWindowFull) {
                String type = candidate.reel.mediaType;
                String lastType = recentMediaTypes.peekLast();
                boolean sameAsStreak = type != null && type.equals(lastType);
                if (sameAsStreak) continue; // would extend an already-full same-media-type streak
            }
            it.remove();
            return candidate;
        }
        return null;
    }

    private static boolean allSame(Deque<String> values) {
        String first = null;
        boolean firstSet = false;
        for (String v : values) {
            if (!firstSet) { first = v; firstSet = true; continue; }
            if (!java.util.Objects.equals(first, v)) return false;
        }
        return true;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static int intOrZero(Integer v) { return v == null ? 0 : v; }

    private static class Scored {
        final ReelModel reel;
        final float score;
        Scored(ReelModel reel, float score) { this.reel = reel; this.score = score; }
    }
}
