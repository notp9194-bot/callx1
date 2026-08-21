package com.callx.app.cache;

import android.util.Log;

import androidx.annotation.NonNull;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MutualFollowersCache — app-wide singleton cache for the reel bio
 * "Followed by X, Y and N others" row.
 *
 * WHY THIS EXISTS (PERF):
 * Before this cache, ReelSocialController#loadReelMutualFollowers() ran on
 * EVERY single reel bind (every time a reel becomes the active/visible one
 * — i.e. every swipe, including swiping back to a reel already seen this
 * session) and did, from scratch, every time:
 *   1. A full download of MY followers list
 *   2. A full download of MY following list           (chained, not parallel)
 *   3. A full download of the reel OWNER's followers list
 *   4. A full download of the reel OWNER's following list
 *   5. Up to 3 more single-value reads for mutual-friend name+photo
 * That's up to 7 Firebase reads — several of them full-node downloads —
 * PER SWIPE, even though (1)+(2) — "my network" — is IDENTICAL across every
 * reel in the session (it only changes when I follow/unfollow someone),
 * and (3)+(4)+(5) for a given reel owner don't meaningfully change between
 * two reels of theirs shown minutes apart in the same scroll session.
 *
 * This cache fixes that with two short-TTL, session-scoped, in-memory
 * layers:
 *   - myNetworkCache:  keyed by myUid           → my followers ∪ following
 *   - mutualCache:      keyed by "myUid|targetUid" → resolved mutual uids
 *   - profileMiniCache: keyed by uid            → name/photo for the small
 *                        avatar row (mutual friends repeat across many
 *                        different reel owners' rows, so this is reused
 *                        far beyond just one target uid)
 *
 * Revisiting an already-cached reel owner (swipe back, or two reels from
 * the same creator) now costs ZERO Firebase reads until the TTL expires.
 * A fresh reel owner still needs the same 4 underlying reads as before —
 * that part is inherent to the "list of mutuals" approach already used by
 * UserReelsActivity/FollowConnectionsActivity — but they're now fetched in
 * PARALLEL instead of serially chained, roughly halving latency, and (1)+(2)
 * are skipped entirely after the very first resolution in a session.
 */
public final class MutualFollowersCache {

    private static final String TAG = "MutualFollowersCache";
    private static MutualFollowersCache sInstance;

    // "My network" changes rarely (only on follow/unfollow) — a few minutes
    // of staleness is invisible to the user and saves a full-node re-fetch
    // on almost every reel swipe.
    private static final long MY_NETWORK_TTL_MS = 3 * 60_000L;
    // Target-specific mutual result — short enough that a stale cache never
    // meaningfully diverges from live data for this soft "social proof" UI.
    private static final long MUTUAL_RESULT_TTL_MS = 2 * 60_000L;
    // Name/photo rarely changes; a longer TTL is fine and lets a mutual
    // friend's avatar be reused across many different reel owners' rows.
    private static final long PROFILE_TTL_MS = 5 * 60_000L;

    private static final class NetworkEntry {
        final Set<String> network;
        final long fetchedAtMs;
        NetworkEntry(Set<String> network) { this.network = network; this.fetchedAtMs = System.currentTimeMillis(); }
        boolean isFresh() { return System.currentTimeMillis() - fetchedAtMs < MY_NETWORK_TTL_MS; }
    }

    private static final class MutualEntry {
        final List<String> uids;
        final long fetchedAtMs;
        MutualEntry(List<String> uids) { this.uids = uids; this.fetchedAtMs = System.currentTimeMillis(); }
        boolean isFresh() { return System.currentTimeMillis() - fetchedAtMs < MUTUAL_RESULT_TTL_MS; }
    }

    private static final class ProfileEntry {
        final String name;
        final String photo;
        final long fetchedAtMs;
        ProfileEntry(String name, String photo) { this.name = name; this.photo = photo; this.fetchedAtMs = System.currentTimeMillis(); }
        boolean isFresh() { return System.currentTimeMillis() - fetchedAtMs < PROFILE_TTL_MS; }
    }

    private final Map<String, NetworkEntry> myNetworkCache = new HashMap<>();
    private final Map<String, MutualEntry> mutualCache = new HashMap<>();
    private final Map<String, ProfileEntry> profileCache = new HashMap<>();
    // Collapses concurrent identical in-flight requests (e.g. two reels from
    // the same owner both becoming visible in quick succession while the
    // fetch is still pending) into a single Firebase round trip.
    private final Map<String, List<MutualResultCallback>> inFlightMutual = new HashMap<>();
    private final Map<String, List<MyNetworkCallback>> inFlightNetwork = new HashMap<>();

    public interface MyNetworkCallback {
        void onReady(@NonNull Set<String> myNetwork);
    }

    public interface MutualResultCallback {
        void onReady(@NonNull List<String> uids, @NonNull List<String> names, @NonNull List<String> photos);
    }

    private MutualFollowersCache() {}

    public static synchronized MutualFollowersCache getInstance() {
        if (sInstance == null) sInstance = new MutualFollowersCache();
        return sInstance;
    }

    /**
     * Call after a successful follow/unfollow so the next lookup reflects
     * it, instead of serving a stale "my network" (and any mutual result
     * derived from it) for up to MY_NETWORK_TTL_MS / MUTUAL_RESULT_TTL_MS.
     * Cheap — just drops the affected cache entries, no Firebase call.
     */
    public void invalidateMyNetwork(String myUid) {
        if (myUid == null) return;
        myNetworkCache.remove(myUid);
        String prefix = myUid + "|";
        java.util.Iterator<String> it = mutualCache.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) it.remove();
        }
    }

    // ── My network (followers ∪ following) ──────────────────────────────────

    public void getMyNetwork(@NonNull String myUid, @NonNull MyNetworkCallback callback) {
        NetworkEntry cached = myNetworkCache.get(myUid);
        if (cached != null && cached.isFresh()) {
            callback.onReady(cached.network);
            return;
        }

        List<MyNetworkCallback> waiters = inFlightNetwork.get(myUid);
        if (waiters != null) {
            waiters.add(callback);
            return;
        }
        waiters = new ArrayList<>();
        waiters.add(callback);
        inFlightNetwork.put(myUid, waiters);

        final Set<String> network = java.util.Collections.synchronizedSet(new HashSet<>());
        final int[] remaining = {2};

        ValueEventListener onEach = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot s : snap.getChildren()) {
                    if (s.getKey() != null) network.add(s.getKey());
                }
                finishOne();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Log.w(TAG, "getMyNetwork partial failure: " + e.getMessage());
                finishOne();
            }
            private void finishOne() {
                if (--remaining[0] > 0) return;
                NetworkEntry entry = new NetworkEntry(network);
                myNetworkCache.put(myUid, entry);
                List<MyNetworkCallback> pending = inFlightNetwork.remove(myUid);
                if (pending != null) {
                    for (MyNetworkCallback cb : pending) cb.onReady(entry.network);
                }
            }
        };

        // PERF: fetched in PARALLEL — the original implementation chained
        // these two full-node reads serially (followers, then followers-
        // callback triggers following), doubling the round-trip latency
        // for no reason since they're independent reads.
        FirebaseUtils.getReelFollowersRef(myUid).addListenerForSingleValueEvent(onEach);
        FirebaseUtils.getReelFollowsRef(myUid).addListenerForSingleValueEvent(onEach);
    }

    // ── Mutual followers for a specific target uid ───────────────────────────

    public void getMutualFollowers(@NonNull String myUid, @NonNull String targetUid, @NonNull MutualResultCallback callback) {
        if (myUid.equals(targetUid)) {
            callback.onReady(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            return;
        }
        String key = myUid + "|" + targetUid;
        MutualEntry cached = mutualCache.get(key);
        if (cached != null && cached.isFresh()) {
            resolveProfilesAndReturn(cached.uids, callback);
            return;
        }

        List<MutualResultCallback> waiters = inFlightMutual.get(key);
        if (waiters != null) {
            waiters.add(callback);
            return;
        }
        waiters = new ArrayList<>();
        waiters.add(callback);
        inFlightMutual.put(key, waiters);

        getMyNetwork(myUid, myNetwork -> resolveTargetNetwork(myUid, targetUid, key, myNetwork));
    }

    private void resolveTargetNetwork(String myUid, String targetUid, String key, Set<String> myNetwork) {
        final Set<String> targetNetwork = java.util.Collections.synchronizedSet(new HashSet<>());
        final int[] remaining = {2};

        ValueEventListener onEach = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot s : snap.getChildren()) {
                    if (s.getKey() != null) targetNetwork.add(s.getKey());
                }
                finishOne();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                finishOne();
            }
            private void finishOne() {
                if (--remaining[0] > 0) return;

                List<String> mutualUids = new ArrayList<>();
                for (String uid : targetNetwork) {
                    if (uid.equals(myUid)) continue;
                    if (myNetwork.contains(uid)) mutualUids.add(uid);
                }

                mutualCache.put(key, new MutualEntry(mutualUids));
                List<MutualResultCallback> pending = inFlightMutual.remove(key);
                if (pending != null) {
                    for (MutualResultCallback cb : pending) resolveProfilesAndReturn(mutualUids, cb);
                }
            }
        };

        // PERF: target's followers + following also fetched in PARALLEL
        // (was a serial followers→then-follows chain before).
        FirebaseUtils.getReelFollowersRef(targetUid).addListenerForSingleValueEvent(onEach);
        FirebaseUtils.getReelFollowsRef(targetUid).addListenerForSingleValueEvent(onEach);
    }

    // ── Name/photo resolution for the top-3 display avatars ─────────────────

    private void resolveProfilesAndReturn(List<String> mutualUids, MutualResultCallback callback) {
        if (mutualUids.isEmpty()) {
            callback.onReady(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            return;
        }
        int fetchCount = Math.min(3, mutualUids.size());
        List<String> names = new ArrayList<>(java.util.Collections.nCopies(fetchCount, (String) null));
        List<String> photos = new ArrayList<>(java.util.Collections.nCopies(fetchCount, (String) null));
        final int[] done = {0};

        for (int i = 0; i < fetchCount; i++) {
            final int index = i;
            String uid = mutualUids.get(i);
            ProfileEntry cached = profileCache.get(uid);
            if (cached != null && cached.isFresh()) {
                names.set(index, cached.name);
                photos.set(index, cached.photo);
                done[0]++;
                if (done[0] >= fetchCount) callback.onReady(mutualUids, names, photos);
                continue;
            }
            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot us) {
                    String n = us.child("name").getValue(String.class);
                    String thumb = us.child("thumbUrl").getValue(String.class);
                    String photo = us.child("photoUrl").getValue(String.class);
                    String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    String finalName = n != null ? n : "User";
                    String finalPhoto = p != null ? p : "";
                    profileCache.put(uid, new ProfileEntry(finalName, finalPhoto));
                    names.set(index, finalName);
                    photos.set(index, finalPhoto);
                    done[0]++;
                    if (done[0] >= fetchCount) callback.onReady(mutualUids, names, photos);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    names.set(index, "User");
                    photos.set(index, "");
                    done[0]++;
                    if (done[0] >= fetchCount) callback.onReady(mutualUids, names, photos);
                }
            });
        }
    }
}
