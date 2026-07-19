package com.callx.app.utils;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class FirebaseUtils {

    public static FirebaseDatabase db() {
        return FirebaseDatabase.getInstance(Constants.DB_URL);
    }

    public static String getCurrentUid() {
        com.google.firebase.auth.FirebaseUser _fu1 = FirebaseAuth.getInstance().getCurrentUser();
        return _fu1 != null ? _fu1.getUid() : "";
    }

    public static String getCurrentName() {
        com.google.firebase.auth.FirebaseUser _fu2 = FirebaseAuth.getInstance().getCurrentUser();
        if (_fu2 == null) return "";
        String n = _fu2.getDisplayName();
        return (n == null || n.isEmpty()) ? "CallX User" : n;
    }

    public static String getCurrentPhotoUrl() {
        com.google.firebase.auth.FirebaseUser fu = FirebaseAuth.getInstance().getCurrentUser();
        if (fu == null || fu.getPhotoUrl() == null) return "";
        return fu.getPhotoUrl().toString();
    }

    public static DatabaseReference getUserRef(String uid) {
        return db().getReference("users").child(uid);
    }

    public static DatabaseReference getScheduledMessagesRef(String chatOrGroupId) {
        return db().getReference("scheduledMessages").child(chatOrGroupId);
    }

    public static DatabaseReference getMessagesRef(String chatId) {
        return db().getReference("messages").child(chatId);
    }

    /** Deterministic 1:1 chat id — same ordering convention used throughout the app. */
    public static String getChatId(String uid1, String uid2) {
        return uid1.compareTo(uid2) < 0 ? uid1 + "_" + uid2 : uid2 + "_" + uid1;
    }

    /** Server-fallback index of not-yet-delivered messages (see MessageStatusSync). */
    public static DatabaseReference getDeliveryPendingRef() {
        return db().getReference("deliveryPending");
    }

    public static DatabaseReference getContactsRef(String uid) {
        return db().getReference("contacts").child(uid);
    }

    public static DatabaseReference getRequestsRef(String uid) {
        return db().getReference("requests").child(uid);
    }

    public static DatabaseReference getCallsRef(String uid) {
        return db().getReference("calls").child(uid);
    }

    public static DatabaseReference getGroupsRef() {
        return db().getReference("groups");
    }

    public static DatabaseReference getGroupMessagesRef(String groupId) {
        return db().getReference("groupMessages").child(groupId);
    }

    public static DatabaseReference getUserGroupsRef(String uid) {
        return db().getReference("userGroups").child(uid);
    }

    public static DatabaseReference getGroupTypingRef(String groupId) {
        return db().getReference("groups").child(groupId).child("typing");
    }

    public static DatabaseReference getGroupMembersRef(String groupId) {
        return db().getReference("groups").child(groupId).child("members");
    }

    public static DatabaseReference getStatusRef() {
        return db().getReference("status");
    }

    public static DatabaseReference getUserStatusRef(String ownerUid) {
        return db().getReference("status").child(ownerUid);
    }

    public static DatabaseReference getStatusSeenByRef(String ownerUid, String statusId) {
        return db().getReference("status").child(ownerUid).child(statusId).child("seenBy");
    }

    public static DatabaseReference getStatusSeenRef(String viewerUid) {
        return db().getReference("statusSeen").child(viewerUid);
    }

    public static DatabaseReference getStatusReactionRef(String ownerUid, String statusId,
                                                          String reactorUid) {
        return db().getReference("status").child(ownerUid).child(statusId)
                   .child("reactions").child(reactorUid);
    }

    public static DatabaseReference getStatusHighlightsRef(String ownerUid) {
        return db().getReference("statusHighlights").child(ownerUid);
    }

    // ── Channels ──────────────────────────────────────────────────────────────

    /** Root channel metadata: channels/{channelId}/ */
    public static DatabaseReference getChannelsRef() {
        return db().getReference("channels");
    }

    /** Single channel: channels/{channelId}/ */
    public static DatabaseReference getChannelRef(String channelId) {
        return db().getReference("channels").child(channelId);
    }

    /** User's channel follows: channelFollows/{uid}/{channelId} = true */
    public static DatabaseReference getChannelFollowsRef(String uid) {
        return db().getReference("channelFollows").child(uid);
    }

    /** Channel posts: channelPosts/{channelId}/{postId}/ */
    public static DatabaseReference getChannelPostsRef(String channelId) {
        return db().getReference("channelPosts").child(channelId);
    }

    /** Single channel post: channelPosts/{channelId}/{postId}/ */
    public static DatabaseReference getChannelPostRef(String channelId, String postId) {
        return db().getReference("channelPosts").child(channelId).child(postId);
    }

    /** Channel post reactions: channelPosts/{channelId}/{postId}/reactions/{uid} */
    public static DatabaseReference getChannelPostReactionRef(String channelId, String postId, String uid) {
        return db().getReference("channelPosts").child(channelId).child(postId)
                   .child("reactions").child(uid);
    }

    /** Channel post poll votes: channelPosts/{channelId}/{postId}/pollVotes/{uid} */
    public static DatabaseReference getChannelPostPollVoteRef(String channelId, String postId, String uid) {
        return db().getReference("channelPosts").child(channelId).child(postId)
                   .child("pollVotes").child(uid);
    }

    /** Channel admins: channelAdmins/{channelId}/{uid} = role */
    public static DatabaseReference getChannelAdminsRef(String channelId) {
        return db().getReference("channelAdmins").child(channelId);
    }

    /** Channel followers: channelFollowers/{channelId}/{uid} = {joinedAt, uid} */
    public static DatabaseReference getChannelFollowersRef(String channelId) {
        return db().getReference("channelFollowers").child(channelId);
    }

    /** A specific follower entry: channelFollowers/{channelId}/{uid} */
    public static DatabaseReference getChannelFollowerRef(String channelId, String uid) {
        return db().getReference("channelFollowers").child(channelId).child(uid);
    }

    /** Channel reports: channelReports/{channelId}/{reportId} */
    public static DatabaseReference getChannelReportsRef(String channelId) {
        return db().getReference("channelReports").child(channelId);
    }

    /** Post reports: channelPostReports/{channelId}/{postId}/{reportId} */
    public static DatabaseReference getChannelPostReportsRef(String channelId, String postId) {
        return db().getReference("channelPostReports").child(channelId).child(postId);
    }

    /** Channel invite codes: channelInviteCodes/{code} = channelId */
    public static DatabaseReference getChannelInviteCodesRef() {
        return db().getReference("channelInviteCodes");
    }

    /** Specific invite code: channelInviteCodes/{code} */
    public static DatabaseReference getChannelInviteCodeRef(String code) {
        return db().getReference("channelInviteCodes").child(code);
    }

    /** Muted channels for a user: channelMutes/{uid}/{channelId} = {mutedUntil} */
    public static DatabaseReference getChannelMutesRef(String uid) {
        return db().getReference("channelMutes").child(uid);
    }

    /** User's channel mute entry: channelMutes/{uid}/{channelId} */
    public static DatabaseReference getChannelMuteRef(String uid, String channelId) {
        return db().getReference("channelMutes").child(uid).child(channelId);
    }

    /** Last read timestamp: channelLastSeen/{uid}/{channelId} = timestamp */
    public static DatabaseReference getChannelLastSeenRef(String uid, String channelId) {
        return db().getReference("channelLastSeen").child(uid).child(channelId);
    }

    /** Channel scheduled posts: channelScheduled/{channelId}/{postId} */
    public static DatabaseReference getChannelScheduledRef(String channelId) {
        return db().getReference("channelScheduled").child(channelId);
    }

    /** Channel analytics: channelAnalytics/{channelId}/ */
    public static DatabaseReference getChannelAnalyticsRef(String channelId) {
        return db().getReference("channelAnalytics").child(channelId);
    }

    /** Blocked followers: channelBlockedFollowers/{channelId}/{uid} = true */
    public static DatabaseReference getChannelBlockedFollowersRef(String channelId) {
        return db().getReference("channelBlockedFollowers").child(channelId);
    }

    /** Per-user channel notification prefs: channelNotifPrefs/{uid}/{channelId} */
    public static DatabaseReference getChannelNotifPrefsRef(String uid, String channelId) {
        return db().getReference("channelNotifPrefs").child(uid).child(channelId);
    }
}
