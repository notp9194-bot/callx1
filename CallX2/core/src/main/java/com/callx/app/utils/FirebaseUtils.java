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

    // ── Unified Block System ───────────────────────────────────────────────

    /**
     * Unified block list: blocks/{blockerUid}/{blockedUid} = true
     * This single path is used across ALL modules (chat, reels, calls, status, X DM).
     * Legacy paths ("blocked/" and "user_blocked/") should be migrated here.
     */
    public static DatabaseReference getBlocksRef(String uid) {
        return db().getReference("blocks").child(uid);
    }

    /** Check if myUid has blocked targetUid */
    public static DatabaseReference getBlockRef(String myUid, String targetUid) {
        return db().getReference("blocks").child(myUid).child(targetUid);
    }

    public static String getChatId(String uid1, String uid2) {
        String[] ids = {uid1, uid2};
        Arrays.sort(ids);
        return ids[0] + "_" + ids[1];
    }

    /**
     * "Watching banner" presence node — shared by 1:1 chats AND group chats:
     * chatPresence/{chatIdOrGroupId}/{uid} = true while that user has the
     * given chat screen open & foregrounded. 1:1 chatIds are "uidA_uidB"
     * (see getChatId above); group ids are Firebase push keys — the two
     * never collide, so both can safely share this one node.
     */
    public static DatabaseReference getChatPresenceRef(String chatOrGroupId) {
        return db().getReference("chatPresence").child(chatOrGroupId);
    }

    // ── Reels ─────────────────────────────────────────────────────────────

    /** Root reels node: reels/{reelId}/ */
    public static DatabaseReference getReelsRef() {
        return db().getReference("reels");
    }

    /** Per-reel likes: reelLikes/{reelId}/{uid} = true */
    public static DatabaseReference getReelLikesRef(String reelId) {
        return db().getReference("reelLikes").child(reelId);
    }

    /** Per-reel comments: reelComments/{reelId}/{commentId}/ */
    public static DatabaseReference getReelCommentsRef(String reelId) {
        return db().getReference("reelComments").child(reelId);
    }

    /** Per-user saved reels: reelSaves/{uid}/{reelId} = true */
    public static DatabaseReference getReelSavesRef(String uid) {
        return db().getReference("reelSaves").child(uid);
    }

    /** Per-reel views: reelViews/{reelId}/{uid} = true */
    public static DatabaseReference getReelViewsRef(String reelId) {
        return db().getReference("reelViews").child(reelId);
    }

    /** Per-user reel follows: reelFollows/{followerUid}/{followedUid} = true */
    public static DatabaseReference getReelFollowsRef(String followerUid) {
        return db().getReference("reelFollows").child(followerUid);
    }

    /** Per-reel reactions: reelReactions/{reelId}/{uid} = emoji */
    public static DatabaseReference getReelReactionsRef(String reelId) {
        return db().getReference("reelReactions").child(reelId);
    }

    /** Per-user watch history: reelWatchHistory/{uid}/{reelId} = timestamp */
    public static DatabaseReference getReelWatchHistoryRef(String uid) {
        return db().getReference("reelWatchHistory").child(uid);
    }

    /** Per-user watch progress: reelWatchProgress/{uid}/{reelId} = int percentage (0-100) */
    public static DatabaseReference getReelWatchProgressRef(String uid) {
        return db().getReference("reelWatchProgress").child(uid);
    }

    /** Per-user reels index: reelsByUser/{uid}/{reelId} = true */
    public static DatabaseReference getReelsByUserRef(String uid) {
        return db().getReference("reelsByUser").child(uid);
    }

    // ── NEW: Production Reel Nodes ─────────────────────────────────────────

    /**
     * Reverse index for liked reels per user.
     * reelLikedByUser/{uid}/{reelId} = timestamp
     * Maintained alongside reelLikes for fast user-level queries.
     */
    public static DatabaseReference getReelLikedByUserRef(String uid) {
        return db().getReference("reelLikedByUser").child(uid);
    }

    /**
     * User reel drafts: reelDrafts/{uid}/{draftId}/
     * Stores unsaved/in-progress reels before they are posted.
     */
    public static DatabaseReference getReelDraftsRef(String uid) {
        return db().getReference("reelDrafts").child(uid);
    }

    /**
     * Reel reports: reelReports/{reelId}/{reporterUid}/
     * Each child is a report object with reason, details, timestamp.
     */
    public static DatabaseReference getReelReportsRef(String reelId) {
        return db().getReference("reelReports").child(reelId);
    }

    /**
     * Music library: musicLibrary/{trackId}/
     * Stores MusicTrack objects (name, artist, genre, audioUrl, coverUrl).
     */
    public static DatabaseReference getMusicLibraryRef() {
        return db().getReference("musicLibrary");
    }

    /**
     * Reel duets index: reelDuets/{originalReelId}/{duetReelId} = true
     * Tracks which reels were created as duets of another.
     */
    public static DatabaseReference getReelDuetsRef(String originalReelId) {
        return db().getReference("reelDuets").child(originalReelId);
    }

    /**
     * Per-reel saves index: reelSavesIndex/{reelId}/{uid} = true
     * Allows counting saves without scanning all users.
     */
    public static DatabaseReference getReelSavesIndexRef(String reelId) {
        return db().getReference("reelSavesIndex").child(reelId);
    }

    // ── NEW v2: Production Reel Nodes ──────────────────────────────────────

    /**
     * Trending hashtags: trendingHashtags/{tag}/count = long
     * Updated server-side or via Cloud Functions on each reel post.
     */
    public static DatabaseReference getTrendingHashtagsRef() {
        return db().getReference("trendingHashtags");
    }

    /**
     * Scheduled reels queue: scheduledReels/{uid}/{scheduleId}/
     * Stores pending scheduled reel metadata (videoUri, caption, scheduledAt, status).
     */
    public static DatabaseReference getScheduledReelsRef(String uid) {
        return db().getReference("scheduledReels").child(uid);
    }

    /**
     * Per-user reel followers: reelFollowers/{uid}/{followerUid} = true
     * Tracks who follows a creator's reel content specifically.
     */
    public static DatabaseReference getReelFollowersRef(String uid) {
        return db().getReference("reelFollowers").child(uid);
    }

    /**
     * Saved sounds per user: users/{uid}/saved_sounds/{soundId} = soundTitle
     * Stores sound bookmarks with the sound title for offline display.
     */
    public static DatabaseReference getSavedSoundsRef(String uid) {
        return db().getReference("users").child(uid).child("saved_sounds");
    }

    /**
     * Video replies index: reelVideoReplies/{reelId}/{commentId}/{replyReelId} = true
     * Links a reply reel back to the original comment it replied to.
     */
    public static DatabaseReference getReelVideoRepliesRef(String reelId, String commentId) {
        return db().getReference("reelVideoReplies").child(reelId).child(commentId);
    }

    /**
     * Reel subtitles: reelSubtitles/{reelId}/
     * Stores subtitle JSON string, enabled flag, font size, style.
     */
    public static DatabaseReference getReelSubtitlesRef(String reelId) {
        return db().getReference("reelSubtitles").child(reelId);
    }

    // ── NEW v3: Repost nodes ───────────────────────────────────────────────

    /**
     * Repost index by reel: reelReposts/{reelId}/{reposterId} = timestamp
     * Written by ReelRepostWorker. Used to check if user has reposted + count reposts.
     */
    public static DatabaseReference getReelRepostsRef(String reelId) {
        return db().getReference("reelReposts").child(reelId);
    }

    /**
     * Per-user repost history: userReposts/{uid}/{reelId} = timestamp
     * Written alongside reelReposts for fast user-level "what did I repost?" queries.
     */
    public static DatabaseReference getReelRepostsByUserRef(String uid) {
        return db().getReference("userReposts").child(uid);
    }
}
