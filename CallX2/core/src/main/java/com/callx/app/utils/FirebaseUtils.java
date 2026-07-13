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

    /**
     * Scheduled chat messages queue: scheduledMessages/{chatOrGroupId}/{scheduleId}/
     * Stores pending scheduled message metadata (text, sendAt, senderId, etc).
     * Never mixed into the live messages/{chatId} node — ChatScheduledMessageWorker
     * moves an entry here into the real chat thread once it fires.
     */
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

    /**
     * Per-message "currently viewing" node — finer-grained than
     * chatPresence above. chatViewing/{chatIdOrGroupId}/{uid} = messageId
     * of whichever message is presently scrolled into view on that user's
     * screen (cleared/removed when they leave the chat). Lets a bubble show
     * a tiny live dot exactly while the other person is looking at it,
     * instead of only knowing the screen is open.
     */
    public static DatabaseReference getChatViewingRef(String chatOrGroupId) {
        return db().getReference("chatViewing").child(chatOrGroupId);
    }

    /**
     * Per-message "currently composing a reply to" node — sibling of
     * chatViewing above, but narrower: chatTypingReply/{chatIdOrGroupId}/{uid}
     * = messageId only while that user (a) has an active reply-bar pointed
     * at that message AND (b) is actively typing (non-empty input), cleared
     * the moment either condition stops being true. Lets a single bubble
     * show a "someone is replying to this" glow, distinct from the broader
     * per-message viewing dot.
     */
    public static DatabaseReference getChatTypingReplyRef(String chatOrGroupId) {
        return db().getReference("chatTypingReply").child(chatOrGroupId);
    }

    /**
     * Per-message "currently playing" node — sibling of chatViewing above,
     * but for audio/video playback instead of scroll position:
     * chatPlayback/{chatIdOrGroupId}/{uid} = messageId of whichever voice
     * note / video that user currently has playing (removed the instant
     * playback pauses, finishes, errors out, or they leave the screen).
     * Lets a single bubble show a "listening…" / "watching…" badge exactly
     * while the other person is playing THAT message — same shared-node
     * trick as chatPresence/chatViewing, safe for both 1:1 and group chats.
     */
    public static DatabaseReference getChatPlaybackRef(String chatOrGroupId) {
        return db().getReference("chatPlayback").child(chatOrGroupId);
    }

    /**
     * Voice-note recording indicator node — the audio-version of the typing
     * indicator. chatRecording/{chatIdOrGroupId}/{uid} = true while that user
     * is actively holding the mic button and recording a voice note, removed
     * the instant they release, cancel, or send. Drives the animated
     * ll_voice_recording_strip banner on the partner's screen.
     */
    public static DatabaseReference getChatRecordingRef(String chatOrGroupId) {
        return db().getReference("chatRecording").child(chatOrGroupId);
    }

    /**
     * Live recording WAVEFORM node — companion to getChatRecordingRef's
     * plain boolean flag. chatRecordingWave/{chatIdOrGroupId}/{uid} = a
     * single quantized int (0-31) representing the CURRENT mic amplitude,
     * overwritten in place (never appended/listed) so each write stays a
     * tiny scalar payload instead of a growing array. Only written while
     * chatRecording/{..}/{uid} is true, and removed the instant recording
     * stops — see RecordingPreviewController.
     */
    public static DatabaseReference getChatRecordingWaveRef(String chatOrGroupId) {
        return db().getReference("chatRecordingWave").child(chatOrGroupId);
    }

    /**
     * Screenshot notification node — Snapchat-style.
     * chatScreenshot/{chatIdOrGroupId}/{uid} = serverTimestamp()
     * Written once when the user takes a screenshot; the partner's listener
     * fires, shows the animated red banner, then the node is removed so the
     * same screenshot never triggers a second notification on re-open.
     */
    public static DatabaseReference getChatScreenshotRef(String chatOrGroupId) {
        return db().getReference("chatScreenshot").child(chatOrGroupId);
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

    /**
     * Delivery-pending index: deliveryPending/{msgId} = {chatId, toUid, ts}
     * Written by MessageStatusSync right after a message becomes "sent";
     * removed once "delivered"/"read" is confirmed. The backend server
     * (index.js cron) scans this small index — not the whole messages
     * tree — as a fallback for messages that never get a client-side ACK.
     */
    public static DatabaseReference getDeliveryPendingRef() {
        return db().getReference("deliveryPending");
    }
}
