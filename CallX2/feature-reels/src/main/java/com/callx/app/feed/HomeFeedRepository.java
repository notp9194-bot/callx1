package com.callx.app.feed;

import com.callx.app.models.FeedPost;
import com.callx.app.models.FeedStory;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ReelFirebaseUtils;
import com.google.firebase.database.*;

import java.util.*;

/**
 * HomeFeedRepository — Firebase data layer for the Instagram-like Home Feed.
 *
 * Provides:
 *  - loadFeedPosts()       : paginated FYP or Following feed from Firebase RTDB
 *  - loadStories()         : stories bar data from status/ node
 *  - toggleLike()          : atomic like/unlike with count update
 *  - toggleSave()          : save/unsave post
 *  - toggleFollow()        : follow/unfollow user
 *  - loadLikeState()       : check if current user liked a post
 *  - loadSaveState()       : check if current user saved a post
 *  - loadFollowState()     : check if current user follows post owner
 *  - loadSuggestedUsers()  : suggested accounts to follow
 *
 * All callbacks run on Firebase's background thread; callers must
 * switch to the main thread (runOnUiThread / Handler) for UI updates.
 */
public class HomeFeedRepository {

    private static final int PAGE_SIZE = 12;

    // ── Callbacks ──────────────────────────────────────────────────────────

    public interface FeedCallback {
        void onLoaded(List<FeedPost> posts, boolean hasMore);
        void onError(String error);
    }

    public interface StoriesCallback {
        void onLoaded(List<FeedStory> stories);
        void onError(String error);
    }

    public interface BooleanCallback {
        void onResult(boolean value);
    }

    public interface SuggestedCallback {
        void onLoaded(List<String[]> users); // [uid, name, photoUrl, handle, followerCount]
    }

    // ── Feed loading ───────────────────────────────────────────────────────

    /**
     * Load FYP (For You) feed — all reels, sorted by timestamp desc, paginated.
     *
     * @param lastTimestamp  oldest timestamp from previous page (0 for first page)
     * @param callback       result callback
     */
    public void loadFypFeed(long lastTimestamp, String myUid, FeedCallback callback) {
        Query query = ReelFirebaseUtils.reelsRef()
                .orderByChild("timestamp");

        if (lastTimestamp > 0) {
            query = query.endBefore((double) lastTimestamp - 1, "timestamp");
        }
        query = query.limitToLast(PAGE_SIZE + 1);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                List<FeedPost> posts = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    try {
                        ReelModel r = child.getValue(ReelModel.class);
                        if (r == null) continue;
                        if (r.reelId == null) r.reelId = child.getKey();
                        if (r.uid != null && r.uid.equals(myUid)) continue; // skip own posts
                        posts.add(FeedPost.fromReelModel(r));
                    } catch (Exception ignored) {}
                }
                // Reverse (Firebase gives ascending, we want newest first)
                Collections.reverse(posts);
                boolean hasMore = posts.size() > PAGE_SIZE;
                if (hasMore) posts = posts.subList(0, PAGE_SIZE);
                callback.onLoaded(posts, hasMore);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                callback.onError(e.getMessage());
            }
        });
    }

    /**
     * Load Following feed — reels from accounts the current user follows.
     *
     * Steps:
     *  1. Read user_following/{myUid} → list of followed UIDs
     *  2. For each followed UID, read user_videos/{uid}/
     *  3. Merge, sort by timestamp desc, return paginated
     *
     * @param lastTimestamp  0 = first page
     */
    public void loadFollowingFeed(long lastTimestamp, String myUid, FeedCallback callback) {
        if (myUid == null || myUid.isEmpty()) {
            callback.onLoaded(Collections.emptyList(), false);
            return;
        }
        ReelFirebaseUtils.reelFollowingRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        List<String> followedUids = new ArrayList<>();
                        for (DataSnapshot child : snap.getChildren()) {
                            followedUids.add(child.getKey());
                        }
                        if (followedUids.isEmpty()) {
                            callback.onLoaded(Collections.emptyList(), false);
                            return;
                        }
                        loadFollowingReels(followedUids, lastTimestamp, PAGE_SIZE, callback);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {
                        callback.onError(e.getMessage());
                    }
                });
    }

    private void loadFollowingReels(List<String> uids, long lastTimestamp,
                                    int pageSize, FeedCallback callback) {
        final List<FeedPost> merged = Collections.synchronizedList(new ArrayList<>());
        final int[] pending = {uids.size()};

        for (String uid : uids) {
            Query q = ReelFirebaseUtils.userReelsRef(uid)
                    .orderByChild("timestamp")
                    .limitToLast(20);
            q.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) {
                        try {
                            ReelModel r = child.getValue(ReelModel.class);
                            if (r == null) continue;
                            if (r.reelId == null) r.reelId = child.getKey();
                            if (lastTimestamp > 0 && r.timestamp >= lastTimestamp) continue;
                            merged.add(FeedPost.fromReelModel(r));
                        } catch (Exception ignored) {}
                    }
                    synchronized (pending) {
                        pending[0]--;
                        if (pending[0] == 0) {
                            // All users loaded — sort by timestamp desc
                            merged.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                            boolean hasMore = merged.size() > pageSize;
                            List<FeedPost> page = hasMore
                                    ? new ArrayList<>(merged.subList(0, pageSize))
                                    : new ArrayList<>(merged);
                            callback.onLoaded(page, hasMore);
                        }
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    synchronized (pending) {
                        pending[0]--;
                        if (pending[0] == 0) {
                            merged.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                            callback.onLoaded(new ArrayList<>(merged), false);
                        }
                    }
                }
            });
        }
    }

    // ── Stories ────────────────────────────────────────────────────────────

    /**
     * Load stories for the stories bar.
     *
     * Reads contacts from users/{myUid}/contacts, then for each contact reads
     * status/{contactUid}/ to find active (< 24h) story items.
     * Also checks statusSeen/{myUid}/{contactUid} for the "seen" ring.
     */
    public void loadStories(String myUid, StoriesCallback callback) {
        if (myUid == null || myUid.isEmpty()) {
            callback.onLoaded(Collections.emptyList());
            return;
        }

        FirebaseUtils.getContactsRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        List<String> contactUids = new ArrayList<>();
                        for (DataSnapshot c : snap.getChildren()) {
                            contactUids.add(c.getKey());
                        }
                        if (contactUids.isEmpty()) {
                            // Still load from reels users (people they follow)
                            loadStoriesFromFollowing(myUid, callback);
                            return;
                        }
                        loadStoriesForUids(contactUids, myUid, callback);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {
                        callback.onLoaded(Collections.emptyList());
                    }
                });
    }

    private void loadStoriesFromFollowing(String myUid, StoriesCallback callback) {
        ReelFirebaseUtils.reelFollowingRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        List<String> uids = new ArrayList<>();
                        for (DataSnapshot c : snap.getChildren()) uids.add(c.getKey());
                        if (uids.isEmpty()) { callback.onLoaded(Collections.emptyList()); return; }
                        loadStoriesForUids(uids, myUid, callback);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {
                        callback.onLoaded(Collections.emptyList());
                    }
                });
    }

    private void loadStoriesForUids(List<String> uids, String myUid, StoriesCallback callback) {
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        final List<FeedStory> result = Collections.synchronizedList(new ArrayList<>());
        final int[] pending = {uids.size()};

        for (String uid : uids) {
            FirebaseUtils.getStatusRef().child(uid)
                    .orderByChild("timestamp")
                    .startAt((double) cutoff)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snap) {
                            if (snap.getChildrenCount() > 0) {
                                // Has active story items — load user profile
                                long latest = 0;
                                for (DataSnapshot item : snap.getChildren()) {
                                    Long ts = item.child("timestamp").getValue(Long.class);
                                    if (ts != null && ts > latest) latest = ts;
                                }
                                final long latestTs = latest;
                                ReelFirebaseUtils.reelUserRef(uid)
                                        .addListenerForSingleValueEvent(new ValueEventListener() {
                                            @Override
                                            public void onDataChange(@NonNull DataSnapshot userSnap) {
                                                String name  = getStr(userSnap, "displayName");
                                                String photo = getStr(userSnap, "photoUrl");
                                                // Check seen state
                                                checkSeenState(myUid, uid, hasUnseen -> {
                                                    FeedStory story = new FeedStory(
                                                            uid, name, photo,
                                                            hasUnseen, latestTs, false);
                                                    story.itemCount = (int) snap.getChildrenCount();
                                                    result.add(story);
                                                    doneOne(pending, result, callback);
                                                });
                                            }
                                            @Override
                                            public void onCancelled(@NonNull DatabaseError e) {
                                                doneOne(pending, result, callback);
                                            }
                                        });
                            } else {
                                doneOne(pending, result, callback);
                            }
                        }
                        @Override
                        public void onCancelled(@NonNull DatabaseError e) {
                            doneOne(pending, result, callback);
                        }
                    });
        }
    }

    private void checkSeenState(String myUid, String ownerUid, BooleanCallback cb) {
        FirebaseUtils.db().getReference("statusSeen")
                .child(myUid).child(ownerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        // hasUnseen = NOT fully seen
                        Boolean seen = snap.getValue(Boolean.class);
                        cb.onResult(seen == null || !seen);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {
                        cb.onResult(true); // assume unseen on error
                    }
                });
    }

    private synchronized void doneOne(int[] pending, List<FeedStory> result, StoriesCallback cb) {
        synchronized (pending) {
            pending[0]--;
            if (pending[0] == 0) {
                result.sort(FeedStory.SORT_ORDER);
                cb.onLoaded(new ArrayList<>(result));
            }
        }
    }

    // ── Like / Save / Follow ───────────────────────────────────────────────

    /** Toggle like for a post. Returns new like state via callback. */
    public void toggleLike(String reelId, String myUid, boolean currentlyLiked,
                           BooleanCallback callback) {
        DatabaseReference likeRef = ReelFirebaseUtils.reelLikesRef(reelId).child(myUid);
        DatabaseReference countRef = ReelFirebaseUtils.reelRef(reelId).child("likesCount");
        DatabaseReference userLikedRef = ReelFirebaseUtils.userLikedReelsRef(myUid).child(reelId);

        if (currentlyLiked) {
            likeRef.removeValue();
            userLikedRef.removeValue();
            countRef.runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long v = d.getValue(Long.class);
                    d.setValue(v == null ? 0 : Math.max(0, v - 1));
                    return Transaction.success(d);
                }
                @Override
                public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) {
                    callback.onResult(false);
                }
            });
        } else {
            likeRef.setValue(true);
            userLikedRef.setValue(System.currentTimeMillis());
            countRef.runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long v = d.getValue(Long.class);
                    d.setValue(v == null ? 1 : v + 1);
                    return Transaction.success(d);
                }
                @Override
                public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) {
                    callback.onResult(true);
                }
            });
        }
    }

    /** Toggle save for a post. */
    public void toggleSave(String reelId, String myUid, boolean currentlySaved,
                           BooleanCallback callback) {
        DatabaseReference saveRef   = ReelFirebaseUtils.reelSavesRef(reelId).child(myUid);
        DatabaseReference userSaved = ReelFirebaseUtils.userSavedReelsRef(myUid).child(reelId);
        if (currentlySaved) {
            saveRef.removeValue();
            userSaved.removeValue();
            callback.onResult(false);
        } else {
            saveRef.setValue(true);
            userSaved.setValue(System.currentTimeMillis());
            callback.onResult(true);
        }
    }

    /** Toggle follow for a user. */
    public void toggleFollow(String targetUid, String myUid, boolean currentlyFollowing,
                             BooleanCallback callback) {
        DatabaseReference followersRef = ReelFirebaseUtils.reelFollowersRef(targetUid).child(myUid);
        DatabaseReference followingRef = ReelFirebaseUtils.reelFollowingRef(myUid).child(targetUid);
        DatabaseReference fCountRef    = ReelFirebaseUtils.reelUserRef(targetUid).child("followerCount");
        DatabaseReference gCountRef    = ReelFirebaseUtils.reelUserRef(myUid).child("followingCount");

        if (currentlyFollowing) {
            followersRef.removeValue();
            followingRef.removeValue();
            adjustCount(fCountRef, -1);
            adjustCount(gCountRef, -1);
            callback.onResult(false);
        } else {
            followersRef.setValue(true);
            followingRef.setValue(true);
            adjustCount(fCountRef, 1);
            adjustCount(gCountRef, 1);
            callback.onResult(true);
        }
    }

    private void adjustCount(DatabaseReference ref, int delta) {
        ref.runTransaction(new Transaction.Handler() {
            @NonNull @Override
            public Transaction.Result doTransaction(@NonNull MutableData d) {
                Long v = d.getValue(Long.class);
                d.setValue(v == null ? Math.max(0, delta) : Math.max(0, v + delta));
                return Transaction.success(d);
            }
            @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
        });
    }

    // ── State loaders ──────────────────────────────────────────────────────

    public void loadLikeState(String reelId, String myUid, BooleanCallback cb) {
        ReelFirebaseUtils.reelLikesRef(reelId).child(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) { cb.onResult(s.exists()); }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(false); }
                });
    }

    public void loadSaveState(String reelId, String myUid, BooleanCallback cb) {
        ReelFirebaseUtils.reelSavesRef(reelId).child(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) { cb.onResult(s.exists()); }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(false); }
                });
    }

    public void loadFollowState(String targetUid, String myUid, BooleanCallback cb) {
        ReelFirebaseUtils.reelFollowersRef(targetUid).child(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) { cb.onResult(s.exists()); }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResult(false); }
                });
    }

    // ── Suggested users ────────────────────────────────────────────────────

    public void loadSuggestedUsers(String myUid, int limit, SuggestedCallback callback) {
        ReelFirebaseUtils.reelUsersRef()
                .orderByChild("followerCount")
                .limitToLast(limit + 5)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        List<String[]> users = new ArrayList<>();
                        for (DataSnapshot child : snap.getChildren()) {
                            String uid = child.getKey();
                            if (uid == null || uid.equals(myUid)) continue;
                            String name    = getStr(child, "displayName");
                            String photo   = getStr(child, "photoUrl");
                            String handle  = getStr(child, "handle");
                            Long   fc      = child.child("followerCount").getValue(Long.class);
                            String fcStr   = fc != null ? formatCount(fc) + " followers" : "";
                            users.add(new String[]{uid, name, photo, handle, fcStr});
                            if (users.size() >= limit) break;
                        }
                        Collections.reverse(users); // highest follower count first
                        callback.onLoaded(users);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {
                        callback.onLoaded(Collections.emptyList());
                    }
                });
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String getStr(DataSnapshot snap, String key) {
        Object v = snap.child(key).getValue();
        return v != null ? v.toString() : "";
    }

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(java.util.Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }
}
