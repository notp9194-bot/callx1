package com.callx.app.repository;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.callx.app.community.CommunityBadge;
import com.callx.app.community.CommunityPoll;
import com.callx.app.community.CommunityReaction;
import com.callx.app.community.CommunityRole;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.CommunityDao;
import com.callx.app.db.dao.CommunityEventDao;
import com.callx.app.db.dao.CommunityJoinRequestDao;
import com.callx.app.db.dao.CommunityModerationLogDao;
import com.callx.app.db.dao.CommunityNotificationDao;
import com.callx.app.db.dao.CommunityScheduledPostDao;
import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.db.entity.CommunityEventEntity;
import com.callx.app.db.entity.CommunityGroupLinkEntity;
import com.callx.app.db.entity.CommunityJoinRequestEntity;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.db.entity.CommunityModerationLogEntity;
import com.callx.app.db.entity.CommunityNotificationEntity;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.db.entity.CommunityScheduledPostEntity;
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
 * of truth for the Community system.
 *
 * v31 additions:
 *   - Join requests / approval flow (private communities)
 *   - Invite links (token-based)
 *   - In-app push notifications (community_notifications/{uid}/{id})
 *   - Multi-emoji reactions (reactionCounts + per-user reactions)
 *   - Moderation (mute/ban/delete post/admin action log)
 *   - Scheduled posts (stored locally + WorkManager publishes)
 *   - Events / RSVP
 *   - Member badges
 *
 * Firebase layout:
 *   communities/{communityId}                        -> CommunityEntity fields
 *   communities/{communityId}/members/{uid}           -> member data + badge, isMuted
 *   communities/{communityId}/posts/{postId}          -> post + reactions/{uid}, reactionCounts/
 *   communities/{communityId}/join_requests/{id}      -> join request (v31)
 *   communities/{communityId}/events/{id}             -> event data (v31)
 *   communities/{communityId}/moderation_log/{id}     -> admin action log (v31)
 *   community_invites/{token}                          -> communityId (v31)
 *   community_notifications/{uid}/{id}                 -> notification (v31)
 *   community_by_owner/{ownerUid}                      -> communityId
 */
public class CommunityRepository {

    private static final String TAG = "CommunityRepository";
    private static final int PAGE_SIZE = 20;

    private static volatile CommunityRepository sInstance;

    private final AppDatabase mDb;
    private final CommunityDao mDao;
    private final CommunityJoinRequestDao mJoinRequestDao;
    private final CommunityEventDao mEventDao;
    private final CommunityNotificationDao mNotificationDao;
    private final CommunityScheduledPostDao mScheduledPostDao;
    private final CommunityModerationLogDao mModerationLogDao;
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
        mJoinRequestDao    = mDb.communityJoinRequestDao();
        mEventDao          = mDb.communityEventDao();
        mNotificationDao   = mDb.communityNotificationDao();
        mScheduledPostDao  = mDb.communityScheduledPostDao();
        mModerationLogDao  = mDb.communityModerationLogDao();
        mExecutor = Executors.newFixedThreadPool(4);
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

    private DatabaseReference notificationsRef(String uid) {
        return mFirebase.getReference("community_notifications").child(uid);
    }

    private DatabaseReference invitesRef() {
        return mFirebase.getReference("community_invites");
    }

    // ─────────────────────────────────────────────────────────────
    // EXISTENCE / OWNERSHIP
    // ─────────────────────────────────────────────────────────────

    public void checkOwnerHasCommunity(String ownerUid, ResultCallback<String> cb) {
        ownerIndexRef().child(ownerUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@Nullable DataSnapshot s) {
                String id = s != null ? s.getValue(String.class) : null;
                if (id != null && !id.isEmpty()) { cb.onResult(id); return; }
                mExecutor.execute(() -> {
                    CommunityEntity local = mDao.getCommunityByOwnerSync(ownerUid);
                    cb.onResult(local != null ? local.id : null);
                });
            }
            @Override public void onCancelled(@Nullable DatabaseError e) { cb.onResult(null); }
        });
    }

    /** Alias used by profile-card UI — same lookup semantics as {@link #checkOwnerHasCommunity}. */
    public void checkHasCommunity(String uid, ResultCallback<String> cb) {
        checkOwnerHasCommunity(uid, cb);
    }

    public LiveData<CommunityEntity> observeCommunity(String communityId) {
        syncCommunityMeta(communityId);
        return mDao.observeCommunity(communityId);
    }

    private void syncCommunityMeta(String communityId) {
        communitiesRef().child(communityId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@Nullable DataSnapshot s) {
                if (s == null || !s.exists()) return;
                CommunityEntity c = parseCommunity(s);
                mExecutor.execute(() -> mDao.insertCommunity(c));
            }
            @Override public void onCancelled(@Nullable DatabaseError e) {}
        });
    }

    // ─────────────────────────────────────────────────────────────
    // CREATE / DISABLE COMMUNITY
    // ─────────────────────────────────────────────────────────────

    public void createCommunity(String ownerUid, String ownerName, String ownerPhoto,
                                String name, String description, @Nullable String iconUrl,
                                ResultCallback<String> cb) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("description", description != null ? description : "");
        data.put("iconUrl", iconUrl != null ? iconUrl : "");
        data.put("ownerUid", ownerUid);
        data.put("memberCount", 1L);
        data.put("groupCount", 0L);
        data.put("postCount", 0L);
        data.put("createdAt", now);
        data.put("isPrivate", false);
        data.put("inviteEnabled", false);

        Map<String, Object> memberData = new HashMap<>();
        memberData.put("name", ownerName);
        memberData.put("photoUrl", ownerPhoto != null ? ownerPhoto : "");
        memberData.put("role", CommunityRole.OWNER);
        memberData.put("joinedAt", now);
        memberData.put("badge", CommunityBadge.NONE);
        memberData.put("isMuted", false);
        memberData.put("isBanned", false);

        // NOTE: "members" must be nested INSIDE `data` (not as a sibling
        // batch entry) — Firebase updateChildren() throws IllegalArgumentException
        // synchronously if a multi-path update contains both a path and a
        // child of that same path (e.g. "communities/{id}" and
        // "communities/{id}/members/{uid}" together). Nesting avoids the
        // path-conflict entirely.
        Map<String, Object> membersMap = new HashMap<>();
        membersMap.put(ownerUid, memberData);
        data.put("members", membersMap);

        Map<String, Object> batch = new HashMap<>();
        batch.put("communities/" + id, data);
        batch.put("community_by_owner/" + ownerUid, id);

        mFirebase.getReference().updateChildren(batch, (err, ref) -> {
            if (err != null) { cb.onResult(null); return; }
            CommunityEntity entity = new CommunityEntity();
            entity.id = id; entity.name = name; entity.description = description;
            entity.ownerUid = ownerUid; entity.memberCount = 1; entity.createdAt = now;
            mExecutor.execute(() -> mDao.insertCommunity(entity));
            cb.onResult(id);
        });
    }

    public void disableCommunity(String communityId, String ownerUid, SimpleCallback cb) {
        Map<String, Object> batch = new HashMap<>();
        batch.put("communities/" + communityId, null);
        batch.put("community_by_owner/" + ownerUid, null);
        mFirebase.getReference().updateChildren(batch, (err, ref) -> {
            if (err != null) { cb.onComplete(false, err.getMessage()); return; }
            mExecutor.execute(() -> mDao.deleteCommunity(communityId));
            cb.onComplete(true, null);
        });
    }

    public void updateCommunityInfo(String communityId, String name, String description,
                                    @Nullable String iconUrl, SimpleCallback cb) {
        Map<String, Object> update = new HashMap<>();
        update.put("name", name);
        update.put("description", description != null ? description : "");
        if (iconUrl != null) update.put("iconUrl", iconUrl);

        communitiesRef().child(communityId).updateChildren(update, (err, ref) -> {
            if (err != null) { cb.onComplete(false, err.getMessage()); return; }
            mExecutor.execute(() -> {
                CommunityEntity c = mDao.getCommunitySync(communityId);
                if (c != null) {
                    c.name = name; c.description = description;
                    if (iconUrl != null) c.iconUrl = iconUrl;
                    mDao.insertCommunity(c);
                }
            });
            cb.onComplete(true, null);
        });
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVACY & INVITE LINKS  (v31)
    // ─────────────────────────────────────────────────────────────

    public void setCommunityPrivacy(String communityId, boolean isPrivate, SimpleCallback cb) {
        communitiesRef().child(communityId).child("isPrivate").setValue(isPrivate, (err, ref) -> {
            if (err != null) { cb.onComplete(false, err.getMessage()); return; }
            mExecutor.execute(() -> {
                CommunityEntity c = mDao.getCommunitySync(communityId);
                if (c != null) { c.isPrivate = isPrivate; mDao.insertCommunity(c); }
            });
            cb.onComplete(true, null);
        });
    }

    public void generateInviteToken(String communityId, ResultCallback<String> cb) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> batch = new HashMap<>();
        batch.put("communities/" + communityId + "/inviteToken", token);
        batch.put("communities/" + communityId + "/inviteEnabled", true);
        batch.put("community_invites/" + token, communityId);
        mFirebase.getReference().updateChildren(batch, (err, ref) -> {
            if (err != null) { cb.onResult(null); return; }
            mExecutor.execute(() -> {
                CommunityEntity c = mDao.getCommunitySync(communityId);
                if (c != null) { c.inviteToken = token; c.inviteEnabled = true; mDao.insertCommunity(c); }
            });
            cb.onResult(token);
        });
    }

    public void resolveInviteToken(String token, ResultCallback<String> communityIdCb) {
        invitesRef().child(token).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@Nullable DataSnapshot s) {
                communityIdCb.onResult(s != null ? s.getValue(String.class) : null);
            }
            @Override public void onCancelled(@Nullable DatabaseError e) { communityIdCb.onResult(null); }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // JOIN REQUESTS  (v31)
    // ─────────────────────────────────────────────────────────────

    public void sendJoinRequest(String communityId, String requesterUid, String requesterName,
                                String requesterPhoto, @Nullable String message, SimpleCallback cb) {
        sendJoinRequest(communityId, null, requesterUid, requesterName, requesterPhoto, message, cb);
    }

    /**
     * v32: Community Access System.
     * @param groupId null = request to join the community itself (Instagram private-account style).
     *                non-null = requester is already a community member asking to join this
     *                specific ADMIN_ONLY linked group.
     */
    public void sendJoinRequest(String communityId, @Nullable String groupId, String requesterUid,
                                String requesterName, String requesterPhoto,
                                @Nullable String message, SimpleCallback cb) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        if (groupId != null) data.put("groupId", groupId);
        data.put("requesterUid", requesterUid);
        data.put("requesterName", requesterName);
        data.put("requesterPhoto", requesterPhoto != null ? requesterPhoto : "");
        data.put("status", "pending");
        data.put("message", message != null ? message : "");
        data.put("createdAt", now);

        communitiesRef().child(communityId).child("join_requests").child(id)
                .setValue(data, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    CommunityJoinRequestEntity entity = new CommunityJoinRequestEntity();
                    entity.id = id; entity.communityId = communityId; entity.groupId = groupId;
                    entity.requesterUid = requesterUid; entity.requesterName = requesterName;
                    entity.requesterPhoto = requesterPhoto; entity.status = "pending";
                    entity.message = message; entity.createdAt = now;
                    mExecutor.execute(() -> mJoinRequestDao.insertRequest(entity));
                    cb.onComplete(true, null);
                });
    }

    public void approveJoinRequest(String communityId, String requestId, String requesterUid,
                                   String requesterName, String requesterPhoto,
                                   String approverUid, SimpleCallback cb) {
        approveJoinRequest(communityId, requestId, null, requesterUid, requesterName, requesterPhoto, approverUid, cb);
    }

    /**
     * v32: Community Access System.
     * @param groupId null = approving a community-join request (adds as community MEMBER).
     *                non-null = approving an ask-to-join-group request (adds uid to that
     *                group's own members only — requester is already a community member).
     */
    public void approveJoinRequest(String communityId, String requestId, @Nullable String groupId,
                                   String requesterUid, String requesterName, String requesterPhoto,
                                   String approverUid, SimpleCallback cb) {
        long now = System.currentTimeMillis();
        Map<String, Object> batch = new HashMap<>();
        batch.put("communities/" + communityId + "/join_requests/" + requestId + "/status", "approved");
        batch.put("communities/" + communityId + "/join_requests/" + requestId + "/processedAt", now);
        batch.put("communities/" + communityId + "/join_requests/" + requestId + "/processedByUid", approverUid);

        if (groupId != null) {
            batch.put("groups/" + groupId + "/members/" + requesterUid, true);
            batch.put("userGroups/" + requesterUid + "/" + groupId, true);
        } else {
            Map<String, Object> memberData = new HashMap<>();
            memberData.put("name", requesterName); memberData.put("photoUrl", requesterPhoto != null ? requesterPhoto : "");
            memberData.put("role", CommunityRole.MEMBER); memberData.put("joinedAt", now);
            memberData.put("badge", CommunityBadge.NONE); memberData.put("isMuted", false); memberData.put("isBanned", false);
            batch.put("communities/" + communityId + "/members/" + requesterUid, memberData);
        }

        mFirebase.getReference().updateChildren(batch, (err, ref) -> {
            if (err != null) { cb.onComplete(false, err.getMessage()); return; }
            mExecutor.execute(() -> {
                mJoinRequestDao.updateStatus(requestId, "approved", now, approverUid);
                if (groupId == null) {
                    CommunityMemberEntity m = new CommunityMemberEntity();
                    m.communityId = communityId; m.uid = requesterUid; m.name = requesterName;
                    m.photoUrl = requesterPhoto; m.role = CommunityRole.MEMBER; m.joinedAt = now;
                    m.badge = CommunityBadge.NONE;
                    mDao.insertMember(m);
                }
                postNotification(requesterUid, communityId, "join_approved",
                        groupId == null ? "Join Request Approved" : "Group Access Approved",
                        groupId == null ? "You have been approved to join the community."
                                        : "You have been approved to join the group.",
                        null, approverUid, null, null);
            });
            cb.onComplete(true, null);
        });
    }

    public void rejectJoinRequest(String communityId, String requestId, String rejectorUid, SimpleCallback cb) {
        long now = System.currentTimeMillis();
        Map<String, Object> update = new HashMap<>();
        update.put("status", "rejected");
        update.put("processedAt", now);
        update.put("processedByUid", rejectorUid);
        communitiesRef().child(communityId).child("join_requests").child(requestId)
                .updateChildren(update, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    mExecutor.execute(() -> mJoinRequestDao.updateStatus(requestId, "rejected", now, rejectorUid));
                    cb.onComplete(true, null);
                });
    }

    public LiveData<List<CommunityJoinRequestEntity>> observePendingJoinRequests(String communityId) {
        syncJoinRequests(communityId);
        return mJoinRequestDao.observePendingRequests(communityId);
    }

    private void syncJoinRequests(String communityId) {
        communitiesRef().child(communityId).child("join_requests")
                .orderByChild("status").equalTo("pending")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        if (s == null) return;
                        List<CommunityJoinRequestEntity> list = new ArrayList<>();
                        for (DataSnapshot child : s.getChildren()) {
                            CommunityJoinRequestEntity e = new CommunityJoinRequestEntity();
                            e.id = child.getKey(); e.communityId = communityId;
                            e.groupId = child.child("groupId").getValue(String.class);
                            e.requesterUid = child.child("requesterUid").getValue(String.class);
                            e.requesterName = child.child("requesterName").getValue(String.class);
                            e.requesterPhoto = child.child("requesterPhoto").getValue(String.class);
                            e.status = "pending"; e.message = child.child("message").getValue(String.class);
                            e.createdAt = longOrZero(child.child("createdAt"));
                            list.add(e);
                        }
                        mExecutor.execute(() -> {
                            for (CommunityJoinRequestEntity e : list) mJoinRequestDao.insertRequest(e);
                        });
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {}
                });
    }

    // ─────────────────────────────────────────────────────────────
    // MEMBERS
    // ─────────────────────────────────────────────────────────────

    public LiveData<List<CommunityMemberEntity>> observeMembers(String communityId) {
        syncMembers(communityId);
        return mDao.observeMembers(communityId);
    }

    private void syncMembers(String communityId) {
        communitiesRef().child(communityId).child("members")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        if (s == null) return;
                        List<CommunityMemberEntity> list = new ArrayList<>();
                        for (DataSnapshot ms : s.getChildren()) {
                            CommunityMemberEntity m = parseMember(communityId, ms);
                            list.add(m);
                        }
                        mExecutor.execute(() -> {
                            for (CommunityMemberEntity m : list) mDao.insertMember(m);
                        });
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {}
                });
    }

    public void addMember(String communityId, String uid, String name, String photo, String role, SimpleCallback cb) {
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("name", name); data.put("photoUrl", photo != null ? photo : "");
        data.put("role", role); data.put("joinedAt", now);
        data.put("badge", CommunityBadge.NONE); data.put("isMuted", false); data.put("isBanned", false);

        communitiesRef().child(communityId).child("members").child(uid)
                .setValue(data, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    CommunityMemberEntity m = new CommunityMemberEntity();
                    m.communityId = communityId; m.uid = uid; m.name = name;
                    m.photoUrl = photo; m.role = role; m.joinedAt = now;
                    m.badge = CommunityBadge.NONE;
                    mExecutor.execute(() -> mDao.insertMember(m));
                    cb.onComplete(true, null);
                });
    }

    public void removeMember(String communityId, String uid, String removedByUid,
                             String removedByName, @Nullable String reason, SimpleCallback cb) {
        communitiesRef().child(communityId).child("members").child(uid)
                .removeValue((err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    mExecutor.execute(() -> {
                        mDao.deleteMember(communityId, uid);
                        logModerationAction(communityId, removedByUid, removedByName,
                                uid, null, "ban", reason, null);
                    });
                    cb.onComplete(true, null);
                });
    }

    public void setMemberRole(String communityId, String uid, String newRole,
                              String changedByUid, String changedByName, SimpleCallback cb) {
        communitiesRef().child(communityId).child("members").child(uid).child("role")
                .setValue(newRole, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    mExecutor.execute(() -> {
                        mDao.updateMemberRole(communityId, uid, newRole);
                        String action = CommunityRole.ADMIN.equals(newRole) ? "make_admin" : "remove_admin";
                        logModerationAction(communityId, changedByUid, changedByName, uid, null, action, null, null);
                    });
                    cb.onComplete(true, null);
                });
    }

    // v31: Mute member
    public void muteMember(String communityId, String uid, boolean muted,
                           String adminUid, String adminName, @Nullable String reason, SimpleCallback cb) {
        communitiesRef().child(communityId).child("members").child(uid).child("isMuted")
                .setValue(muted, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    mExecutor.execute(() -> {
                        mDao.updateMemberMuted(communityId, uid, muted);
                        logModerationAction(communityId, adminUid, adminName, uid, null,
                                muted ? "mute" : "unmute", reason, null);
                    });
                    cb.onComplete(true, null);
                });
    }

    // v31: Assign badge
    public void setBadge(String communityId, String uid, String badge,
                         String adminUid, String adminName, SimpleCallback cb) {
        communitiesRef().child(communityId).child("members").child(uid).child("badge")
                .setValue(badge, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    mExecutor.execute(() -> mDao.updateMemberBadge(communityId, uid, badge));
                    cb.onComplete(true, null);
                });
    }

    // ─────────────────────────────────────────────────────────────
    // GROUPS
    // ─────────────────────────────────────────────────────────────

    public LiveData<List<CommunityGroupLinkEntity>> observeLinkedGroups(String communityId) {
        return mDao.observeLinkedGroups(communityId);
    }

    /** v31: joined GroupEntity list for a community — used by GroupsFragment/AddGroupActivity. */
    public LiveData<List<GroupEntity>> observeCommunityGroups(String communityId) {
        syncGroupLinks(communityId);
        return mDao.observeCommunityGroups(communityId);
    }

    private void syncGroupLinks(String communityId) {
        communitiesRef().child(communityId).child("groups")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        if (s == null) return;
                        List<CommunityGroupLinkEntity> list = new ArrayList<>();
                        for (DataSnapshot gs : s.getChildren()) {
                            CommunityGroupLinkEntity link = new CommunityGroupLinkEntity();
                            link.communityId = communityId; link.groupId = gs.getKey();
                            link.addedByUid = gs.child("addedByUid").getValue(String.class);
                            link.addedAt = longOrZero(gs.child("addedAt"));
                            String accessType = gs.child("accessType").getValue(String.class);
                            link.accessType = accessType != null ? accessType : "OPEN";
                            list.add(link);
                        }
                        mExecutor.execute(() -> {
                            for (CommunityGroupLinkEntity l : list) mDao.insertGroupLink(l);
                        });
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {}
                });
    }

    public void linkGroup(String communityId, String groupId, String addedByUid, SimpleCallback cb) {
        linkGroup(communityId, groupId, addedByUid, "OPEN", cb);
    }

    /**
     * v32: link a group with an explicit access type.
     * "OPEN" (default, WhatsApp-style) — any community member who taps the
     * group is auto-joined immediately.
     * "ADMIN_ONLY" (Instagram-style) — member must send an ask-to-join
     * request that a community admin/owner approves.
     */
    public void linkGroup(String communityId, String groupId, String addedByUid,
                          String accessType, SimpleCallback cb) {
        long now = System.currentTimeMillis();
        String type = "ADMIN_ONLY".equals(accessType) ? "ADMIN_ONLY" : "OPEN";
        Map<String, Object> data = new HashMap<>();
        data.put("addedByUid", addedByUid); data.put("addedAt", now); data.put("accessType", type);

        communitiesRef().child(communityId).child("groups").child(groupId)
                .setValue(data, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    CommunityGroupLinkEntity link = new CommunityGroupLinkEntity();
                    link.communityId = communityId; link.groupId = groupId;
                    link.addedByUid = addedByUid; link.addedAt = now; link.accessType = type;
                    mExecutor.execute(() -> mDao.insertGroupLink(link));
                    cb.onComplete(true, null);
                });
    }

    /** v32: admin toggles a linked group between "OPEN" and "ADMIN_ONLY". */
    public void setGroupAccessType(String communityId, String groupId, String accessType, SimpleCallback cb) {
        String type = "ADMIN_ONLY".equals(accessType) ? "ADMIN_ONLY" : "OPEN";
        communitiesRef().child(communityId).child("groups").child(groupId).child("accessType")
                .setValue(type, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    mExecutor.execute(() -> mDao.updateGroupAccessType(communityId, groupId, type));
                    cb.onComplete(true, null);
                });
    }

    public void unlinkGroup(String communityId, String groupId, SimpleCallback cb) {
        communitiesRef().child(communityId).child("groups").child(groupId)
                .removeValue((err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    mExecutor.execute(() -> mDao.deleteGroupLink(communityId, groupId));
                    cb.onComplete(true, null);
                });
    }

    /** Alias — CommunityAddGroupActivity's naming for {@link #linkGroup}. */
    public void addGroupToCommunity(String communityId, String groupId, String addedByUid, SimpleCallback cb) {
        linkGroup(communityId, groupId, addedByUid, cb);
    }

    /** Alias — CommunityGroupsFragment's naming for {@link #unlinkGroup}. */
    public void removeGroupFromCommunity(String communityId, String groupId, SimpleCallback cb) {
        unlinkGroup(communityId, groupId, cb);
    }

    /**
     * v32: Community Access System result — mirrors WhatsApp (open groups
     * auto-join) + Instagram (ask-to-join / pending) + Telegram (invite-link
     * bypass, see {@link #joinCommunityByInvite}) access patterns.
     */
    public interface GroupAccessListener {
        /** Already a group member, or just auto-joined an OPEN group — safe to open the chat. */
        void onGranted();
        /** Not a community member yet — must join/request the community first. */
        void onNotCommunityMember();
        /** ADMIN_ONLY group: an ask-to-join request already exists and is pending. */
        void onRequestPending();
        /** ADMIN_ONLY group: a fresh ask-to-join request was just created. */
        void onRequestSent();
        void onError(String message);
    }

    /**
     * v32: Community Access System — call before opening a community-linked
     * group chat. Checks community membership, then group membership, then
     * the linked group's accessType, auto-joining OPEN groups or creating an
     * ask-to-join request for ADMIN_ONLY ones.
     */
    public void resolveGroupAccess(String communityId, String groupId, String uid,
                                   String uname, String uphoto, GroupAccessListener listener) {
        communitiesRef().child(communityId).child("members").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot memberSnap) {
                        if (memberSnap == null || !memberSnap.exists()) {
                            listener.onNotCommunityMember();
                            return;
                        }
                        mFirebase.getReference().child("groups").child(groupId).child("members").child(uid)
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(@Nullable DataSnapshot gMemberSnap) {
                                        if (gMemberSnap != null && gMemberSnap.exists()) {
                                            listener.onGranted();
                                            return;
                                        }
                                        resolveGroupAccessType(communityId, groupId, uid, uname, uphoto, listener);
                                    }
                                    @Override public void onCancelled(@Nullable DatabaseError e) {
                                        listener.onError(e != null ? e.getMessage() : "Access check failed");
                                    }
                                });
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {
                        listener.onError(e != null ? e.getMessage() : "Access check failed");
                    }
                });
    }

    private void resolveGroupAccessType(String communityId, String groupId, String uid,
                                        String uname, String uphoto, GroupAccessListener listener) {
        communitiesRef().child(communityId).child("groups").child(groupId).child("accessType")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        String type = s != null ? s.getValue(String.class) : null;
                        if (!"ADMIN_ONLY".equals(type)) {
                            // OPEN (default, WhatsApp-style) — auto-join the group.
                            Map<String, Object> batch = new HashMap<>();
                            batch.put("groups/" + groupId + "/members/" + uid, true);
                            batch.put("userGroups/" + uid + "/" + groupId, true);
                            mFirebase.getReference().updateChildren(batch, (err, ref) -> {
                                if (err != null) { listener.onError(err.getMessage()); return; }
                                listener.onGranted();
                            });
                            return;
                        }
                        findPendingGroupRequest(communityId, groupId, uid, uname, uphoto, listener);
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {
                        listener.onError(e != null ? e.getMessage() : "Access check failed");
                    }
                });
    }

    private void findPendingGroupRequest(String communityId, String groupId, String uid,
                                         String uname, String uphoto, GroupAccessListener listener) {
        communitiesRef().child(communityId).child("join_requests")
                .orderByChild("status").equalTo("pending")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        if (s != null) {
                            for (DataSnapshot child : s.getChildren()) {
                                String reqGroupId = child.child("groupId").getValue(String.class);
                                String reqUid = child.child("requesterUid").getValue(String.class);
                                if (groupId.equals(reqGroupId) && uid.equals(reqUid)) {
                                    listener.onRequestPending();
                                    return;
                                }
                            }
                        }
                        sendJoinRequest(communityId, groupId, uid, uname, uphoto, null,
                                (success, error) -> {
                                    if (success) listener.onRequestSent();
                                    else listener.onError(error);
                                });
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {
                        listener.onError(e != null ? e.getMessage() : "Access check failed");
                    }
                });
    }

    public void observeAvailableGroups(String uid, ResultCallback<List<GroupEntity>> cb) {
        mExecutor.execute(() -> {
            List<GroupEntity> groups = mDao.getGroupsForUserSync(uid);
            cb.onResult(groups);
        });
    }

    // ─────────────────────────────────────────────────────────────
    // POSTS
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

    public void syncRecentPosts(String communityId, boolean announcements) {
        communitiesRef().child(communityId).child("posts")
                .orderByChild("isAnnouncement").equalTo(announcements)
                .limitToLast(PAGE_SIZE)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        if (s == null) return;
                        List<CommunityPostEntity> list = new ArrayList<>();
                        for (DataSnapshot ps : s.getChildren()) list.add(parsePost(communityId, ps));
                        mExecutor.execute(() -> mDao.insertPosts(list));
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {}
                });
    }

    public void createPost(String communityId, String authorUid, String authorName,
                           String authorPhoto, String text, @Nullable String mediaUrl,
                           @Nullable String mediaType, boolean isAnnouncement,
                           @Nullable CommunityPoll poll, SimpleCallback cb) {
        createPostInternal(communityId, authorUid, authorName, authorPhoto, text,
                mediaUrl, mediaType, isAnnouncement, poll, 0L, cb);
    }

    private void createPostInternal(String communityId, String authorUid, String authorName,
                                    String authorPhoto, String text, @Nullable String mediaUrl,
                                    @Nullable String mediaType, boolean isAnnouncement,
                                    @Nullable CommunityPoll poll, long scheduledOriginMs, SimpleCallback cb) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("authorUid", authorUid); data.put("authorName", authorName);
        data.put("authorPhoto", authorPhoto != null ? authorPhoto : "");
        data.put("text", text != null ? text : "");
        if (mediaUrl != null) { data.put("mediaUrl", mediaUrl); data.put("mediaType", mediaType != null ? mediaType : "image"); }
        data.put("isAnnouncement", isAnnouncement);
        data.put("pinned", false);
        data.put("likeCount", 0L); data.put("commentCount", 0L);
        data.put("createdAt", now);
        if (scheduledOriginMs > 0) data.put("scheduledAt", scheduledOriginMs);
        if (poll != null) {
            Map<String, Object> pollMap = new HashMap<>();
            pollMap.put("question", poll.question);
            List<Map<String, Object>> opts = new ArrayList<>();
            for (CommunityPoll.Option o : poll.options) {
                Map<String, Object> om = new HashMap<>(); om.put("text", o.text); om.put("votes", 0L); opts.add(om);
            }
            pollMap.put("options", opts);
            data.put("poll", pollMap);
        }

        // Extract @mentions from text
        List<String> mentionedUids = new ArrayList<>();
        if (text != null && text.contains("@")) {
            // mentionedUids would be populated by the UI before calling createPost
        }
        if (!mentionedUids.isEmpty()) data.put("mentionedUids", mentionedUids);

        communitiesRef().child(communityId).child("posts").child(id)
                .setValue(data, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    CommunityPostEntity post = new CommunityPostEntity();
                    post.id = id; post.communityId = communityId; post.authorUid = authorUid;
                    post.authorName = authorName; post.authorPhoto = authorPhoto;
                    post.text = text; post.mediaUrl = mediaUrl; post.mediaType = mediaType;
                    post.isAnnouncement = isAnnouncement; post.createdAt = now;
                    post.scheduledAt = scheduledOriginMs;
                    if (poll != null) post.pollJson = poll.toJson();
                    mExecutor.execute(() -> {
                        mDao.insertPost(post);
                        // Send notifications for mentions
                        // (FCM handles actual push; this updates local notification store)
                    });
                    cb.onComplete(true, null);
                });
    }

    public void createPostWithMentions(String communityId, String authorUid, String authorName,
                                       String authorPhoto, String text, @Nullable String mediaUrl,
                                       @Nullable String mediaType, boolean isAnnouncement,
                                       @Nullable CommunityPoll poll, List<String> mentionedUids,
                                       SimpleCallback cb) {
        // Same as createPost but also dispatches mention notifications
        createPostInternal(communityId, authorUid, authorName, authorPhoto, text,
                mediaUrl, mediaType, isAnnouncement, poll, 0L, (success, error) -> {
                    if (success && mentionedUids != null) {
                        for (String uid : mentionedUids) {
                            if (!uid.equals(authorUid)) {
                                postNotification(uid, communityId, "mention",
                                        authorName + " mentioned you",
                                        text != null && text.length() > 60 ? text.substring(0, 60) + "…" : text,
                                        null, authorUid, authorName, authorPhoto);
                            }
                        }
                    }
                    cb.onComplete(success, error);
                });
    }

    public void deletePost(String communityId, String postId, String adminUid, String adminName,
                           @Nullable String reason, SimpleCallback cb) {
        communitiesRef().child(communityId).child("posts").child(postId)
                .removeValue((err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    mExecutor.execute(() -> {
                        mDao.deletePost(postId);
                        logModerationAction(communityId, adminUid, adminName, null, null,
                                "delete_post", reason, postId);
                    });
                    cb.onComplete(true, null);
                });
    }

    public void reportPost(String communityId, String postId, String reporterUid, String reason, SimpleCallback cb) {
        String reportId = UUID.randomUUID().toString();
        Map<String, Object> data = new HashMap<>();
        data.put("postId", postId); data.put("reporterUid", reporterUid);
        data.put("reason", reason); data.put("createdAt", System.currentTimeMillis());
        mFirebase.getReference("community_reports").child(communityId).child(reportId)
                .setValue(data, (err, ref) -> cb.onComplete(err == null, err != null ? err.getMessage() : null));
    }

    // ─────────────────────────────────────────────────────────────
    // LIKES  (simple toggle — unchanged from v30)
    // ─────────────────────────────────────────────────────────────

    public void toggleLike(String communityId, String postId, String uid, SimpleCallback cb) {
        DatabaseReference likesRef = communitiesRef().child(communityId).child("posts")
                .child(postId).child("likes").child(uid);
        likesRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@Nullable DataSnapshot s) {
                boolean liked = s != null && s.exists();
                Map<String, Object> batch = new HashMap<>();
                batch.put("communities/" + communityId + "/posts/" + postId + "/likes/" + uid,
                        liked ? null : Boolean.TRUE);
                long delta = liked ? -1L : 1L;
                batch.put("communities/" + communityId + "/posts/" + postId + "/likeCount",
                        com.google.firebase.database.ServerValue.increment(delta));
                mFirebase.getReference().updateChildren(batch, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    mExecutor.execute(() -> {
                        CommunityPostEntity p = mDao.getPostSync(postId);
                        if (p != null) { p.likeCount = Math.max(0, p.likeCount + delta); mDao.insertPost(p); }
                    });
                    cb.onComplete(true, null);
                });
            }
            @Override public void onCancelled(@Nullable DatabaseError e) { cb.onComplete(false, e.getMessage()); }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // MULTI-EMOJI REACTIONS  (v31)
    // ─────────────────────────────────────────────────────────────

    public void addReaction(String communityId, String postId, String uid,
                            String reactionType, SimpleCallback cb) {
        DatabaseReference postRef = communitiesRef().child(communityId).child("posts").child(postId);
        postRef.child("reactions").child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@Nullable DataSnapshot s) {
                String prevType = s != null ? s.getValue(String.class) : null;
                Map<String, Object> batch = new HashMap<>();
                String base = "communities/" + communityId + "/posts/" + postId;

                if (prevType != null && !prevType.isEmpty()) {
                    batch.put(base + "/reactionCounts/" + prevType,
                            com.google.firebase.database.ServerValue.increment(-1L));
                }
                if (reactionType != null && !reactionType.isEmpty()) {
                    batch.put(base + "/reactions/" + uid, reactionType);
                    batch.put(base + "/reactionCounts/" + reactionType,
                            com.google.firebase.database.ServerValue.increment(1L));
                } else {
                    batch.put(base + "/reactions/" + uid, null);
                }

                mFirebase.getReference().updateChildren(batch, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    syncPostReactionCounts(communityId, postId);
                    cb.onComplete(true, null);
                });
            }
            @Override public void onCancelled(@Nullable DatabaseError e) { cb.onComplete(false, e.getMessage()); }
        });
    }

    private void syncPostReactionCounts(String communityId, String postId) {
        communitiesRef().child(communityId).child("posts").child(postId).child("reactionCounts")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        if (s == null) return;
                        Map<String, Long> counts = new HashMap<>();
                        for (DataSnapshot child : s.getChildren()) {
                            Long v = child.getValue(Long.class);
                            if (v != null && v > 0) counts.put(child.getKey(), v);
                        }
                        String json = CommunityReaction.toJson(counts);
                        mExecutor.execute(() -> mDao.updateReactionCounts(postId, json));
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {}
                });
    }

    // ─────────────────────────────────────────────────────────────
    // COMMENTS  (unchanged pattern from v30)
    // ─────────────────────────────────────────────────────────────

    public void addComment(String communityId, String postId, String authorUid, String authorName,
                           String authorPhoto, String text, SimpleCallback cb) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("authorUid", authorUid); data.put("authorName", authorName);
        data.put("authorPhoto", authorPhoto != null ? authorPhoto : "");
        data.put("text", text); data.put("createdAt", now);

        Map<String, Object> batch = new HashMap<>();
        batch.put("communities/" + communityId + "/posts/" + postId + "/comments/" + id, data);
        batch.put("communities/" + communityId + "/posts/" + postId + "/commentCount",
                com.google.firebase.database.ServerValue.increment(1L));
        mFirebase.getReference().updateChildren(batch, (err, ref) -> {
            if (err != null) { cb.onComplete(false, err.getMessage()); return; }
            mExecutor.execute(() -> {
                CommunityPostEntity p = mDao.getPostSync(postId);
                if (p != null) { p.commentCount++; mDao.insertPost(p); }
            });
            cb.onComplete(true, null);
        });
    }

    public void fetchComments(String communityId, String postId, ResultCallback<List<Map<String, Object>>> cb) {
        communitiesRef().child(communityId).child("posts").child(postId).child("comments")
                .orderByChild("createdAt")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        List<Map<String, Object>> list = new ArrayList<>();
                        if (s != null) {
                            for (DataSnapshot cs : s.getChildren()) {
                                Map<String, Object> m = new HashMap<>();
                                m.put("id", cs.getKey());
                                m.put("authorUid", cs.child("authorUid").getValue(String.class));
                                m.put("authorName", cs.child("authorName").getValue(String.class));
                                m.put("authorPhoto", cs.child("authorPhoto").getValue(String.class));
                                m.put("text", cs.child("text").getValue(String.class));
                                m.put("createdAt", longOrZero(cs.child("createdAt")));
                                list.add(m);
                            }
                        }
                        cb.onResult(list);
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) { cb.onResult(new ArrayList<>()); }
                });
    }

    // ─────────────────────────────────────────────────────────────
    // POLLS  (unchanged pattern from v30)
    // ─────────────────────────────────────────────────────────────

    public void votePoll(String communityId, String postId, String uid, int optionIndex, SimpleCallback cb) {
        DatabaseReference pollRef = communitiesRef().child(communityId).child("posts")
                .child(postId).child("poll");
        pollRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@Nullable DataSnapshot s) {
                CommunityPoll poll = parsePollSnapshot(s);
                if (poll == null) { cb.onComplete(false, "no poll"); return; }
                poll.applyVote(uid, optionIndex);
                Map<String, Object> batch = new HashMap<>();
                String base = "communities/" + communityId + "/posts/" + postId + "/poll";
                for (int i = 0; i < poll.options.size(); i++)
                    batch.put(base + "/options/" + i + "/votes", (long) poll.options.get(i).votes);
                batch.put(base + "/voters/" + uid, (long) optionIndex);
                mFirebase.getReference().updateChildren(batch, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    String json = poll.toJson();
                    mExecutor.execute(() -> mDao.updatePollJson(postId, json));
                    cb.onComplete(true, null);
                });
            }
            @Override public void onCancelled(@Nullable DatabaseError e) { cb.onComplete(false, e.getMessage()); }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // SCHEDULED POSTS  (v31)
    // ─────────────────────────────────────────────────────────────

    public void schedulePost(String communityId, String authorUid, String authorName,
                             String authorPhoto, String text, @Nullable String mediaUrl,
                             @Nullable String mediaType, boolean isAnnouncement,
                             @Nullable CommunityPoll poll, long scheduledAt, SimpleCallback cb) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        CommunityScheduledPostEntity e = new CommunityScheduledPostEntity();
        e.id = id; e.communityId = communityId; e.authorUid = authorUid;
        e.authorName = authorName; e.authorPhoto = authorPhoto;
        e.text = text; e.mediaUrl = mediaUrl; e.mediaType = mediaType;
        e.isAnnouncement = isAnnouncement; e.scheduledAt = scheduledAt;
        e.status = "pending"; e.createdAt = now;
        if (poll != null) e.pollJson = poll.toJson();

        mExecutor.execute(() -> {
            mScheduledPostDao.insertScheduled(e);
            cb.onComplete(true, null);
        });
    }

    public LiveData<List<CommunityScheduledPostEntity>> observeScheduledPosts(String communityId) {
        return mScheduledPostDao.observeScheduled(communityId);
    }

    public void cancelScheduledPost(String scheduledPostId, SimpleCallback cb) {
        mExecutor.execute(() -> {
            mScheduledPostDao.updateStatus(scheduledPostId, "cancelled");
            cb.onComplete(true, null);
        });
    }

    /** Called by WorkManager to publish due posts. */
    public void publishDueScheduledPosts() {
        mExecutor.execute(() -> {
            List<CommunityScheduledPostEntity> due = mScheduledPostDao.getDuePosts(System.currentTimeMillis());
            for (CommunityScheduledPostEntity sp : due) {
                CommunityPoll poll = sp.pollJson != null ? CommunityPoll.fromJson(sp.pollJson) : null;
                createPostInternal(sp.communityId, sp.authorUid, sp.authorName, sp.authorPhoto,
                        sp.text, sp.mediaUrl, sp.mediaType, sp.isAnnouncement, poll,
                        sp.scheduledAt, (success, error) -> {
                            if (success) {
                                mExecutor.execute(() -> mScheduledPostDao.updateStatus(sp.id, "published"));
                            }
                        });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // EVENTS  (v31)
    // ─────────────────────────────────────────────────────────────

    public LiveData<List<CommunityEventEntity>> observeEvents(String communityId) {
        syncEvents(communityId);
        return mEventDao.observeEvents(communityId);
    }

    public LiveData<List<CommunityEventEntity>> observeUpcomingEvents(String communityId, long nowMs) {
        syncEvents(communityId);
        return mEventDao.observeUpcomingEvents(communityId, nowMs);
    }

    private void syncEvents(String communityId) {
        communitiesRef().child(communityId).child("events")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        if (s == null) return;
                        List<CommunityEventEntity> list = new ArrayList<>();
                        for (DataSnapshot es : s.getChildren()) {
                            CommunityEventEntity ev = new CommunityEventEntity();
                            ev.id = es.getKey(); ev.communityId = communityId;
                            ev.title = es.child("title").getValue(String.class);
                            ev.description = es.child("description").getValue(String.class);
                            ev.location = es.child("location").getValue(String.class);
                            ev.createdByUid = es.child("createdByUid").getValue(String.class);
                            ev.createdByName = es.child("createdByName").getValue(String.class);
                            ev.startTimeMs = longOrZero(es.child("startTimeMs"));
                            ev.endTimeMs   = longOrZero(es.child("endTimeMs"));
                            ev.rsvpCount   = longOrZero(es.child("rsvpCount"));
                            ev.createdAt   = longOrZero(es.child("createdAt"));
                            list.add(ev);
                        }
                        mExecutor.execute(() -> mEventDao.insertEvents(list));
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {}
                });
    }

    public void createEvent(String communityId, String title, String description, String location,
                            long startTimeMs, long endTimeMs, String createdByUid, String createdByName,
                            SimpleCallback cb) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("title", title); data.put("description", description != null ? description : "");
        data.put("location", location != null ? location : "");
        data.put("createdByUid", createdByUid); data.put("createdByName", createdByName);
        data.put("startTimeMs", startTimeMs); data.put("endTimeMs", endTimeMs);
        data.put("rsvpCount", 0L); data.put("createdAt", now);

        communitiesRef().child(communityId).child("events").child(id)
                .setValue(data, (err, ref) -> {
                    if (err != null) { cb.onComplete(false, err.getMessage()); return; }
                    CommunityEventEntity ev = new CommunityEventEntity();
                    ev.id = id; ev.communityId = communityId; ev.title = title;
                    ev.description = description; ev.location = location;
                    ev.createdByUid = createdByUid; ev.createdByName = createdByName;
                    ev.startTimeMs = startTimeMs; ev.endTimeMs = endTimeMs; ev.createdAt = now;
                    mExecutor.execute(() -> mEventDao.insertEvent(ev));
                    cb.onComplete(true, null);
                });
    }

    public void rsvpEvent(String communityId, String eventId, String uid, String status,
                          SimpleCallback cb) {
        DatabaseReference eventRef = communitiesRef().child(communityId).child("events").child(eventId);
        eventRef.child("rsvps").child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@Nullable DataSnapshot s) {
                String prevStatus = s != null ? s.getValue(String.class) : null;
                Map<String, Object> batch = new HashMap<>();
                String base = "communities/" + communityId + "/events/" + eventId;
                batch.put(base + "/rsvps/" + uid, status);
                // Increment rsvpCount only if going and wasn't already going
                if ("going".equals(status) && !"going".equals(prevStatus)) {
                    batch.put(base + "/rsvpCount", com.google.firebase.database.ServerValue.increment(1L));
                } else if (!"going".equals(status) && "going".equals(prevStatus)) {
                    batch.put(base + "/rsvpCount", com.google.firebase.database.ServerValue.increment(-1L));
                }
                mFirebase.getReference().updateChildren(batch, (err, ref) -> {
                    if (cb != null) cb.onComplete(err == null, err != null ? err.getMessage() : null);
                });
            }
            @Override public void onCancelled(@Nullable DatabaseError e) {
                if (cb != null) cb.onComplete(false, e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // IN-APP NOTIFICATIONS  (v31)
    // ─────────────────────────────────────────────────────────────

    public LiveData<List<CommunityNotificationEntity>> observeNotificationsForCommunity(
            String targetUid, String communityId) {
        syncNotifications(targetUid, communityId);
        return mNotificationDao.observeNotificationsForCommunity(targetUid, communityId);
    }

    public LiveData<Integer> observeUnreadNotificationCount(String targetUid) {
        return mNotificationDao.observeUnreadCount(targetUid);
    }

    private void syncNotifications(String targetUid, String communityId) {
        notificationsRef(targetUid).orderByChild("communityId").equalTo(communityId)
                .limitToLast(50)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        if (s == null) return;
                        List<CommunityNotificationEntity> list = new ArrayList<>();
                        for (DataSnapshot ns : s.getChildren()) {
                            CommunityNotificationEntity n = parseNotification(targetUid, ns);
                            list.add(n);
                        }
                        mExecutor.execute(() -> mNotificationDao.insertNotifications(list));
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {}
                });
    }

    private void postNotification(String targetUid, String communityId, String type,
                                  String title, String body, @Nullable String postId,
                                  String fromUid, @Nullable String fromName, @Nullable String fromPhoto) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("id", id); data.put("communityId", communityId); data.put("type", type);
        data.put("title", title); data.put("body", body != null ? body : "");
        data.put("fromUid", fromUid); data.put("fromName", fromName != null ? fromName : "");
        data.put("fromPhoto", fromPhoto != null ? fromPhoto : "");
        if (postId != null) data.put("postId", postId);
        data.put("isRead", false); data.put("createdAt", now);

        notificationsRef(targetUid).child(id).setValue(data);

        CommunityNotificationEntity n = new CommunityNotificationEntity();
        n.id = id; n.communityId = communityId; n.targetUid = targetUid;
        n.type = type; n.title = title; n.body = body; n.postId = postId;
        n.fromUid = fromUid; n.fromName = fromName; n.fromPhoto = fromPhoto;
        n.isRead = false; n.createdAt = now;
        mNotificationDao.insertNotification(n);
    }

    public void markNotificationRead(String notifId) {
        mExecutor.execute(() -> mNotificationDao.markRead(notifId));
    }

    public void markAllNotificationsRead(String uid) {
        mExecutor.execute(() -> mNotificationDao.markAllRead(uid));
    }

    // ─────────────────────────────────────────────────────────────
    // MODERATION LOG  (v31)
    // ─────────────────────────────────────────────────────────────

    public LiveData<List<CommunityModerationLogEntity>> observeModerationLog(String communityId) {
        syncModerationLog(communityId);
        return mModerationLogDao.observeLogs(communityId);
    }

    private void syncModerationLog(String communityId) {
        communitiesRef().child(communityId).child("moderation_log").limitToLast(100)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot s) {
                        if (s == null) return;
                        List<CommunityModerationLogEntity> list = new ArrayList<>();
                        for (DataSnapshot ls : s.getChildren()) {
                            CommunityModerationLogEntity log = new CommunityModerationLogEntity();
                            log.id = ls.getKey(); log.communityId = communityId;
                            log.actionByUid  = ls.child("actionByUid").getValue(String.class);
                            log.actionByName = ls.child("actionByName").getValue(String.class);
                            log.targetUid    = ls.child("targetUid").getValue(String.class);
                            log.targetName   = ls.child("targetName").getValue(String.class);
                            log.action       = ls.child("action").getValue(String.class);
                            log.reason       = ls.child("reason").getValue(String.class);
                            log.targetPostId = ls.child("targetPostId").getValue(String.class);
                            log.createdAt    = longOrZero(ls.child("createdAt"));
                            list.add(log);
                        }
                        mExecutor.execute(() -> {
                            for (CommunityModerationLogEntity l : list) mModerationLogDao.insertLog(l);
                        });
                    }
                    @Override public void onCancelled(@Nullable DatabaseError e) {}
                });
    }

    private void logModerationAction(String communityId, String adminUid, String adminName,
                                     @Nullable String targetUid, @Nullable String targetName,
                                     String action, @Nullable String reason, @Nullable String targetPostId) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("actionByUid", adminUid); data.put("actionByName", adminName != null ? adminName : "");
        data.put("targetUid", targetUid); data.put("targetName", targetName != null ? targetName : "");
        data.put("action", action); data.put("reason", reason != null ? reason : "");
        if (targetPostId != null) data.put("targetPostId", targetPostId);
        data.put("createdAt", now);

        communitiesRef().child(communityId).child("moderation_log").child(id).setValue(data);

        CommunityModerationLogEntity log = new CommunityModerationLogEntity();
        log.id = id; log.communityId = communityId; log.actionByUid = adminUid;
        log.actionByName = adminName; log.targetUid = targetUid; log.targetName = targetName;
        log.action = action; log.reason = reason; log.targetPostId = targetPostId; log.createdAt = now;
        mModerationLogDao.insertLog(log);
    }

    // ─────────────────────────────────────────────────────────────
    // PARSE HELPERS
    // ─────────────────────────────────────────────────────────────

    private CommunityEntity parseCommunity(DataSnapshot s) {
        CommunityEntity c = new CommunityEntity();
        c.id = s.getKey();
        c.name        = s.child("name").getValue(String.class);
        c.description = s.child("description").getValue(String.class);
        c.iconUrl     = s.child("iconUrl").getValue(String.class);
        c.ownerUid    = s.child("ownerUid").getValue(String.class);
        c.memberCount = longOrZero(s.child("memberCount"));
        c.groupCount  = longOrZero(s.child("groupCount"));
        c.postCount   = longOrZero(s.child("postCount"));
        c.createdAt   = longOrZero(s.child("createdAt"));
        Boolean priv  = s.child("isPrivate").getValue(Boolean.class);
        c.isPrivate   = priv != null && priv;
        Boolean invE  = s.child("inviteEnabled").getValue(Boolean.class);
        c.inviteEnabled = invE != null && invE;
        c.inviteToken = s.child("inviteToken").getValue(String.class);
        return c;
    }

    private CommunityMemberEntity parseMember(String communityId, DataSnapshot s) {
        CommunityMemberEntity m = new CommunityMemberEntity();
        m.communityId = communityId; m.uid = s.getKey();
        m.name       = s.child("name").getValue(String.class);
        m.photoUrl   = s.child("photoUrl").getValue(String.class);
        m.role       = s.child("role").getValue(String.class);
        m.joinedAt   = longOrZero(s.child("joinedAt"));
        m.badge      = s.child("badge").getValue(String.class);
        Boolean muted  = s.child("isMuted").getValue(Boolean.class);
        Boolean banned = s.child("isBanned").getValue(Boolean.class);
        m.isMuted  = muted  != null && muted;
        m.isBanned = banned != null && banned;
        return m;
    }

    private CommunityPostEntity parsePost(String communityId, DataSnapshot s) {
        CommunityPostEntity p = new CommunityPostEntity();
        p.id          = s.getKey();
        p.communityId = communityId;
        p.authorUid   = s.child("authorUid").getValue(String.class);
        p.authorName  = s.child("authorName").getValue(String.class);
        p.authorPhoto = s.child("authorPhoto").getValue(String.class);
        p.text        = s.child("text").getValue(String.class);
        p.mediaUrl    = s.child("mediaUrl").getValue(String.class);
        p.mediaType   = s.child("mediaType").getValue(String.class);
        Boolean announcement = s.child("isAnnouncement").getValue(Boolean.class);
        p.isAnnouncement = announcement != null && announcement;
        Boolean pinned = s.child("pinned").getValue(Boolean.class);
        p.pinned   = pinned != null && pinned;
        p.likeCount    = longOrZero(s.child("likeCount"));
        p.commentCount = longOrZero(s.child("commentCount"));
        p.createdAt    = longOrZero(s.child("createdAt"));
        p.scheduledAt  = longOrZero(s.child("scheduledAt"));
        CommunityPoll poll = parsePollSnapshot(s.child("poll"));
        p.pollJson = poll != null ? poll.toJson() : null;
        // v31: reaction counts
        DataSnapshot rcSnap = s.child("reactionCounts");
        if (rcSnap.exists()) {
            Map<String, Long> counts = new HashMap<>();
            for (DataSnapshot rc : rcSnap.getChildren()) {
                Long v = rc.getValue(Long.class);
                if (v != null && v > 0) counts.put(rc.getKey(), v);
            }
            p.reactionCountsJson = CommunityReaction.toJson(counts);
        }
        return p;
    }

    private CommunityNotificationEntity parseNotification(String targetUid, DataSnapshot s) {
        CommunityNotificationEntity n = new CommunityNotificationEntity();
        n.id = s.getKey(); n.targetUid = targetUid;
        n.communityId = s.child("communityId").getValue(String.class);
        n.type      = s.child("type").getValue(String.class);
        n.title     = s.child("title").getValue(String.class);
        n.body      = s.child("body").getValue(String.class);
        n.postId    = s.child("postId").getValue(String.class);
        n.fromUid   = s.child("fromUid").getValue(String.class);
        n.fromName  = s.child("fromName").getValue(String.class);
        n.fromPhoto = s.child("fromPhoto").getValue(String.class);
        Boolean read = s.child("isRead").getValue(Boolean.class);
        n.isRead    = read != null && read;
        n.createdAt = longOrZero(s.child("createdAt"));
        return n;
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
