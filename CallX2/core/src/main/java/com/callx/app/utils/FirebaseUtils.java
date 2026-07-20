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

    /** Alias for getCurrentUid() — used by newer feature modules. */
    public static String getMyUid() { return getCurrentUid(); }

    /** Alias for getCurrentName() — used by newer feature modules. */
    public static String getMyDisplayName() { return getCurrentName(); }

    /** Alias for getCurrentPhotoUrl() — used by newer feature modules. */
    public static String getMyIconUrl() { return getCurrentPhotoUrl(); }

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

    /** Per-user 1:1 blocklist: blocks/{uid}/{blockedUid} = true */
    public static DatabaseReference getBlocksRef(String uid) {
        return db().getReference("blocks").child(uid);
    }

    // ── Chat presence (typing / viewing / recording / playback / screenshot) ───

    public static DatabaseReference getChatPresenceRef(String chatOrGroupId) {
        return db().getReference("chatPresence").child(chatOrGroupId);
    }

    public static DatabaseReference getChatTypingReplyRef(String chatOrGroupId) {
        return db().getReference("chatTypingReply").child(chatOrGroupId);
    }

    public static DatabaseReference getChatViewingRef(String chatOrGroupId) {
        return db().getReference("chatViewing").child(chatOrGroupId);
    }

    public static DatabaseReference getChatRecordingRef(String chatOrGroupId) {
        return db().getReference("chatRecording").child(chatOrGroupId);
    }

    public static DatabaseReference getChatRecordingWaveRef(String chatOrGroupId) {
        return db().getReference("chatRecordingWave").child(chatOrGroupId);
    }

    public static DatabaseReference getChatPlaybackRef(String chatOrGroupId) {
        return db().getReference("chatPlayback").child(chatOrGroupId);
    }

    public static DatabaseReference getChatScreenshotRef(String chatOrGroupId) {
        return db().getReference("chatScreenshot").child(chatOrGroupId);
    }

    // ── Reels ────────────────────────────────────────────────────────────────

    /** Root reel metadata: reels/{reelId}/ */
    public static DatabaseReference getReelsRef() {
        return db().getReference("reels");
    }

    /** Per-user reel index (profile grid): reelsByUser/{uid}/{reelId} */
    public static DatabaseReference getReelsByUserRef(String uid) {
        return db().getReference("reelsByUser").child(uid);
    }

    public static DatabaseReference getReelCommentsRef(String reelId) {
        return db().getReference("reelComments").child(reelId);
    }

    public static DatabaseReference getReelLikesRef(String reelId) {
        return db().getReference("reelLikes").child(reelId);
    }

    public static DatabaseReference getReelSavesRef(String uid) {
        return db().getReference("reelSaves").child(uid);
    }

    public static DatabaseReference getReelRepostsRef(String reelId) {
        return db().getReference("reelReposts").child(reelId);
    }

    public static DatabaseReference getReelReportsRef(String reelId) {
        return db().getReference("reelReports").child(reelId);
    }

    /** uid's outgoing follows: reelFollows/{uid}/{targetUid} = true */
    public static DatabaseReference getReelFollowsRef(String uid) {
        return db().getReference("reelFollows").child(uid);
    }

    /** uid's incoming followers: reelFollowers/{uid}/{followerUid} = true */
    public static DatabaseReference getReelFollowersRef(String uid) {
        return db().getReference("reelFollowers").child(uid);
    }

    public static DatabaseReference getTrendingHashtagsRef() {
        return db().getReference("trendingHashtags");
    }

    public static DatabaseReference getMusicLibraryRef() {
        return db().getReference("musicLibrary");
    }

    /** Per-user unpublished drafts: reelDrafts/{uid}/{draftId} */
    public static DatabaseReference getReelDraftsRef(String uid) {
        return db().getReference("reelDrafts").child(uid);
    }

    /** Reels a user has liked (profile "liked" tab index): reelLikedByUser/{uid}/{reelId} */
    public static DatabaseReference getReelLikedByUserRef(String uid) {
        return db().getReference("reelLikedByUser").child(uid);
    }

    public static DatabaseReference getReelReactionsRef(String reelId) {
        return db().getReference("reelReactions").child(reelId);
    }

    /** Reels a user has reposted (profile "reposts" tab index): reelRepostsByUser/{uid}/{reelId} */
    public static DatabaseReference getReelRepostsByUserRef(String uid) {
        return db().getReference("reelRepostsByUser").child(uid);
    }

    /** Per-reel saved-by index: reelSavesIndex/{reelId}/{uid} = true */
    public static DatabaseReference getReelSavesIndexRef(String reelId) {
        return db().getReference("reelSavesIndex").child(reelId);
    }

    public static DatabaseReference getReelViewsRef(String reelId) {
        return db().getReference("reelViews").child(reelId);
    }

    public static DatabaseReference getReelWatchHistoryRef(String uid) {
        return db().getReference("reelWatchHistory").child(uid);
    }

    public static DatabaseReference getReelWatchProgressRef(String uid) {
        return db().getReference("reelWatchProgress").child(uid);
    }

    public static DatabaseReference getScheduledReelsRef(String uid) {
        return db().getReference("scheduledReels").child(uid);
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

    // ── FCM Push Notification Helpers ─────────────────────────────────────────
    // Writes a notification payload to notifyQueue/{recipientUid}/{pushId}.
    // A Cloud Function (or a lightweight WorkManager background worker) picks
    // these up and dispatches them via FCM HTTP v1. Also calls PushNotify.send()
    // as a foreground best-effort fallback — same approach as the legacy
    // PushNotify usage elsewhere in the app.

    /**
     * Sends a push notification to a single user.
     * Writes to notifyQueue/{recipientUid}/{pushId} and calls PushNotify.send()
     * as a foreground fallback.
     */
    public static void sendPushToUser(String recipientUid, String title, String body,
                                       java.util.Map<String, String> data) {
        if (recipientUid == null || recipientUid.isEmpty()) return;
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("to", recipientUid);
        payload.put("title", title != null ? title : "");
        payload.put("body", body != null ? body : "");
        payload.put("sentAt", System.currentTimeMillis());
        if (data != null) payload.put("data", data);
        // Server-side dispatch queue
        db().getReference("notifyQueue").child(recipientUid).push().setValue(payload);
        // Foreground fallback
        try {
            com.callx.app.utils.PushNotify.send(recipientUid, title, body, data);
        } catch (Exception ignored) {}
    }

    /**
     * Sends a push notification to all group members except the sender.
     */
    public static void sendGroupPushNotification(String groupId,
                                                  java.util.Collection<String> memberUids,
                                                  String senderUid,
                                                  String title, String body,
                                                  java.util.Map<String, String> data) {
        if (memberUids == null || memberUids.isEmpty()) return;
        java.util.Map<String, String> enriched = new java.util.HashMap<>();
        if (data != null) enriched.putAll(data);
        if (groupId != null) enriched.put("groupId", groupId);
        enriched.put("type", "group_message");
        for (String uid : memberUids) {
            if (uid == null || uid.isEmpty() || uid.equals(senderUid)) continue;
            sendPushToUser(uid, title, body, enriched);
        }
    }

    /**
     * Sends a push notification for a 1:1 chat message.
     */
    public static void sendChatPushNotification(String recipientUid, String senderName,
                                                  String body, String chatId) {
        java.util.Map<String, String> data = new java.util.HashMap<>();
        data.put("chatId", chatId != null ? chatId : "");
        data.put("type", "chat_message");
        sendPushToUser(recipientUid, senderName, body, data);
    }

    /**
     * Increments the unread notification badge count for a user (transactionally).
     */
    public static void incrementNotifyBadge(String uid) {
        if (uid == null || uid.isEmpty()) return;
        db().getReference("notifyBadge").child(uid)
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @Override
                public com.google.firebase.database.Transaction.Result doTransaction(
                        com.google.firebase.database.MutableData current) {
                    Long val = current.getValue(Long.class);
                    current.setValue(val == null ? 1L : val + 1L);
                    return com.google.firebase.database.Transaction.success(current);
                }
                @Override public void onComplete(com.google.firebase.database.DatabaseError e,
                                                  boolean committed,
                                                  com.google.firebase.database.DataSnapshot s) {}
            });
    }

    /**
     * Resets the unread badge count to 0 for a user — call when the user opens the chat.
     */
    public static void resetNotifyBadge(String uid) {
        if (uid == null || uid.isEmpty()) return;
        db().getReference("notifyBadge").child(uid).setValue(0);
    }
}
