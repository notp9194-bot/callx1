package com.callx.app.ranking;

import androidx.annotation.NonNull;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RankingProfile — one user's personalization signals for a single feed
 * render, shared by both the Reels tab (ReelsFragment) and the Home tab
 * (HomeFragment) so their ranking logic stays a single source of truth
 * instead of two independently-drifting copies.
 *
 * Pulled fresh (not cached across app sessions) each time a feed is
 * (re)loaded — cheap because it's 2 small, already-indexed Firebase reads
 * run in parallel with the follow-set fetch the feed screens already do.
 *
 * Signals captured:
 *  - followedUids          — who the user follows (relationship signal)
 *  - creatorWatchCounts     — how many times the user has watched each
 *                             creator's content recently (creator affinity —
 *                             derived from real watchHistory data, not a proxy)
 *  - highlyWatchedReelIds   — reels the user already watched to ≥90% —
 *                             mildly deprioritized so a full feed reload
 *                             doesn't keep resurfacing the same clip
 *  - notInterestedTopics    — hashtags/topics from users/{uid}/feedSettings
 *                             the user explicitly marked "Not Interested" —
 *                             filters matching reels out of the feed entirely
 *  - preferredTopics        — hashtags/topics the user opted into — boosts
 *                             matching reels
 *  - hashtagAffinity        — IMPLICIT per-hashtag interest score, learned
 *                             from actual watch behavior (not something the
 *                             user had to set): each watch-history entry
 *                             contributes its completion % to every hashtag
 *                             on that reel, so tags from reels the user
 *                             watches all the way through count for more
 *                             than tags from reels they skipped early
 *  - hashtagFatigue         — the flip side: tags the user has repeatedly
 *                             bailed out of early (avg completion < 15%
 *                             across ≥3 watches) — a soft, self-correcting
 *                             negative signal distinct from the explicit
 *                             "Not Interested" list
 *  - mediaTypeAffinity      — implicit preference between "video" and
 *                             "photo_slideshow" content, from the same
 *                             completion-weighted watch history
 *
 * Both feedSettings fields already existed (set via ReelFeedSettingsActivity)
 * but were never actually read by the ranking code before this — wiring them
 * in, alongside the new implicit signals above, is what makes this pass
 * "comprehensive" rather than a re-skin of the old trendingScore()-only sort.
 */
public class RankingProfile {

    public Set<String> followedUids = new HashSet<>();
    public final Map<String, Integer> creatorWatchCounts = new HashMap<>();
    public final Set<String> highlyWatchedReelIds = new HashSet<>();
    public final Set<String> notInterestedTopics = new HashSet<>();
    public final Set<String> preferredTopics = new HashSet<>();
    public final Map<String, Float> hashtagAffinity = new HashMap<>();
    public final Set<String> hashtagFatigue = new HashSet<>();
    public final Map<String, Float> mediaTypeAffinity = new HashMap<>();

    private static final int WATCH_HISTORY_SAMPLE = 300;
    private static final int FATIGUE_MIN_SAMPLES = 3;
    private static final float FATIGUE_COMPLETION_THRESHOLD = 0.15f;

    public interface Listener {
        void onReady(@NonNull RankingProfile profile);
    }

    /**
     * Loads the personalization signals for {@code myUid}. {@code followedUids}
     * is passed in (rather than re-fetched) because the calling feed screen
     * already needs that set for other purposes (follow-button state, etc.) —
     * this avoids a duplicate Firebase read.
     *
     * Safe to call with a null uid (logged-out / anonymous browsing) — resolves
     * immediately with an empty profile so callers don't need a separate branch.
     */
    public static void load(String myUid, Set<String> followedUids, @NonNull Listener listener) {
        RankingProfile profile = new RankingProfile();
        profile.followedUids = followedUids != null ? followedUids : new HashSet<>();

        if (myUid == null || myUid.isEmpty()) {
            listener.onReady(profile);
            return;
        }

        // Two independent reads — resolve once both come back (or fail).
        AtomicInteger pending = new AtomicInteger(2);
        Runnable maybeFinish = () -> {
            if (pending.decrementAndGet() == 0) listener.onReady(profile);
        };

        // Per-tag/per-mediaType running totals used to derive hashtagFatigue
        // and mediaTypeAffinity once the whole sample has been scanned.
        Map<String, Integer> tagSampleCount = new HashMap<>();
        Map<String, Float>   tagCompletionSum = new HashMap<>();
        Map<String, Integer> mediaSampleCount = new HashMap<>();
        Map<String, Float>   mediaCompletionSum = new HashMap<>();

        FirebaseUtils.db().getReference("watchHistory").child(myUid)
            .orderByChild("watchedAtMs")
            .limitToLast(WATCH_HISTORY_SAMPLE)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) {
                        String ownerUid = s.child("ownerUid").getValue(String.class);
                        if (ownerUid != null && !ownerUid.isEmpty()) {
                            profile.creatorWatchCounts.merge(ownerUid, 1, Integer::sum);
                        }
                        Integer pctObj = s.child("percentWatched").getValue(Integer.class);
                        int pct = pctObj != null ? pctObj : 0;
                        float completion = Math.max(0f, Math.min(100f, pct)) / 100f;

                        if (pct >= 90) {
                            String reelId = s.getKey();
                            if (reelId != null) profile.highlyWatchedReelIds.add(reelId);
                        }

                        for (DataSnapshot h : s.child("hashtags").getChildren()) {
                            String tag = normalizeTopic(h.getValue(String.class));
                            if (tag.isEmpty()) continue;
                            profile.hashtagAffinity.merge(tag, completion, Float::sum);
                            tagSampleCount.merge(tag, 1, Integer::sum);
                            tagCompletionSum.merge(tag, completion, Float::sum);
                        }

                        String mediaType = s.child("mediaType").getValue(String.class);
                        if (mediaType != null && !mediaType.isEmpty()) {
                            mediaSampleCount.merge(mediaType, 1, Integer::sum);
                            mediaCompletionSum.merge(mediaType, completion, Float::sum);
                        }
                    }

                    for (Map.Entry<String, Integer> e : tagSampleCount.entrySet()) {
                        String tag = e.getKey();
                        int count = e.getValue();
                        if (count < FATIGUE_MIN_SAMPLES) continue;
                        float avgCompletion = tagCompletionSum.getOrDefault(tag, 0f) / count;
                        if (avgCompletion < FATIGUE_COMPLETION_THRESHOLD) profile.hashtagFatigue.add(tag);
                    }
                    for (Map.Entry<String, Integer> e : mediaSampleCount.entrySet()) {
                        String type = e.getKey();
                        int count = e.getValue();
                        float avgCompletion = mediaCompletionSum.getOrDefault(type, 0f) / count;
                        profile.mediaTypeAffinity.put(type, avgCompletion);
                    }

                    maybeFinish.run();
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) { maybeFinish.run(); }
            });

        FirebaseUtils.getUserRef(myUid).child("feedSettings")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot t : snap.child("notInterested").getChildren()) {
                        String v = t.getValue(String.class);
                        if (v != null) profile.notInterestedTopics.add(normalizeTopic(v));
                    }
                    for (DataSnapshot t : snap.child("preferredTopics").getChildren()) {
                        String v = t.getValue(String.class);
                        if (v != null) profile.preferredTopics.add(normalizeTopic(v));
                    }
                    maybeFinish.run();
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) { maybeFinish.run(); }
            });
    }

    static String normalizeTopic(String topic) {
        if (topic == null) return "";
        return topic.toLowerCase(Locale.ROOT).replace("#", "").trim();
    }
}
