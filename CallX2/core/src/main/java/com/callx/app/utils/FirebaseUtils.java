package com.callx.app.utils;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.util.Arrays;

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

    public static DatabaseReference getUserRef(String uid) {
        return db().getReference("users").child(uid);
    }

    public static DatabaseReference getScheduledMessagesRef(String chatOrGroupId) {
        return db().getReference("scheduledMessages").child(chatOrGroupId);
    }

    public static DatabaseReference getMessagesRef(String chatId) {
        return db().getReference("messages").child(chatId);
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

    /**
     * Root channel metadata: channels/{channelId}/
     * Shared by ChannelRepository for all channel CRUD operations.
     */
    public static DatabaseReference getChannelsRef() {
        return db().getReference("channels");
    }

    /**
     * Single channel: channels/{channelId}/
     */
    public static DatabaseReference getChannelRef(String channelId) {
        return db().getReference("channels").child(channelId);
    }

    /**
     * User's channel follows: channelFollows/{uid}/{channelId} = true
     * Written by ChannelRepository.followChannel / unfollowChannel.
     */
    public static DatabaseReference getChannelFollowsRef(String uid) {
        return db().getReference("channelFollows").child(uid);
    }

    /**
     * Posts inside a channel: channelPosts/{channelId}/{postId}/
     * Written by ChannelRepository.postToChannel.
     */
    public static DatabaseReference getChannelPostsRef(String channelId) {
        return db().getReference("channelPosts").child(channelId);
    }

    // ── Unified Block System ───────────────────────────────────────────────

    public static DatabaseReference getBlocksRef(String uid) {
        return db().getReference("blocks").child(uid);
    }

    public static DatabaseReference getBlockRef(String myUid, String targetUid) {
        return db().getReference("blocks").child(myUid).child(targetUid);
    }

    public static String getChatId(String uid1, String uid2) {
        String[] ids = {uid1, uid2};
        Arrays.sort(ids);
        return ids[0] + "_" + ids[1];
    }

    public static DatabaseReference getChatPresenceRef(String chatOrGroupId) {
        return db().getReference("chatPresence").child(chatOrGroupId);
    }

    public static DatabaseReference getChatViewingRef(String chatOrGroupId) {
        return db().getReference("chatViewing").child(chatOrGroupId);
    }

    public static DatabaseReference getChatTypingReplyRef(String chatOrGroupId) {
        return db().getReference("chatTypingReply").child(chatOrGroupId);
    }

    public static DatabaseReference getChatPlaybackRef(String chatOrGroupId) {
        return db().getReference("chatPlayback").child(chatOrGroupId);
    }

    public static DatabaseReference getChatRecordingRef(String chatOrGroupId) {
        return db().getReference("chatRecording").child(chatOrGroupId);
    }

    public static DatabaseReference getChatRecordingWaveRef(String chatOrGroupId) {
        return db().getReference("chatRecordingWave").child(chatOrGroupId);
    }

    public static DatabaseReference getChatScreenshotRef(String chatOrGroupId) {
        return db().getReference("chatScreenshot").child(chatOrGroupId);
    }

    // ── Reels ─────────────────────────────────────────────────────────────

    public static DatabaseReference getReelsRef() {
        return db().getReference("reels");
    }

    public static DatabaseReference getReelLikesRef(String reelId) {
        return db().getReference("reelLikes").child(reelId);
    }

    public static DatabaseReference getReelCommentsRef(String reelId) {
        return db().getReference("reelComments").child(reelId);
    }

    public static DatabaseReference getReelSavesRef(String uid) {
        return db().getReference("reelSaves").child(uid);
    }

    public static DatabaseReference getReelViewsRef(String reelId) {
        return db().getReference("reelViews").child(reelId);
    }

    public static DatabaseReference getReelFollowsRef(String followerUid) {
        return db().getReference("reelFollows").child(followerUid);
    }

    public static DatabaseReference getReelReactionsRef(String reelId) {
        return db().getReference("reelReactions").child(reelId);
    }

    public static DatabaseReference getReelWatchHistoryRef(String uid) {
        return db().getReference("reelWatchHistory").child(uid);
    }

    public static DatabaseReference getReelWatchProgressRef(String uid) {
        return db().getReference("reelWatchProgress").child(uid);
    }

    public static DatabaseReference getReelsByUserRef(String uid) {
        return db().getReference("reelsByUser").child(uid);
    }

    public static DatabaseReference getReelLikedByUserRef(String uid) {
        return db().getReference("reelLikedByUser").child(uid);
    }

    public static DatabaseReference getReelDraftsRef(String uid) {
        return db().getReference("reelDrafts").child(uid);
    }

    public static DatabaseReference getReelReportsRef(String reelId) {
        return db().getReference("reelReports").child(reelId);
    }

    public static DatabaseReference getMusicLibraryRef() {
        return db().getReference("musicLibrary");
    }

    public static DatabaseReference getReelDuetsRef(String originalReelId) {
        return db().getReference("reelDuets").child(originalReelId);
    }

    public static DatabaseReference getReelSavesIndexRef(String reelId) {
        return db().getReference("reelSavesIndex").child(reelId);
    }

    public static DatabaseReference getTrendingHashtagsRef() {
        return db().getReference("trendingHashtags");
    }

    public static DatabaseReference getScheduledReelsRef(String uid) {
        return db().getReference("scheduledReels").child(uid);
    }

    public static DatabaseReference getReelFollowersRef(String uid) {
        return db().getReference("reelFollowers").child(uid);
    }

    public static DatabaseReference getSavedSoundsRef(String uid) {
        return db().getReference("users").child(uid).child("saved_sounds");
    }

    public static DatabaseReference getReelVideoRepliesRef(String reelId, String commentId) {
        return db().getReference("reelVideoReplies").child(reelId).child(commentId);
    }

    public static DatabaseReference getReelSubtitlesRef(String reelId) {
        return db().getReference("reelSubtitles").child(reelId);
    }

    public static DatabaseReference getReelRepostsRef(String reelId) {
        return db().getReference("reelReposts").child(reelId);
    }

    public static DatabaseReference getReelRepostsByUserRef(String uid) {
        return db().getReference("userReposts").child(uid);
    }

    public static DatabaseReference getDeliveryPendingRef() {
        return db().getReference("deliveryPending");
    }
}
