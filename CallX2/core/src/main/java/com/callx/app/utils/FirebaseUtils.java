package com.callx.app.utils;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class FirebaseUtils {

    public static FirebaseDatabase db() {
        return FirebaseDatabase.getInstance(Constants.DB_URL);
    }

    /**
     * Returns the signed-in uid data reads/writes should use. Linked/companion
     * devices (see core/linkeddevice/LinkedDeviceManager + LinkDeviceQrActivity)
     * sign in with a real Firebase Auth custom token for the primary account,
     * so this already returns the right uid on those devices with no extra
     * indirection — exactly like a second WhatsApp device.
     */
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

    // ── Verification (implemented in :core-lite) ─────────────────────────
    // Keep these aliases so the main app and feature modules retain their
    // existing imports while the implementation stays out of the heavy core.
    public static final String VERIFICATION_REQUESTS_PATH =
        com.callx.app.corelite.FirebaseUtils.VERIFICATION_REQUESTS_PATH;
    public static final String ADMINS_PATH =
        com.callx.app.corelite.FirebaseUtils.ADMINS_PATH;
    public static final String FIELD_IS_VERIFIED =
        com.callx.app.corelite.FirebaseUtils.FIELD_IS_VERIFIED;
    public static final String STATUS_PENDING =
        com.callx.app.corelite.FirebaseUtils.STATUS_PENDING;
    public static final String STATUS_APPROVED =
        com.callx.app.corelite.FirebaseUtils.STATUS_APPROVED;
    public static final String STATUS_REJECTED =
        com.callx.app.corelite.FirebaseUtils.STATUS_REJECTED;

    public static DatabaseReference getVerificationRequestsRef() {
        return com.callx.app.corelite.FirebaseUtils.getVerificationRequestsRef();
    }

    public static DatabaseReference getVerificationRequestRef(String uid) {
        return com.callx.app.corelite.FirebaseUtils.getVerificationRequestRef(uid);
    }

    public static DatabaseReference getAdminsRef() {
        return com.callx.app.corelite.FirebaseUtils.getAdminsRef();
    }

    public static DatabaseReference getIsVerifiedRef(String uid) {
        return com.callx.app.corelite.FirebaseUtils.getIsVerifiedRef(uid);
    }

    // ── Linked devices (WhatsApp-Web-style companion sessions) ─────────────

    /** users/{uid}/linkedDevices — realtime list of approved companion sessions. */
    public static DatabaseReference getLinkedDevicesRef(String uid) {
        return getUserRef(uid).child("linkedDevices");
    }

    /** users/{uid}/linkedDevices/{deviceId} */
    public static DatabaseReference getLinkedDeviceRef(String uid, String deviceId) {
        return getLinkedDevicesRef(uid).child(deviceId);
    }

    /** pairingSessions/{pairingCode} — short-lived QR handshake node, top-level (pre-auth-scoped). */
    public static DatabaseReference getPairingSessionRef(String pairingCode) {
        return db().getReference("pairingSessions").child(pairingCode);
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

    /**
     * Dedicated like counter: reels/{reelId}/likesCount — kept in sync by
     * atomic transactions in ReelSocialController.toggleLike(). UI that only
     * needs the NUMBER of likes (header counts, live badges) should listen
     * here instead of on getReelLikesRef(), which is the full likers list
     * (one child per uid) and gets heavier to sync with every single like/
     * unlike as a reel goes viral.
     */
    public static DatabaseReference getReelLikesCountRef(String reelId) {
        return getReelsRef().child(reelId).child("likesCount");
    }

    /**
     * Denormalized liker snapshot: reelLikeMeta/{reelId}/{uid} = {name, username, photo, verified, ts}.
     * Written alongside every reelLikes/{reelId}/{uid} write (see writeReelLike()) so
     * ReelLikesBottomSheet can render a whole page of likers from ONE read of this
     * node instead of one reels/users/{uid} read per liker.
     */
    public static DatabaseReference getReelLikeMetaRef(String reelId) {
        return db().getReference("reelLikeMeta").child(reelId);
    }

    /**
     * Writes the like timestamp AND a denormalized display snapshot in one call.
     * Real N+1 fix: previously reelLikes/{reelId}/{uid} only ever held a bare
     * timestamp, so nothing was actually denormalized — ReelLikesBottomSheet had
     * to read reels/users/{uid} separately for every single liker on every page
     * open (PAGE_SIZE reads per page). Now the liker's display fields are copied
     * into reelLikeMeta at like time, so later reads are a single node fetch.
     * Likes written before this fix simply have no reelLikeMeta entry; callers
     * fall back to a per-user read only for those.
     */
    public static void writeReelLike(String reelId, String uid) {
        if (reelId == null || uid == null || uid.isEmpty()) return;
        final long now = System.currentTimeMillis();
        getReelLikesRef(reelId).child(uid).setValue(now);

        db().getReference("reels/users").child(uid).get().addOnSuccessListener(s -> {
            String name  = s.child("displayName").getValue(String.class);
            String user  = s.child("handle").getValue(String.class);
            String thumb = s.child("thumbUrl").getValue(String.class);
            String photo = s.child("photoUrl").getValue(String.class);
            Boolean ver  = s.child("verified").getValue(Boolean.class);
            String resolvedPhoto = (thumb != null && !thumb.isEmpty()) ? thumb : (photo != null ? photo : "");

            java.util.Map<String, Object> meta = new java.util.HashMap<>();
            meta.put("name", name != null ? name : "User");
            meta.put("username", user != null ? user : "");
            meta.put("photo", resolvedPhoto);
            meta.put("verified", Boolean.TRUE.equals(ver));
            meta.put("ts", now);
            getReelLikeMetaRef(reelId).child(uid).setValue(meta);
        });
    }

    /** Removes both the like and its denormalized snapshot together. */
    public static void removeReelLike(String reelId, String uid) {
        if (reelId == null || uid == null || uid.isEmpty()) return;
        getReelLikesRef(reelId).child(uid).removeValue();
        getReelLikeMetaRef(reelId).child(uid).removeValue();
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

    /** Admin-configurable thresholds for the Reels "Viral" audio tab —
     *  appConfig/reelsViral/{windowDays, minUses} — lets ops retune what
     *  counts as viral (window + usage cutoff) without an app release.
     *  See ReelTrendingAudioActivity#loadViralConfig(). */
    public static DatabaseReference getReelsViralConfigRef() {
        return db().getReference("appConfig").child("reelsViral");
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

    /**
     * Reels a user has reposted (profile "reposts" tab index): userReposts/{uid}/{reelId}
     * NOTE: all repost-write paths (RepostWithCaptionActivity, RepostQuoteActivity,
     * ReelSocialController, HomeFragment, ReelRepostWorker) write to "userReposts" — this
     * used to point at a different, never-written "reelRepostsByUser" node, which is why
     * the profile grid's Repost tab stayed empty even after reposting.
     */
    public static DatabaseReference getReelRepostsByUserRef(String uid) {
        return db().getReference("userReposts").child(uid);
    }

    /**
     * Reels a user has duetted (profile "duet" tab index): userDuetReels/{uid}/{reelId} = timestamp.
     * {reelId} is the NEW duet reel the user recorded/published (owned reel), written from
     * ReelUploadActivity right after the duet reel itself is published to "reels"/"reelsByUser".
     */
    public static DatabaseReference getUserDuetReelsRef(String uid) {
        return db().getReference("userDuetReels").child(uid);
    }

    /**
     * Reels a user is part of via a Collab Repost (profile "collab repost" tab index):
     * userCollabRepostReels/{uid}/{reelId} = timestamp. Written for BOTH the initiator and the
     * collaborator when a collab repost invite is accepted (see CollabRepostAcceptActivity),
     * since {reelId} — the joint collab reel — already lives on both users' reelsByUser index.
     */
    public static DatabaseReference getUserCollabRepostReelsRef(String uid) {
        return db().getReference("userCollabRepostReels").child(uid);
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

    /**
     * groupSenderKeys/{groupId}/{recipientUid}/{fromUid} = ciphertext (sealed
     * with the 1:1 X3DH/Double-Ratchet session between fromUid and
     * recipientUid — see GroupE2EManager). Each member's mailbox
     * (groupSenderKeys/{groupId}/{recipientUid}) should be locked down in
     * Firebase security rules so only recipientUid can read it, and only an
     * existing member of the group can write into it — mirrors the
     * e2e_prekeys lockdown pattern used for 1:1 (see index.js), except this
     * one IS safe as a direct client write/read since each entry is already
     * ciphertext sealed to one specific recipient, not raw key material.
     */
    public static DatabaseReference getGroupSenderKeysRef(String groupId) {
        return db().getReference("groupSenderKeys").child(groupId);
    }

    /**
     * groupSenderKeyRequests/{targetUid}/{groupId}/{requesterUid} =
     * ServerValue.TIMESTAMP. Mirrors e2e_rekey_requests but for GROUP Sender
     * Keys (see GroupE2EManager#requestSenderKeyResend /
     * #listenForGroupKeyRequests). A member who is missing a specific
     * sender's current Sender Key (e.g. they reinstalled/cleared data and
     * lost their locally-received copy) writes under that sender's own uid
     * to ask them to redistribute — carries no key material, just "please
     * resend your group key to me for this group."
     */
    public static DatabaseReference getGroupSenderKeyRequestsRef(String targetUid) {
        return db().getReference("groupSenderKeyRequests").child(targetUid);
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

    /**
     * status/{ownerUid}/{statusId}/replies/{pushId} — public-on-the-status-node copy
     * of every text reply sent to that status (Instagram-style). A normal status
     * reply is otherwise only a private chat DM (see StatusReplyBottomSheet#sendReply),
     * invisible to the owner unless they open the chat; this node lets
     * StatusViewerActivity show the latest replier's avatar + comment as a bottom-left
     * overlay directly on the story when the OWNER reopens it, and lets
     * StatusRepliesBottomSheet list every replier for that story.
     */
    public static DatabaseReference getStatusRepliesRef(String ownerUid, String statusId) {
        return db().getReference("status").child(ownerUid).child(statusId).child("replies");
    }

    public static DatabaseReference getStatusSeenRef(String viewerUid) {
        return db().getReference("statusSeen").child(viewerUid);
    }

    public static DatabaseReference getStatusReactionRef(String ownerUid, String statusId,
                                                          String reactorUid) {
        return db().getReference("status").child(ownerUid).child(statusId)
                   .child("reactions").child(reactorUid);
    }

    /** Scheduled statuses (not yet published): statusScheduled/{ownerUid}/{statusId} */
    public static DatabaseReference getUserStatusScheduledRef(String ownerUid) {
        return db().getReference("statusScheduled").child(ownerUid);
    }

    public static DatabaseReference getStatusHighlightsRef(String ownerUid) {
        return db().getReference("statusHighlights").child(ownerUid);
    }

    /**
     * Ref for a single viewer's answer to a 🧠 Quiz sticker on a status.
     * Mirrors getStatusReactionRef's shape — status/{ownerUid}/{statusId}/stickerVotes/{stickerIndex}/{voterUid}.
     * Used to lock a viewer into their first answer (quiz stickers can only be answered once)
     * and to restore that locked-in state when the same status is reopened.
     */
    public static DatabaseReference getStatusQuizVoteRef(String ownerUid, String statusId,
                                                          int stickerIndex, String voterUid) {
        return db().getReference("status").child(ownerUid).child(statusId)
                   .child("stickerVotes").child(String.valueOf(stickerIndex)).child(voterUid);
    }

    /**
     * Ref for a single viewer's "🔔 Remind me" subscription to a ⏳ Countdown sticker.
     * status/{ownerUid}/{statusId}/stickerSubscribers/{stickerIndex}/{viewerUid} — a
     * separate node from stickerVotes since subscribing is toggleable (unlike a locked-in
     * quiz answer, a viewer can subscribe and later unsubscribe).
     */
    public static DatabaseReference getStatusCountdownSubscriberRef(String ownerUid, String statusId,
                                                                     int stickerIndex, String viewerUid) {
        return db().getReference("status").child(ownerUid).child(statusId)
                   .child("stickerSubscribers").child(String.valueOf(stickerIndex)).child(viewerUid);
    }

    /**
     * Ref for a single viewer's vote on a 🗳️ Poll sticker (2-option, e.g. Yes/No).
     * status/{ownerUid}/{statusId}/stickerPollVotes/{stickerIndex}/{voterUid} — kept in its
     * own node (separate from stickerVotes, which is quiz-shaped) since a poll vote is just
     * "A" or "B", not an index + correctness flag.
     */
    public static DatabaseReference getStatusPollVoteRef(String ownerUid, String statusId,
                                                          int stickerIndex, String voterUid) {
        return db().getReference("status").child(ownerUid).child(statusId)
                   .child("stickerPollVotes").child(String.valueOf(stickerIndex)).child(voterUid);
    }

    /**
     * Ref for ALL votes on a 🗳️ Poll sticker — used to compute the live A/B percentage
     * split shown once a viewer has voted. status/{ownerUid}/{statusId}/stickerPollVotes/{stickerIndex}/
     */
    public static DatabaseReference getStatusPollVotesRef(String ownerUid, String statusId,
                                                           int stickerIndex) {
        return db().getReference("status").child(ownerUid).child(statusId)
                   .child("stickerPollVotes").child(String.valueOf(stickerIndex));
    }

    /**
     * Ref for a single viewer's response to a 🎚️ Slider sticker (0-100 emoji rating).
     * status/{ownerUid}/{statusId}/stickerSliderResponses/{stickerIndex}/{voterUid}
     */
    public static DatabaseReference getStatusSliderResponseRef(String ownerUid, String statusId,
                                                                int stickerIndex, String voterUid) {
        return db().getReference("status").child(ownerUid).child(statusId)
                   .child("stickerSliderResponses").child(String.valueOf(stickerIndex)).child(voterUid);
    }

    /**
     * Ref for ALL responses to a 🎚️ Slider sticker — used to compute the live average
     * shown once a viewer has dragged and released the slider.
     */
    public static DatabaseReference getStatusSliderResponsesRef(String ownerUid, String statusId,
                                                                 int stickerIndex) {
        return db().getReference("status").child(ownerUid).child(statusId)
                   .child("stickerSliderResponses").child(String.valueOf(stickerIndex));
    }

    // ── Reel interactive stickers (photo-slideshow) ─────────────────────────
    // Mirrors the status/{ownerUid}/{statusId}/stickerXxx/{stickerIndex}/{uid} shape
    // above, keyed by reelId instead of ownerUid+statusId. stickerKey combines the
    // photo position and the sticker's index within that photo's array (e.g. "2_0")
    // since ReelModel.photoStickerJsonList is one array PER PHOTO — a plain
    // stickerIndex alone would collide between stickers on different photos of the
    // same reel.

    /** Ref for a single viewer's answer to a 🧠 Quiz sticker on a reel. */
    public static DatabaseReference getReelStickerQuizVoteRef(String reelId, String stickerKey,
                                                                String voterUid) {
        return db().getReference("reelStickerVotes").child(reelId)
                   .child(stickerKey).child(voterUid);
    }

    /** Ref for a single viewer's "🔔 Remind me" subscription to a ⏳ Countdown sticker on a reel. */
    public static DatabaseReference getReelStickerCountdownSubscriberRef(String reelId, String stickerKey,
                                                                          String viewerUid) {
        return db().getReference("reelStickerSubscribers").child(reelId)
                   .child(stickerKey).child(viewerUid);
    }

    /** Ref for a single viewer's vote on a 🗳️ Poll sticker on a reel. */
    public static DatabaseReference getReelStickerPollVoteRef(String reelId, String stickerKey,
                                                                String voterUid) {
        return db().getReference("reelStickerPollVotes").child(reelId)
                   .child(stickerKey).child(voterUid);
    }

    /** Ref for ALL votes on a 🗳️ Poll sticker on a reel — used for the live A/B split. */
    public static DatabaseReference getReelStickerPollVotesRef(String reelId, String stickerKey) {
        return db().getReference("reelStickerPollVotes").child(reelId).child(stickerKey);
    }

    /** Ref for a single viewer's response to a 🎚️ Slider sticker on a reel. */
    public static DatabaseReference getReelStickerSliderResponseRef(String reelId, String stickerKey,
                                                                      String voterUid) {
        return db().getReference("reelStickerSliderResponses").child(reelId)
                   .child(stickerKey).child(voterUid);
    }

    /** Ref for ALL responses to a 🎚️ Slider sticker on a reel — used for the live average. */
    public static DatabaseReference getReelStickerSliderResponsesRef(String reelId, String stickerKey) {
        return db().getReference("reelStickerSliderResponses").child(reelId).child(stickerKey);
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
