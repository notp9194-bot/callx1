package com.callx.app.utils;

import com.callx.app.models.XUser;
import com.google.firebase.database.*;
import java.util.*;

/**
 * Computes "Who to follow" suggestions:
 *   1. Get all UIDs the current user already follows (my_following).
 *   2. For each followee, fetch their following list → collect second-degree UIDs.
 *   3. Count how many of my followees follow each second-degree UID (mutual count).
 *   4. Exclude: myself, anyone I already follow.
 *   5. Sort descending by mutual count, return top N.
 */
public class XWhoToFollowManager {

    public interface SuggestionsCallback {
        void onResult(List<SuggestedUser> suggestions);
    }

    public static class SuggestedUser {
        public XUser user;
        public int   mutualCount;
        public boolean isFollowing;
        public SuggestedUser(XUser user, int mutualCount) {
            this.user = user; this.mutualCount = mutualCount;
        }
    }

    private static final int MAX_SUGGESTIONS  = 8;
    private static final int MAX_FOLLOWEE_SCAN = 20; // limit scanning to first N followees for perf

    public static void getSuggestions(String myUid, SuggestionsCallback callback) {
        if (myUid == null || myUid.isEmpty()) { callback.onResult(Collections.emptyList()); return; }

        // Step 1: get my following list
        XFirebaseUtils.userFollowingRef(myUid).limitToFirst(MAX_FOLLOWEE_SCAN)
            .get().addOnSuccessListener(myFollowingSnap -> {

            Set<String> myFollowing = new HashSet<>();
            for (DataSnapshot ds : myFollowingSnap.getChildren()) {
                if (ds.getKey() != null) myFollowing.add(ds.getKey());
            }

            if (myFollowing.isEmpty()) {
                // No following → fall back to popular users
                loadPopularUsers(myUid, callback);
                return;
            }

            // Step 2: for each followee, get their following list
            // mutual[uid] = how many of MY followees also follow that uid
            Map<String, Integer> mutualCount = new HashMap<>();
            long[] pending = { myFollowing.size() };

            for (String followeeUid : myFollowing) {
                XFirebaseUtils.userFollowingRef(followeeUid).limitToFirst(50)
                    .get().addOnSuccessListener(theirSnap -> {
                    for (DataSnapshot ds : theirSnap.getChildren()) {
                        String candidate = ds.getKey();
                        if (candidate == null) continue;
                        if (candidate.equals(myUid)) continue;          // skip self
                        if (myFollowing.contains(candidate)) continue;  // skip already following
                        mutualCount.put(candidate,
                            mutualCount.getOrDefault(candidate, 0) + 1);
                    }
                    pending[0]--;
                    if (pending[0] <= 0) {
                        // Step 3: rank by mutual count, take top N
                        resolveUsers(myUid, mutualCount, MAX_SUGGESTIONS, callback);
                    }
                }).addOnFailureListener(e -> {
                    pending[0]--;
                    if (pending[0] <= 0)
                        resolveUsers(myUid, mutualCount, MAX_SUGGESTIONS, callback);
                });
            }
        }).addOnFailureListener(e -> callback.onResult(Collections.emptyList()));
    }

    /** Fetch XUser objects for the top candidates and return sorted list */
    private static void resolveUsers(String myUid, Map<String, Integer> mutualCount,
                                     int maxCount, SuggestionsCallback callback) {
        if (mutualCount.isEmpty()) {
            loadPopularUsers(myUid, callback);
            return;
        }

        // Sort UIDs by mutual count descending
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(mutualCount.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> topUids = new ArrayList<>();
        for (int i = 0; i < Math.min(maxCount, sorted.size()); i++)
            topUids.add(sorted.get(i).getKey());

        List<SuggestedUser> results = new ArrayList<>();
        long[] pending = { topUids.size() };
        for (String uid : topUids) {
            XFirebaseUtils.xUserRef(uid).get().addOnSuccessListener(snap -> {
                XUser u = snap.getValue(XUser.class);
                if (u != null) {
                    u.uid = snap.getKey();
                    u.ensureMapsNotNull();
                    int mc = mutualCount.getOrDefault(uid, 0);
                    results.add(new SuggestedUser(u, mc));
                }
                pending[0]--;
                if (pending[0] <= 0) {
                    results.sort((a, b) -> Integer.compare(b.mutualCount, a.mutualCount));
                    callback.onResult(results);
                }
            }).addOnFailureListener(e -> {
                pending[0]--;
                if (pending[0] <= 0) {
                    results.sort((a, b) -> Integer.compare(b.mutualCount, a.mutualCount));
                    callback.onResult(results);
                }
            });
        }
    }

    /** Fallback: when user has no following, suggest users with most followers */
    private static void loadPopularUsers(String myUid, SuggestionsCallback callback) {
        XFirebaseUtils.root_x_users()
            .orderByChild("followerCount")
            .limitToLast(10)
            .get().addOnSuccessListener(snap -> {
            List<SuggestedUser> results = new ArrayList<>();
            for (DataSnapshot ds : snap.getChildren()) {
                XUser u = ds.getValue(XUser.class);
                if (u == null || myUid.equals(ds.getKey())) continue;
                u.uid = ds.getKey();
                u.ensureMapsNotNull();
                results.add(new SuggestedUser(u, 0));
            }
            // Sort by follower count descending
            results.sort((a, b) -> Long.compare(b.user.followerCount, a.user.followerCount));
            if (results.size() > MAX_SUGGESTIONS) results = results.subList(0, MAX_SUGGESTIONS);
            callback.onResult(results);
        }).addOnFailureListener(e -> callback.onResult(Collections.emptyList()));
    }
}
