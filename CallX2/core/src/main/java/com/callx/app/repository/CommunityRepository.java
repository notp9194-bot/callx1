package com.callx.app.repository;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.callx.app.community.CommunityPoll;
import com.callx.app.community.CommunityRole;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.CommunityDao;
import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.db.entity.CommunityGroupLinkEntity;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.utils.Constants;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CommunityRepository — offline-first (Room cache) + Firebase RTDB source
 * of truth for the Community system, mirroring ChatRepository's strategy:
 *   1. Serve from local Room cache immediately.
 *   2. Write-through to Firebase, then update Room from the same write.
 *   3. Long-lived Firebase listeners keep Room (and therefore any
 *      LiveData/observeX() the UI is watching) fresh in the background.
 *
 * Firebase layout (Realtime Database):
 *   communities/{communityId}                       -> CommunityEntity fields
 *   communities/{communityId}/members/{uid}          -> {name, photoUrl, role, joinedAt}
 *   communities/{communityId}/groups/{groupId}       -> {addedByUid, addedAt}
 *   communities/{communityId}/posts/{postId}         -> CommunityPostEntity fields (+ poll/, likes/, comments/)
 *   community_by_owner/{ownerUid}                     -> communityId   (opt-in existence lookup)
 *
 * All public methods are safe to call from the main thread — internal Room
 * work always runs on mExecutor, Firebase calls are already async.
 */
public class CommunityRepository {

    private static final String TAG = "CommunityRepository";
    private static final int PAGE_SIZE = 20;

    private static volatile CommunityRepository sInstance;

    private final AppDatabase mDb;
    private final CommunityDao mDao;
    private final ExecutorService mExecutor;
    private final FirebaseDatabase mFirebase;

    public interface SimpleCallback {
        void onComplete(boolean success, @Nullable String error);
    }

    public interface ResultCallback<T> {
        void onResult(@Nullable T value);
    }

    private CommunityRepository(Context ctx) {
        mDb = AppDatabase.getInstance(ctx);
        mDao = mDb.communityDao();
        mExecutor = Executors.newFixedThreadPool(3);
        mFirebase = FirebaseDatabase.getInstance(Constants.DB_URL);
    }

    public static CommunityRepository getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (CommunityRepository.class) {
                if (sInstance == null) sInstance = new CommunityRepository(ctx.getApplicationContext());
            }
        }
        return sInstance;
    }

    private DatabaseReference communitiesRef() {
        return mFirebase.getReference("communities");
    }

    private DatabaseReference ownerIndexRef() {
        return mFirebase.getReference("community_by_owner");
    }

    // ─────────────────────────────────────────────────────────────
    // EXISTENCE / OWNERSHIP — "does this contact have a Community?"
    // ─────────────────────────────────────────────────────────────

    /**
     * Checks whether ownerUid has an enabled Community. Room cache answers
     * first (offline / instant for the chat header card), then Firebase's
     * community_by_owner index confirms/refreshes it — this index exists
     * specifically so the chat header doesn't have to scan every community.
     */
    public void checkHasCommunity(String ownerUid, ResultCallback<String> cb) {
        if (ownerUid == null || ownerUid.isEmpty()) { cb.onResult(null); return; }
        mExecutor.execute(() -> {
            String cachedId = mDao.getCommunityIdByOwnerSync(ownerUid);
            if (cachedId != null) cb.onResult(cachedId);

            ownerIndexRef().child(ownerUid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    String id = snapshot.getValue(String.class);
                    if (id == null && cachedId == null) { cb.onResult(null); return; }
                    if (id != null && !id.equals(cachedId)) {
                        fetchCommunity(id, community -> { /* refreshes Room via fetchCommunity */ });
                        cb.onResult(id);
                    }
                }
                @Override public void onCancelled(DatabaseError error) {
                    Log.w(TAG, "checkHasCommunity cancelled: " + error.getMessage());
                }
            });
        });
    }

    public LiveData<CommunityEntity> observeCommunity(String communityId) {
        return mDao.observeCommunity(communityId);
    }

    public void fetchCommunity(String communityId, ResultCallback<CommunityEntity> cb) {
        communitiesRef().child(communityId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                if (!s.exists()) { cb.onResult(null); return; }
                CommunityEntity c = new CommunityEntity();
                c.id = communityId;
                c.name = s.child("name").getValue(String.class);
                c.description = s.child("description").getValue(String.class);
                c.iconUrl = s.child("iconUrl").getValue(String.class);
                c.ownerUid = s.child("ownerUid").getValue(String.class);
                c.memberCount = longOrZero(s.child("memberCount"));
                c.groupCount = longOrZero(s.child("groupCount"));
                c.postCount = longOrZero(s.child("postCount"));
                c.createdAt = longOrZero(s.child("createdAt"));
                mExecutor.execute(() -> mDao.insertCommunity(c));
                cb.onResult(c);
            }
            @Override public void onCancelled(DatabaseError error) { cb.onResult(null); }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // CREATE / MANAGE
    // ─────────────────────────────────────────────────────────────

    /**
     * Opt-in creation: a user has NO Community until they explicitly call
     * this. Fails (via cb) if ownerUid already owns one — a user gets at
     * most one Community, matching the scope agreed with the user.
     */
    public void createCommunity(String ownerUid, String ownerName, String ownerPhoto,
                                 String name, String description, String iconUrl,
                                 ResultCallback<String> cb) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("name", name);
        data.put("description", description != null ? description : "");
        data.put("iconUrl", iconUrl != null ? iconUrl : "");
        data.put("ownerUid", ownerUid);
        data.put("memberCount", 1);
        data.put("groupCount", 0);
        data.put("postCount", 0);
        data.put("createdAt", now);

        Map<String, Object> ownerMember = new HashMap<>();
        ownerMember.put("name", ownerName);
        ownerMember.put("photoUrl", ownerPhoto);
        ownerMember.put("role", CommunityRole.OWNER);
        ownerMember.put("joinedAt", now);
        data.put("members", new HashMap<String, Object>() {{ put(ownerUid, ownerMember); }});

        communitiesRef().child(id).setValue(data, (error, ref) -> {
            if (error != null) { cb.onResult(null); return; }
            ownerIndexRef().child(ownerUid).setValue(id);

            CommunityEntity entity = new CommunityEntity();
            entity.id = id; entity.name = name; entity.description = description;
            entity.iconUrl = iconUrl; entity.ownerUid = ownerUid; entity.memberCount = 1;
            entity.createdAt = now;

            CommunityMemberEntity member = new CommunityMemberEntity();
            member.communityId = id; member.uid = ownerUid; member.name = ownerName;
            member.photoUrl = ownerPhoto; member.role = CommunityRole.OWNER; member.joinedAt = now;

            mExecutor.execute(() -> {
                mDao.insertCommunity(entity);
                mDao.insertMember(member);
            });
            cb.onResult(id);
        });
    }

    public void updateCommunityInfo(String communityId, String name, String description,
                                     String iconUrl, SimpleCallback cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("name", name);
        updates.put("description", description);
        if (iconUrl != null) updates.put("iconUrl", iconUrl);
        communitiesRef().child(communityId).updateChildren(updates, (error, ref) -> {
            boolean ok = error == null;
            if (ok) {
                mExecutor.execute(() -> {
                    CommunityEntity c = mDao.getCommunitySync(communityId);
                    if (c != null) {
                        c.name = name; c.description = description;
                        if (iconUrl != null) c.iconUrl = iconUrl;
                        mDao.insertCommunity(c);
                    }
                });
            }
            cb.onComplete(ok, error != null ? error.getMessage() : null);
        });
    }

    /** Owner-only: deletes the Community entirely (does NOT delete the linked group chats themselves). */
    public void disableCommunity(String communityId, String ownerUid, SimpleCallback cb) {
        communitiesRef().child(communityId).removeValue((error, ref) -> {
            boolean ok = error == null;
            if (ok) {
                ownerIndexRef().child(ownerUid).removeValue();
                mExecutor.execute(() -> mDao.deleteCommunity(communityId));
            }
            cb.onComplete(ok, error != null ? error.getMessage() : null);
        });
    }

    // ─────────────────────────────────────────────────────────────
    // MEMBERS
    // ─────────────────────────────────────────────────────────────

    public LiveData<List<CommunityMemberEntity>> observeMembers(String communityId) {
        return mDao.observeMembers(communityId);
    }

    public void addMember(String communityId, String uid, String name, String photoUrl, SimpleCallback cb) {
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("photoUrl", photoUrl);
        data.put("role", CommunityRole.MEMBER);
        data.put("joinedAt", now);

        communitiesRef().child(communityId).child("members").child(uid).setValue(data, (error, ref) -> {
            boolean ok = error == null;
            if (ok) {
                CommunityMemberEntity member = new CommunityMemberEntity();
                member.communityId = communityId; member.uid = uid; member.name = name;
                member.photoUrl = photoUrl; member.role = CommunityRole.MEMBER; member.joinedAt = now;
                mExecutor.execute(() -> {
                    mDao.insertMember(member);
                    int count = mDao.getMemberCountSync(communityId);
                    mDao.updateMemberCount(communityId, count);
                });
                communitiesRef().child(communityId).child("memberCount")
                        .setValue(mDao.getMemberCountSync(communityId));
            }
            cb.onComplete(ok, error != null ? error.getMessage() : null);
        });
    }

    public void updateMemberRole(String communityId, String uid, String role, SimpleCallback cb) {
        communitiesRef().child(communityId).child("members").child(uid).child("role")
                .setValue(role, (error, ref) -> {
                    boolean ok = error == null;
                    if (ok) mExecutor.execute(() -> {
                        CommunityMemberEntity m = mDao.getMemberSync(communityId, uid);
                        if (m != null) { m.role = role; mDao.insertMember(m); }
                    });
                    cb.onComplete(ok, error != null ? error.getMessage() : null);
                });
    }

    public void removeMember(String communityId, String uid, SimpleCallback cb) {
        communitiesRef().child(communityId).child("members").child(uid).removeValue((error, ref) -> {
            boolean ok = error == null;
            if (ok) mExecutor.execute(() -> {
                mDao.deleteMember(communityId, uid);
                int count = mDao.getMemberCountSync(communityId);
                mDao.updateMemberCount(communityId, count);
                communitiesRef().child(communityId).child("memberCount").setValue(count);
            });
            cb.onComplete(ok, error != null ? error.getMessage() : null);
        });
    }

    // ─────────────────────────────────────────────────────────────
    // GROUPS (linked existing group chats)
    // ─────────────────────────────────────────────────────────────

    public LiveData<List<GroupEntity>> observeCommunityGroups(String communityId) {
        return mDao.observeCommunityGroups(communityId);
    }

    public void addGroupToCommunity(String communityId, String groupId, String addedByUid, SimpleCallback cb) {
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("addedByUid", addedByUid);
        data.put("addedAt", now);
        communitiesRef().child(communityId).child("groups").child(groupId).setValue(data, (error, ref) -> {
            boolean ok = error == null;
            if (ok) {
                CommunityGroupLinkEntity link = new CommunityGroupLinkEntity();
                link.communityId = communityId; link.groupId = groupId;
                link.addedByUid = addedByUid; link.addedAt = now;
                mExecutor.execute(() -> {
                    mDao.insertGroupLink(link);
                    int count = mDao.getGroupCountSync(communityId);
                    communitiesRef().child(communityId).child("groupCount").setValue(count);
                });
            }
            cb.onComplete(ok, error != null ? error.getMessage() : null);
        });
    }

    public void removeGroupFromCommunity(String communityId, String groupId, SimpleCallback cb) {
        communitiesRef().child(communityId).child("groups").child(groupId).removeValue((error, ref) -> {
            boolean ok = error == null;
            if (ok) mExecutor.execute(() -> {
                mDao.deleteGroupLink(communityId, groupId);
                int count = mDao.getGroupCountSync(communityId);
                communitiesRef().child(communityId).child("groupCount").setValue(count);
            });
            cb.onComplete(ok, error != null ? error.getMessage() : null);
        });
    }

    // ─────────────────────────────────────────────────────────────
    // FEED / ANNOUNCEMENTS / POSTS
    // ─────────────────────────────────────────────────────────────

    public LiveData<List<CommunityPostEntity>> observeFeed(String communityId) {
        return mDao.observeFeed(communityId);
    }

    public LiveData<List<CommunityPostEntity>> observeAnnouncements(String communityId) {
        return mDao.observeAnnouncements(communityId);
    }

    public LiveData<List<CommunityPostEntity>> observeMediaPosts(String communityId) {
        return mDao.observeMediaPosts(communityId);
    }

    /**
     * Live-syncs the most recent PAGE_SIZE posts (feed or announcements)
     * from Firebase into Room. Call once per screen open; the RecyclerView
     * then observes Room via observeFeed()/observeAnnouncements() so it
     * updates live without re-querying Firebase on every scroll.
     */
    public void syncRecentPosts(String communityId, boolean announcementsOnly) {
        communitiesRef().child(communityId).child("posts")
                .orderByChild("createdAt")
                .limitToLast(PAGE_SIZE)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snapshot) {
                        List<CommunityPostEntity> posts = new ArrayList<>();
                        for (DataSnapshot child : snapshot.getChildren()) {
                            CommunityPostEntity p = parsePost(communityId, child);
                            if (p != null && p.isAnnouncement == announcementsOnly) posts.add(p);
                        }
                        if (!posts.isEmpty()) mExecutor.execute(() -> mDao.insertPosts(posts));
                    }
                    @Override public void onCancelled(DatabaseError error) {
                        Log.w(TAG, "syncRecentPosts cancelled: " + error.getMessage());
                    }
                });
    }

    /** Older-page loader for "scroll to load more" — reads straight from Room's own paging query. */
    public void loadMorePosts(String communityId, boolean announcementsOnly, long beforeTs,
                               ResultCallback<List<CommunityPostEntity>> cb) {
        mExecutor.execute(() -> {
            List<CommunityPostEntity> page =
                    mDao.getPostsPageSync(communityId, announcementsOnly, beforeTs, PAGE_SIZE);
            cb.onResult(page);
        });
    }

    public void createPost(String communityId, String authorUid, String authorName, String authorPhoto,
                            String text, @Nullable String mediaUrl, @Nullable String mediaType,
                            boolean isAnnouncement, @Nullable CommunityPoll poll, SimpleCallback cb) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        Map<String, Object> data = new HashMap<>();
        data.put("authorUid", authorUid);
        data.put("authorName", authorName);
        data.put("authorPhoto", authorPhoto);
        data.put("text", text != null ? text : "");
        if (mediaUrl != null) data.put("mediaUrl", mediaUrl);
        if (mediaType != null) data.put("mediaType", mediaType);
        data.put("isAnnouncement", isAnnouncement);
        data.put("pinned", false);
        data.put("likeCount", 0);
        data.put("commentCount", 0);
        data.put("createdAt", now);
        if (poll != null) {
            Map<String, Object> pollMap = new HashMap<>();
            pollMap.put("question", poll.question);
            List<Map<String, Object>> opts = new ArrayList<>();
            for (CommunityPoll.Option o : poll.options) {
                Map<String, Object> om = new HashMap<>();
                om.put("text", o.text);
                om.put("votes", o.votes);
                opts.add(om);
            }
            pollMap.put("options", opts);
            data.put("poll", pollMap);
        }

        communitiesRef().child(communityId).child("posts").child(id).setValue(data, (error, ref) -> {
            boolean ok = error == null;
            if (ok) {
                CommunityPostEntity post = new CommunityPostEntity();
                post.id = id; post.communityId = communityId; post.authorUid = authorUid;
                post.authorName = authorName; post.authorPhoto = authorPhoto; post.text = text;
                post.mediaUrl = mediaUrl; post.mediaType = mediaType;
                post.isAnnouncement = isAnnouncement; post.createdAt = now;
                post.pollJson = poll != null ? poll.toJson() : null;
                mExecutor.execute(() -> {
                    mDao.insertPost(post);
                    int count = mDao.getPostCountSync(communityId);
                    communitiesRef().child(communityId).child("postCount").setValue(count);
                });
            }
            cb.onComplete(ok, error != null ? error.getMessage() : null);
        });
    }

    public void toggleLike(String communityId, String postId, String uid, SimpleCallback cb) {
        DatabaseReference likeRef = communitiesRef().child(communityId).child("posts")
                .child(postId).child("likes").child(uid);
        likeRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                boolean currentlyLiked = snapshot.exists();
                likeRef.setValue(currentlyLiked ? null : true);
                DatabaseReference countRef = communitiesRef().child(communityId).child("posts")
                        .child(postId).child("likeCount");
                countRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot countSnap) {
                        long current = longOrZero(countSnap);
                        long updated = Math.max(0, current + (currentlyLiked ? -1 : 1));
                        countRef.setValue(updated);
                        mExecutor.execute(() -> mDao.updateLikeCount(postId, updated));
                        cb.onComplete(true, null);
                    }
                    @Override public void onCancelled(DatabaseError error) { cb.onComplete(false, error.getMessage()); }
                });
            }
            @Override public void onCancelled(DatabaseError error) { cb.onComplete(false, error.getMessage()); }
        });
    }

    public void addComment(String communityId, String postId, String uid, String name,
                            String photo, String text, SimpleCallback cb) {
        String commentId = UUID.randomUUID().toString();
        Map<String, Object> data = new HashMap<>();
        data.put("uid", uid);
        data.put("name", name);
        data.put("photo", photo);
        data.put("text", text);
        data.put("createdAt", System.currentTimeMillis());

        communitiesRef().child(communityId).child("posts").child(postId).child("comments")
                .child(commentId).setValue(data, (error, ref) -> {
                    boolean ok = error == null;
                    if (ok) {
                        DatabaseReference countRef = communitiesRef().child(communityId)
                                .child("posts").child(postId).child("commentCount");
                        countRef.addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot s) {
                                long updated = longOrZero(s) + 1;
                                countRef.setValue(updated);
                                mExecutor.execute(() -> mDao.updateCommentCount(postId, updated));
                            }
                            @Override public void onCancelled(DatabaseError error) {}
                        });
                    }
                    cb.onComplete(ok, error != null ? error.getMessage() : null);
                });
    }

    /** Fetches this post's comments once (bottom sheet open) — comments aren't cached in Room. */
    public void fetchComments(String communityId, String postId, ResultCallback<List<Map<String, Object>>> cb) {
        communitiesRef().child(communityId).child("posts").child(postId).child("comments")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snapshot) {
                        List<Map<String, Object>> comments = new ArrayList<>();
                        for (DataSnapshot child : snapshot.getChildren()) {
                            Map<String, Object> c = new HashMap<>();
                            c.put("uid", child.child("uid").getValue(String.class));
                            c.put("name", child.child("name").getValue(String.class));
                            c.put("photo", child.child("photo").getValue(String.class));
                            c.put("text", child.child("text").getValue(String.class));
                            c.put("createdAt", longOrZero(child.child("createdAt")));
                            comments.add(c);
                        }
                        cb.onResult(comments);
                    }
                    @Override public void onCancelled(DatabaseError error) { cb.onResult(new ArrayList<>()); }
                });
    }

    public void votePoll(String communityId, String postId, String uid, int optionIndex, SimpleCallback cb) {
        DatabaseReference postRef = communitiesRef().child(communityId).child("posts").child(postId);
        postRef.child("poll").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                CommunityPoll poll = parsePollSnapshot(snapshot);
                if (poll == null) { cb.onComplete(false, "No poll on this post"); return; }
                poll.applyVote(uid, optionIndex);

                Map<String, Object> pollMap = new HashMap<>();
                pollMap.put("question", poll.question);
                List<Map<String, Object>> opts = new ArrayList<>();
                for (CommunityPoll.Option o : poll.options) {
                    Map<String, Object> om = new HashMap<>();
                    om.put("text", o.text);
                    om.put("votes", o.votes);
                    opts.add(om);
                }
                pollMap.put("options", opts);
                Map<String, Object> votersMap = new HashMap<>(poll.voters);
                pollMap.put("voters", votersMap);

                postRef.child("poll").setValue(pollMap, (error, ref) -> {
                    boolean ok = error == null;
                    if (ok) mExecutor.execute(() -> mDao.updatePollJson(postId, poll.toJson()));
                    cb.onComplete(ok, error != null ? error.getMessage() : null);
                });
            }
            @Override public void onCancelled(DatabaseError error) { cb.onComplete(false, error.getMessage()); }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // PARSE HELPERS
    // ─────────────────────────────────────────────────────────────

    private CommunityPostEntity parsePost(String communityId, DataSnapshot s) {
        CommunityPostEntity p = new CommunityPostEntity();
        p.id = s.getKey();
        p.communityId = communityId;
        p.authorUid = s.child("authorUid").getValue(String.class);
        p.authorName = s.child("authorName").getValue(String.class);
        p.authorPhoto = s.child("authorPhoto").getValue(String.class);
        p.text = s.child("text").getValue(String.class);
        p.mediaUrl = s.child("mediaUrl").getValue(String.class);
        p.mediaType = s.child("mediaType").getValue(String.class);
        Boolean announcement = s.child("isAnnouncement").getValue(Boolean.class);
        p.isAnnouncement = announcement != null && announcement;
        Boolean pinned = s.child("pinned").getValue(Boolean.class);
        p.pinned = pinned != null && pinned;
        p.likeCount = longOrZero(s.child("likeCount"));
        p.commentCount = longOrZero(s.child("commentCount"));
        p.createdAt = longOrZero(s.child("createdAt"));
        CommunityPoll poll = parsePollSnapshot(s.child("poll"));
        p.pollJson = poll != null ? poll.toJson() : null;
        return p;
    }

    @Nullable
    private CommunityPoll parsePollSnapshot(DataSnapshot pollSnap) {
        if (!pollSnap.exists()) return null;
        CommunityPoll poll = new CommunityPoll();
        poll.question = pollSnap.child("question").getValue(String.class);
        for (DataSnapshot optSnap : pollSnap.child("options").getChildren()) {
            String text = optSnap.child("text").getValue(String.class);
            long votes = longOrZero(optSnap.child("votes"));
            poll.options.add(new CommunityPoll.Option(text, (int) votes));
        }
        // options may be stored as a JSON array (index keys "0","1",...) rather than
        // named children — Firebase RTDB returns arrays as ordered children either way,
        // so the loop above already covers both shapes.
        for (DataSnapshot voterSnap : pollSnap.child("voters").getChildren()) {
            Long idx = voterSnap.getValue(Long.class);
            if (idx != null) poll.voters.put(voterSnap.getKey(), idx.intValue());
        }
        return poll;
    }

    private static long longOrZero(DataSnapshot snap) {
        Long v = snap.getValue(Long.class);
        return v != null ? v : 0L;
    }
}
