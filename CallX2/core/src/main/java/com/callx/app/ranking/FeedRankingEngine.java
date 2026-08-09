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
 * FeedRankingEngine — single, shared ranking implementation used by both the
 * Reels tab (ReelsFragment For-You) and the Home tab (HomeFragment For-You).
 *
 * This is a client-side heuristic — there is no trained ML ranking model
 * here, and none of this is claimed to match Instagram's actual ranker byte
 * for byte. What it does do is combine the same *categories* of signal a
 * real production feed ranker combines, plus the same two-stage shape
 * (score everything → re-rank for diversity) real feeds use:
 *
 *   1. Engagement × recency decay   — ReelModel#trendingScore()
 *   2. Relationship / affinity      — is this creator followed, and how much
 *                                     has this user actually watched them
 *                                     before (real watch-history counts, not
 *                                     a guess)
 *   3. Watch-time / interest proxy  — avgCompletionRate (recorded on the
 *                                     reel from real viewer sessions) plus a
 *                                     views/duration proxy for reels that
 *                                     don't have completion data yet
 *   4. Topic personalization        — boosts hashtags the user opted into
 *                                     and hard-filters ones marked
 *                                     "Not Interested" (users/{uid}/feedSettings —
 *                                     previously collected but never applied)
 *   5. Discovery / exploration      — a small boost for creators the user
 *                                     has never watched, so the feed doesn't
 *                                     collapse into only ever showing the
 *                                     same handful of accounts
 *   6. Repeat suppression           — a small penalty for reels the user
 *                                     already watched to completion recently
 *   7. Diversity re-ranking         — a second pass over the sorted list so
 *                                     the same creator can't appear twice
 *                                     within a short window, even if their
 *                                     posts score highest overall (this is
 *                                     what stops one prolific/viral creator
 *                                     from dominating five cards in a row)
 */
public final class FeedRankingEngine {

    private FeedRankingEngine() {}

    // ── Tunable weights ──────────────────────────────────────────────────
    private static final float W_FOLLOWED               = 40f;
    private static final float W_CREATOR_AFFINITY_STEP   = 3f;   // per prior watch
    private static final float W_CREATOR_AFFINITY_CAP    = 30f;
    private static final float W_COMPLETION_RATE         = 20f;  // avgCompletionRate is 0..1
    private static final float W_VIEWS_PROXY              = 0.015f;
    private static final float W_DURATION_PROXY           = 0.05f;
    private static final float W_PREFERRED_TOPIC          = 25f;
    private static final float W_DISCOVERY_NEW_CREATOR    = 8f;
    private static final float PENALTY_ALREADY_WATCHED    = 15f;

    /** Sentinel score for "Not Interested" hits — filtered out, never shown. */
    private static final float FILTERED = -1_000_000f;

    /** Default window for the diversity pass: no repeated creator within N cards. */
    public static final int DEFAULT_DIVERSITY_WINDOW = 4;

    /**
     * Scores a single reel against a user's ranking profile. Higher is
     * better. Returns a large negative sentinel for reels matching a
     * "Not Interested" topic — callers should treat any score at or below
     * that as "exclude from feed", which {@link #buildRankedFeed} already
     * does for you.
     */
    public static float score(@Nullable ReelModel reel, @Nullable RankingProfile profile) {
        if (reel == null) return FILTERED;

        float engagementRecency = reel.trendingScore();

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
            }
        }

        float discovery = (priorWatches == 0 && !isFollowed) ? W_DISCOVERY_NEW_CREATOR : 0f;

        float repeatPenalty = (profile != null && reel.reelId != null
                && profile.highlyWatchedReelIds.contains(reel.reelId)) ? PENALTY_ALREADY_WATCHED : 0f;

        return engagementRecency + relationship + creatorAffinity + completionSignal
                + watchTimeProxy + topicScore + discovery - repeatPenalty;
    }

    /**
     * Builds the final feed order: scores every reel, drops "Not Interested"
     * matches, sorts by score descending, then runs a diversity re-rank pass
     * so the same creator doesn't cluster. This is the method feed screens
     * should call — prefer it over calling {@link #score} + sorting manually.
     */
    @NonNull
    public static List<ReelModel> buildRankedFeed(@NonNull List<ReelModel> source,
                                                    @Nullable RankingProfile profile) {
        return buildRankedFeed(source, profile, DEFAULT_DIVERSITY_WINDOW);
    }

    @NonNull
    public static List<ReelModel> buildRankedFeed(@NonNull List<ReelModel> source,
                                                    @Nullable RankingProfile profile,
                                                    int diversityWindow) {
        List<Scored> scored = new ArrayList<>(source.size());
        for (ReelModel r : source) {
            float s = score(r, profile);
            if (s <= FILTERED / 2f) continue; // "Not Interested" — excluded, not just demoted
            scored.add(new Scored(r, s));
        }
        scored.sort((a, b) -> Float.compare(b.score, a.score));
        return diversify(scored, diversityWindow);
    }

    /**
     * Second-pass re-ranking: walks the score-sorted list and greedily picks
     * the next-highest-scoring reel whose creator hasn't appeared in the
     * last {@code windowSize} picks. Falls back to the best remaining reel
     * (even if its creator just appeared) once nothing else qualifies, so a
     * feed with very few distinct creators still renders everything instead
     * of stalling. This mirrors the diversity/de-duplication re-ranking pass
     * real feed systems apply after the initial relevance sort.
     */
    @NonNull
    private static List<ReelModel> diversify(List<Scored> ranked, int windowSize) {
        LinkedList<Scored> pool = new LinkedList<>(ranked);
        List<ReelModel> result = new ArrayList<>(pool.size());
        Deque<String> recentUids = new ArrayDeque<>();

        while (!pool.isEmpty()) {
            Scored pick = null;
            Iterator<Scored> it = pool.iterator();
            while (it.hasNext()) {
                Scored candidate = it.next();
                String uid = candidate.reel.uid;
                if (uid == null || !recentUids.contains(uid)) {
                    pick = candidate;
                    it.remove();
                    break;
                }
            }
            if (pick == null) pick = pool.removeFirst(); // every remaining creator just appeared — take best anyway

            result.add(pick.reel);
            recentUids.addLast(pick.reel.uid);
            if (recentUids.size() > windowSize) recentUids.removeFirst();
        }
        return result;
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
