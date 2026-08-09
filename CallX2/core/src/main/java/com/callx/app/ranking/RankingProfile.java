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
 *
 * Both feedSettings fields already existed (set via ReelFeedSettingsActivity)
 * but were never actually read by the ranking code before this — wiring them
 * in is part of what makes this pass "comprehensive" rather than a re-skin
 * of the old trendingScore()-only sort.
 */
public class RankingProfile {

    public Set<String> followedUids = new HashSet<>();
    public final Map<String, Integer> creatorWatchCounts = new HashMap<>();
    public final Set<String> highlyWatchedReelIds = new HashSet<>();
    public final Set<String> notInterestedTopics = new HashSet<>();
    public final Set<String> preferredTopics = new HashSet<>();

    private static final int WATCH_HISTORY_SAMPLE = 300;

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
                        Integer pct = s.child("percentWatched").getValue(Integer.class);
                        if (pct != null && pct >= 90) {
                            String reelId = s.getKey();
                            if (reelId != null) profile.highlyWatchedReelIds.add(reelId);
                        }
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
