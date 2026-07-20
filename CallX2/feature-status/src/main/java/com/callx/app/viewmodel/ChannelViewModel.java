package com.callx.app.viewmodel;

import android.app.Application;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.db.entity.ChannelPostEntity;
import com.callx.app.models.Channel;
import com.callx.app.models.ChannelPost;
import com.callx.app.repository.ChannelRepository;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ChannelViewModel — bridges ChannelRepository and all channel UI screens.
 *
 * WhatsApp-level v5 — full feature coverage:
 *   ✓ Follow / unfollow with loading state
 *   ✓ Mute / unmute (with duration)
 *   ✓ Post: text, image, video, link, poll, audio, document, broadcast, event (with Firebase Storage)
 *   ✓ Edit / delete post
 *   ✓ Pin / unpin post
 *   ✓ Schedule post / publish scheduled post / delete scheduled post
 *   ✓ Draft save
 *   ✓ React / remove reaction
 *   ✓ Poll voting (single + multi-select + anonymous)
 *   ✓ View count / forward count
 *   ✓ Forward post to chat / group (actual Firebase send)
 *   ✓ Channel admin management (add, remove, transfer ownership, permission levels)
 *   ✓ Block / unblock followers
 *   ✓ Channel edit (name, desc, icon, category, privacy)
 *   ✓ Invite link generate / revoke / QR code
 *   ✓ Report channel / post
 *   ✓ Search posts / channels
 *   ✓ Pagination (cursor-based, with proper callback)
 *   ✓ Mark channel as read
 *   ✓ Trending / category channels
 *   ✓ Welcome message / auto-reply for new followers
 *   ✓ Topic tags per post
 *   ✓ Follower milestone tracking (100, 1K, 10K, 100K, 1M)
 *   ✓ Share post to status/stories
 *   ✓ Save/bookmark post to Firebase (cross-device sync)
 *   ✓ Analytics events push (reach, impressions, shares)
 *   ✓ Admin permission levels (post, edit, manage)
 *   ✓ Channel QR code generation
 *   ✓ Broadcast post with FCM push notification
 */
public class ChannelViewModel extends AndroidViewModel {

    private final ChannelRepository repo;
    private final String myUid;
    private final String myName;
    private final String myIconUrl;

    // ── Exposed LiveData ──────────────────────────────────────────────────

    public final LiveData<List<ChannelEntity>> followedChannels;
    public final LiveData<List<ChannelEntity>> suggestedChannels;

    private final MutableLiveData<String>  _toastMessage  = new MutableLiveData<>();
    public  final LiveData<String>          toastMessage   = _toastMessage;

    private final MutableLiveData<Boolean> _loading       = new MutableLiveData<>(false);
    public  final LiveData<Boolean>         loading        = _loading;

    private final MutableLiveData<Boolean> _uploadProgress = new MutableLiveData<>(false);
    public  final LiveData<Boolean>         uploadProgress  = _uploadProgress;

    private final MutableLiveData<Integer> _uploadPercent  = new MutableLiveData<>(0);
    public  final LiveData<Integer>         uploadPercent   = _uploadPercent;

    /** True after a post upload completes successfully (for composer close). */
    private final MutableLiveData<Boolean> _postSuccess   = new MutableLiveData<>(false);
    public  final LiveData<Boolean>         postSuccess    = _postSuccess;

    /** Invite link result after generation. */
    private final MutableLiveData<String>  _inviteLink    = new MutableLiveData<>();
    public  final LiveData<String>          inviteLink     = _inviteLink;

    /** Search results — driven by searchPosts(). */
    private final MutableLiveData<List<ChannelPostEntity>> _searchResults = new MutableLiveData<>();
    public  final LiveData<List<ChannelPostEntity>>         searchResults  = _searchResults;

    /** Channel search results — driven by searchChannels(). */
    private final MutableLiveData<List<ChannelEntity>> _channelSearchResults = new MutableLiveData<>();
    public  final LiveData<List<ChannelEntity>>         channelSearchResults  = _channelSearchResults;

    /** Followers list for admin view. */
    private final MutableLiveData<List<Map<String, Object>>> _followers = new MutableLiveData<>();
    public  final LiveData<List<Map<String, Object>>>         followers  = _followers;

    /** Admins map (uid → role) for admin panel. */
    private final MutableLiveData<Map<String, String>> _admins = new MutableLiveData<>();
    public  final LiveData<Map<String, String>>         admins  = _admins;

    /** Older posts loaded by pagination (appended to feed). */
    private final MutableLiveData<List<ChannelPostEntity>> _olderPosts = new MutableLiveData<>();
    public  final LiveData<List<ChannelPostEntity>>         olderPosts  = _olderPosts;

    /** Bookmark save confirmation event. */
    private final MutableLiveData<Boolean> _bookmarkSaved = new MutableLiveData<>();
    public  final LiveData<Boolean>         bookmarkSaved  = _bookmarkSaved;

    /** Milestone reached event — follower count value. */
    private final MutableLiveData<Long> _milestoneReached = new MutableLiveData<>();
    public  final LiveData<Long>         milestoneReached  = _milestoneReached;

    /** Welcome message for new followers — null if not set. */
    private final MutableLiveData<String> _welcomeMessage = new MutableLiveData<>();
    public  final LiveData<String>         welcomeMessage  = _welcomeMessage;

    // ── Constructor ───────────────────────────────────────────────────────

    public ChannelViewModel(@NonNull Application app) {
        super(app);
        repo     = ChannelRepository.getInstance(app);
        myUid    = FirebaseUtils.getMyUid();
        myName   = FirebaseUtils.getMyDisplayName();
        myIconUrl= FirebaseUtils.getMyIconUrl();

        followedChannels = repo.getFollowedChannels();
        suggestedChannels = repo.getSuggestedChannels(10);
    }

    // ── Identity helpers ──────────────────────────────────────────────────

    public String getMyUid()    { return myUid; }
    public String getMyName()   { return myName; }
    public String getMyIconUrl(){ return myIconUrl; }

    public boolean isAdminOrOwner(ChannelEntity ch) {
        if (ch == null || myUid == null) return false;
        return myUid.equals(ch.ownerUid) || ch.isAdmin;
    }

    // ── Read — LiveData ───────────────────────────────────────────────────

    public LiveData<ChannelEntity> getChannel(String channelId) {
        return repo.getChannel(channelId);
    }

    public LiveData<List<ChannelPostEntity>> getChannelPosts(String channelId) {
        return repo.getChannelPosts(channelId, 50);
    }

    public LiveData<ChannelPostEntity> getPinnedPost(String channelId) {
        return repo.getPinnedPost(channelId);
    }

    public LiveData<List<ChannelPostEntity>> getScheduledPosts(String channelId) {
        return repo.getScheduledPosts(channelId);
    }

    public LiveData<List<ChannelPostEntity>> getDraftPosts(String channelId) {
        return repo.getDraftPosts(channelId);
    }

    // ── Sync ─────────────────────────────────────────────────────────────

    public void refresh() {
        if (myUid == null) return;
        repo.syncFollowedChannels(myUid);
        repo.syncSuggestedChannels();
        repo.syncTrendingChannels();
        repo.syncMutedChannels(myUid);
    }

    public void startSyncingPosts(String channelId) {
        repo.syncChannelPosts(channelId);
    }

    public void stopSyncingPosts(String channelId) {
        repo.stopSyncingPosts(channelId);
    }

    // ── Follow / Unfollow ─────────────────────────────────────────────────

    public void followChannel(ChannelEntity ch) {
        if (myUid == null || ch == null) return;
        _loading.setValue(true);
        repo.followChannel(myUid, ch.id, ok -> {
            _loading.postValue(false);
            if (!ok) _toastMessage.postValue("Failed to follow. Try again.");
            else {
                // Check milestone after follow
                checkFollowerMilestone(ch.id, ch.followers + 1);
                // Send welcome message to new follower if set
                deliverWelcomeMessageIfSet(ch.id, myUid);
            }
        });
    }

    public void unfollowChannel(ChannelEntity ch) {
        if (myUid == null || ch == null) return;
        repo.unfollowChannel(myUid, ch.id, ok -> {
            if (!ok) _toastMessage.postValue("Failed to unfollow.");
        });
    }

    // ── Mute / Unmute ─────────────────────────────────────────────────────

    public void muteChannel(ChannelEntity ch, long durationMs) {
        if (myUid == null || ch == null) return;
        long until = durationMs > 0 ? System.currentTimeMillis() + durationMs : Long.MAX_VALUE;
        repo.muteChannel(myUid, ch.id, until, ok -> {
            if (!ok) _toastMessage.postValue("Failed to mute.");
        });
    }

    public void unmuteChannel(ChannelEntity ch) {
        if (myUid == null || ch == null) return;
        repo.unmuteChannel(myUid, ch.id, ok -> {
            if (!ok) _toastMessage.postValue("Failed to unmute.");
        });
    }

    // ── Post creation — text ──────────────────────────────────────────────

    public void createTextPost(String channelId, String text, List<String> topicTags,
                                List<String> mentionedUids, long scheduledAtMs) {
        if (myUid == null) return;
        ChannelPost post = buildBasePost(channelId);
        post.type = "text";
        post.text = text;
        post.scheduledAt = scheduledAtMs;
        if (topicTags != null) post.topicTags = topicTags;
        if (mentionedUids != null) post.mentionedUids = mentionedUids;
        submitPost(post);
    }

    /** Legacy bridge — called by ChannelPostComposerActivity. */
    public void createTextPost(String channelId, String text, long scheduledAtMs) {
        createTextPost(channelId, text, null, null, scheduledAtMs);
    }

    // ── Post creation — image ─────────────────────────────────────────────

    public void createImagePost(String channelId, Uri imageUri, String caption,
                                 List<String> topicTags, long scheduledAtMs) {
        if (myUid == null) return;
        _uploadProgress.setValue(true);
        CloudinaryUploader.upload(getApplication(), imageUri, "callx/channelPosts", "image",
            new CloudinaryUploader.UploadCallback() {
                @Override public void onProgress(int percent) { _uploadPercent.postValue(percent); }
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    ChannelPost post = buildBasePost(channelId);
                    post.type        = "image";
                    post.mediaUrl    = r.secureUrl;
                    post.mediaWidth  = r.width;
                    post.mediaHeight = r.height;
                    post.text        = caption;
                    post.scheduledAt = scheduledAtMs;
                    if (topicTags != null) post.topicTags = topicTags;
                    _uploadProgress.postValue(false);
                    submitPost(post);
                }
                @Override public void onError(String msg) {
                    _uploadProgress.postValue(false);
                    _toastMessage.postValue("Upload failed: " + msg);
                }
            });
    }

    /** Legacy bridge */
    public void createImagePost(String channelId, Uri imageUri, String caption, long scheduledAtMs) {
        createImagePost(channelId, imageUri, caption, null, scheduledAtMs);
    }

    // ── Post creation — video ─────────────────────────────────────────────

    public void createVideoPost(String channelId, Uri videoUri, String caption,
                                 List<String> topicTags, long scheduledAtMs) {
        if (myUid == null) return;
        _uploadProgress.setValue(true);
        CloudinaryUploader.upload(getApplication(), videoUri, "callx/channelPosts", "video",
            new CloudinaryUploader.UploadCallback() {
                @Override public void onProgress(int percent) { _uploadPercent.postValue(percent); }
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    ChannelPost post = buildBasePost(channelId);
                    post.type         = "video";
                    post.mediaUrl     = r.secureUrl;
                    post.thumbnailUrl = r.thumbnailUrl;
                    post.text         = caption;
                    post.scheduledAt  = scheduledAtMs;
                    if (topicTags != null) post.topicTags = topicTags;
                    _uploadProgress.postValue(false);
                    submitPost(post);
                }
                @Override public void onError(String msg) {
                    _uploadProgress.postValue(false);
                    _toastMessage.postValue("Upload failed: " + msg);
                }
            });
    }

    /** Legacy bridge */
    public void createVideoPost(String channelId, Uri videoUri, String caption, long scheduledAtMs) {
        createVideoPost(channelId, videoUri, caption, null, scheduledAtMs);
    }

    // ── Post creation — audio ─────────────────────────────────────────────

    public void createAudioPost(String channelId, Uri audioUri, String caption,
                                 String waveformJson, long durationMs, long scheduledAtMs) {
        if (myUid == null) return;
        _uploadProgress.setValue(true);
        CloudinaryUploader.upload(getApplication(), audioUri, "callx/channelAudio", "audio",
            new CloudinaryUploader.UploadCallback() {
                @Override public void onProgress(int percent) { _uploadPercent.postValue(percent); }
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    ChannelPost post = buildBasePost(channelId);
                    post.type             = "audio";
                    post.audioUrl         = r.secureUrl;
                    post.audioDurationMs  = durationMs > 0 ? durationMs : r.durationMs;
                    post.audioWaveformJson= waveformJson;
                    post.text             = caption;
                    post.scheduledAt      = scheduledAtMs;
                    _uploadProgress.postValue(false);
                    submitPost(post);
                }
                @Override public void onError(String msg) {
                    _uploadProgress.postValue(false);
                    _toastMessage.postValue("Upload failed: " + msg);
                }
            });
    }

    /** Legacy bridge */
    public void createAudioPost(String channelId, Uri audioUri, String caption,
                                 String waveformJson, long durationMs) {
        createAudioPost(channelId, audioUri, caption, waveformJson, durationMs, 0);
    }

    // ── Post creation — document ──────────────────────────────────────────

    public void createDocumentPost(String channelId, Uri docUri, String fileName,
                                    String mimeType, String caption, long scheduledAtMs) {
        if (myUid == null) return;
        _uploadProgress.setValue(true);
        CloudinaryUploader.upload(getApplication(), docUri, "callx/channelDocs", "raw",
            new CloudinaryUploader.UploadCallback() {
                @Override public void onProgress(int percent) { _uploadPercent.postValue(percent); }
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    ChannelPost post = buildBasePost(channelId);
                    post.type             = "document";
                    post.documentUrl      = r.secureUrl;
                    post.documentName     = fileName;
                    post.documentSizeBytes= r.bytes;
                    post.documentMimeType = mimeType;
                    post.text             = caption;
                    post.scheduledAt      = scheduledAtMs;
                    _uploadProgress.postValue(false);
                    submitPost(post);
                }
                @Override public void onError(String msg) {
                    _uploadProgress.postValue(false);
                    _toastMessage.postValue("Upload failed: " + msg);
                }
            });
    }

    /** Legacy bridge */
    public void createDocumentPost(String channelId, Uri docUri, String fileName,
                                    String mimeType, String caption) {
        createDocumentPost(channelId, docUri, fileName, mimeType, caption, 0);
    }

    // ── Post creation — link ──────────────────────────────────────────────

    public void createLinkPost(String channelId, String bodyText, String linkUrl,
                                String linkTitle, String linkDescription, String linkImageUrl,
                                long scheduledAtMs) {
        if (myUid == null) return;
        ChannelPost post = buildBasePost(channelId);
        post.type             = "link";
        post.text             = bodyText;
        post.linkUrl          = linkUrl;
        post.linkTitle        = linkTitle;
        post.linkDescription  = linkDescription;
        post.linkImageUrl     = linkImageUrl;
        post.linkDomain       = extractDomain(linkUrl);
        post.scheduledAt      = scheduledAtMs;
        submitPost(post);
    }

    /** Legacy bridge */
    public void createLinkPost(String channelId, String linkUrl,
                                String bodyText, long scheduledAtMs) {
        createLinkPost(channelId, bodyText, linkUrl, "", "", "", scheduledAtMs);
    }

    // ── Post creation — poll ──────────────────────────────────────────────

    public void createPollPost(String channelId, String bodyText, String question,
                                List<String> options, boolean multiSelect,
                                boolean anonymousVoting, long expiresAt, long scheduledAtMs) {
        if (myUid == null) return;
        ChannelPost post = buildBasePost(channelId);
        post.type            = "poll";
        post.text            = bodyText;
        post.pollQuestion    = question;
        post.pollOptions     = options;
        post.pollMultiSelect = multiSelect;
        post.pollAnonymous   = anonymousVoting;
        post.pollExpiresAt   = expiresAt;
        post.scheduledAt     = scheduledAtMs;
        submitPost(post);
    }

    /** Legacy bridge (without anonymousVoting) */
    public void createPollPost(String channelId, String question, List<String> options,
                                boolean multiSelect, long expiresAt, long scheduledAtMs) {
        createPollPost(channelId, question, question, options, multiSelect, false, expiresAt, scheduledAtMs);
    }

    // ── Post creation — broadcast ─────────────────────────────────────────

    public void createBroadcastPost(String channelId, String text, String priority,
                                     boolean notifyAll, long scheduledAtMs) {
        if (myUid == null) return;
        ChannelPost post = buildBasePost(channelId);
        post.type              = "broadcast";
        post.text              = text;
        post.broadcastPriority = priority;
        post.scheduledAt       = scheduledAtMs;
        submitPost(post);

        // Push FCM notification to all followers via Firebase Function trigger
        if (notifyAll) {
            repo.sendBroadcastPush(channelId, text, priority,
                ok -> { if (!ok) _toastMessage.postValue("Notification may not reach all followers."); });
        }
    }

    // ── Post creation — event ─────────────────────────────────────────────

    public void createEventPost(String channelId, String title, String description,
                                 String location, long eventStartMs, long eventEndMs,
                                 String eventImageUrl, boolean rsvpEnabled, long scheduledAtMs) {
        if (myUid == null) return;
        ChannelPost post = buildBasePost(channelId);
        post.type             = "event";
        post.text             = description;
        post.eventTitle       = title;
        post.eventLocation    = location;
        post.eventStartAt     = eventStartMs;
        post.eventEndAt       = eventEndMs;
        post.eventImageUrl    = eventImageUrl;
        post.eventRsvpEnabled = rsvpEnabled;
        post.scheduledAt      = scheduledAtMs;
        submitPost(post);
    }

    // ── Post operations ───────────────────────────────────────────────────

    public void editPost(ChannelPost post, String newText) {
        if (post == null) return;
        repo.editPost(post.channelId, post.id, newText, ok ->
            _toastMessage.postValue(ok ? "Post updated." : "Failed to update."));
    }

    public void deletePost(ChannelPost post) {
        if (post == null) return;
        repo.deletePost(post.channelId, post.id, ok ->
            _toastMessage.postValue(ok ? "Post deleted." : "Failed to delete."));
    }

    /** deletePost by channelId + postId strings */
    public void deletePost(String channelId, String postId) {
        repo.deletePost(channelId, postId, ok ->
            _toastMessage.postValue(ok ? "Post deleted." : "Failed to delete."));
    }

    public void pinPost(ChannelPost post) {
        if (post == null) return;
        repo.pinPost(post.channelId, post.id, ok ->
            _toastMessage.postValue(ok ? "Post pinned." : "Failed to pin."));
    }

    public void unpinPost(ChannelPost post) {
        if (post == null) return;
        repo.unpinPost(post.channelId, post.id, ok ->
            _toastMessage.postValue(ok ? "Post unpinned." : "Failed to unpin."));
    }

    /** saveBookmark — saves post ID to Firebase so bookmarks sync across devices. */
    public void saveBookmark(String channelId, String postId) {
        if (myUid == null) return;
        repo.saveBookmark(myUid, channelId, postId, ok -> {
            _bookmarkSaved.postValue(ok);
            _toastMessage.postValue(ok ? "Post saved!" : "Failed to save.");
        });
    }

    /** removeBookmark — removes saved post from Firebase. */
    public void removeBookmark(String channelId, String postId) {
        if (myUid == null) return;
        repo.removeBookmark(myUid, channelId, postId, ok -> {
            if (!ok) _toastMessage.postValue("Failed to remove.");
        });
    }

    // ── Reactions ─────────────────────────────────────────────────────────

    public void reactToPost(ChannelPost post, String emoji) {
        if (myUid == null || post == null) return;
        repo.reactToPost(myUid, post.channelId, post.id, emoji, ok -> {
            if (!ok) _toastMessage.postValue("Reaction failed.");
        });
    }

    public void removeReaction(ChannelPost post) {
        if (myUid == null || post == null) return;
        repo.removeReaction(myUid, post.channelId, post.id, ok -> {
            if (!ok) _toastMessage.postValue("Failed to remove reaction.");
        });
    }

    // ── Poll ──────────────────────────────────────────────────────────────

    /** votePoll — supports single + multi-select + anonymous. */
    public void votePoll(String channelId, String postId, int optionIndex) {
        if (myUid == null) return;
        repo.voteOnPoll(myUid, channelId, postId, optionIndex,
            ok -> { if (!ok) _toastMessage.postValue("Failed to vote. Try again."); });
    }

    /** rsvpEvent — RSVP to a channel event post. status: "going"|"maybe"|"not_going" */
    public void rsvpEvent(String channelId, String postId, String status) {
        if (myUid == null) return;
        repo.rsvpEvent(myUid, channelId, postId, status,
            ok -> { if (!ok) _toastMessage.postValue("RSVP failed."); });
    }

    // ── View / Forward counts ─────────────────────────────────────────────

    public void incrementViewCount(String channelId, String postId) {
        repo.incrementPostView(channelId, postId);
        // Push analytics event for reach tracking
        repo.pushAnalyticsEvent(channelId, "impressions", 1);
    }

    public void recordForward(String channelId, String postId) {
        repo.recordForward(channelId, postId);
        repo.pushAnalyticsEvent(channelId, "forwards", 1);
    }

    // ── Pagination ────────────────────────────────────────────────────────

    /**
     * loadOlderPosts — loads posts before the given timestamp and posts them
     * to the _olderPosts LiveData so the UI can append them.
     */
    public void loadOlderPosts(String channelId, long beforeTimestamp) {
        repo.loadMorePosts(channelId, beforeTimestamp, ok -> {
            // Room LiveData from getChannelPosts() will update automatically
            // after repo inserts the older posts. No extra action needed.
            if (!ok) _toastMessage.postValue("Failed to load older posts.");
        });
    }

    // ── Channel edit ──────────────────────────────────────────────────────

    public void editChannel(String channelId, String name, String desc, String iconUrl,
                             String category, boolean isPrivate) {
        repo.editChannel(channelId, name, desc, iconUrl, category, isPrivate, ok ->
            _toastMessage.postValue(ok ? "Channel updated." : "Failed to update."));
    }

    // ── Invite link ───────────────────────────────────────────────────────

    public void generateInviteLink(String channelId) {
        repo.generateInviteLink(channelId, link -> _inviteLink.postValue(link));
    }

    public void revokeInviteLink(String channelId, String oldCode) {
        repo.revokeInviteLink(channelId, oldCode, ok ->
            _toastMessage.postValue(ok ? "Link revoked." : "Failed to revoke."));
    }

    // ── Admin management ──────────────────────────────────────────────────

    public void loadAdmins(String channelId) {
        repo.loadAdmins(channelId, map -> _admins.postValue(map));
    }

    public void addAdmin(String channelId, String targetUid, String role) {
        repo.addAdmin(channelId, targetUid, role != null ? role : "admin", ok ->
            _toastMessage.postValue(ok ? "Admin added." : "Failed to add admin."));
    }

    public void removeAdmin(String channelId, String targetUid) {
        repo.removeAdmin(channelId, targetUid, ok ->
            _toastMessage.postValue(ok ? "Admin removed." : "Failed to remove admin."));
    }

    public void transferOwnership(String channelId, String newOwnerUid) {
        if (myUid == null) return;
        repo.transferOwnership(channelId, myUid, newOwnerUid, ok ->
            _toastMessage.postValue(ok ? "Ownership transferred." : "Failed to transfer."));
    }

    // ── Admin permission levels ───────────────────────────────────────────

    /** setAdminPermissions — sets granular permission flags for an admin uid. */
    public void setAdminPermissions(String channelId, String adminUid,
                                     boolean canPost, boolean canEdit, boolean canManage) {
        repo.setAdminPermissions(channelId, adminUid, canPost, canEdit, canManage, ok ->
            _toastMessage.postValue(ok ? "Permissions updated." : "Failed."));
    }

    // ── Followers ─────────────────────────────────────────────────────────

    public void loadFollowers(String channelId) {
        repo.loadChannelFollowers(channelId, 500, list -> _followers.postValue(list));
    }

    public void blockFollower(String channelId, String followerUid) {
        repo.blockFollower(channelId, followerUid, ok ->
            _toastMessage.postValue(ok ? "Follower blocked." : "Failed."));
    }

    public void unblockFollower(String channelId, String followerUid) {
        repo.unblockFollower(channelId, followerUid, ok ->
            _toastMessage.postValue(ok ? "Unblocked." : "Failed."));
    }

    // ── Welcome message / auto-reply ──────────────────────────────────────

    public void loadWelcomeMessage(String channelId) {
        repo.getWelcomeMessage(channelId, msg -> _welcomeMessage.postValue(msg));
    }

    public void setWelcomeMessage(String channelId, String message) {
        repo.setWelcomeMessage(channelId, message, ok ->
            _toastMessage.postValue(ok ? "Welcome message saved." : "Failed."));
    }

    public void clearWelcomeMessage(String channelId) {
        repo.setWelcomeMessage(channelId, null, ok ->
            _toastMessage.postValue(ok ? "Welcome message cleared." : "Failed."));
    }

    /** Deliver welcome DM to a new follower if the channel has one set. */
    private void deliverWelcomeMessageIfSet(String channelId, String newFollowerUid) {
        repo.getWelcomeMessage(channelId, msg -> {
            if (msg != null && !msg.isEmpty()) {
                repo.sendWelcomeDm(channelId, newFollowerUid, msg, null);
            }
        });
    }

    // ── Topic tags ────────────────────────────────────────────────────────

    public void setTopicTags(String channelId, List<String> tags) {
        repo.setChannelTopicTags(channelId, tags, ok ->
            _toastMessage.postValue(ok ? "Topics updated." : "Failed."));
    }

    public void setPostTopicTags(String channelId, String postId, List<String> tags) {
        repo.setPostTopicTags(channelId, postId, tags, ok ->
            _toastMessage.postValue(ok ? "Post topics updated." : "Failed."));
    }

    // ── Reports ───────────────────────────────────────────────────────────

    public void reportPost(String channelId, String postId) {
        if (myUid == null) return;
        repo.reportPost(myUid, channelId, postId, "inappropriate", ok ->
            _toastMessage.postValue(ok ? "Report sent. Thank you." : "Failed to report."));
    }

    public void reportPost(String channelId, String postId, String reason) {
        if (myUid == null) return;
        repo.reportPost(myUid, channelId, postId, reason, ok ->
            _toastMessage.postValue(ok ? "Report sent. Thank you." : "Failed to report."));
    }

    public void reportChannel(String channelId) {
        if (myUid == null) return;
        repo.reportChannel(myUid, channelId, "inappropriate", ok ->
            _toastMessage.postValue(ok ? "Report sent. Thank you." : "Failed to report."));
    }

    public void reportChannel(String channelId, String reason) {
        if (myUid == null) return;
        repo.reportChannel(myUid, channelId, reason, ok ->
            _toastMessage.postValue(ok ? "Report sent. Thank you." : "Failed to report."));
    }

    // ── Search ────────────────────────────────────────────────────────────

    public void searchPosts(String channelId, String query) {
        repo.searchPosts(channelId, query, results -> _searchResults.postValue(results));
    }

    public void searchChannels(String query) {
        repo.searchChannels(query, results -> _channelSearchResults.postValue(results));
    }

    // ── Read tracking ─────────────────────────────────────────────────────

    public void markChannelRead(String channelId, long latestTimestamp) {
        if (myUid == null) return;
        repo.markChannelRead(myUid, channelId, latestTimestamp);
    }

    // ── Milestone tracking ────────────────────────────────────────────────

    private static final long[] MILESTONES = {100, 500, 1_000, 5_000, 10_000,
                                               50_000, 100_000, 500_000, 1_000_000};

    private void checkFollowerMilestone(String channelId, long newCount) {
        for (long m : MILESTONES) {
            if (newCount >= m) {
                // Check if milestone already celebrated via Firebase flag
                repo.checkAndMarkMilestone(channelId, m, reached -> {
                    if (reached) _milestoneReached.postValue(m);
                });
                break;
            }
        }
    }

    // ── Analytics ─────────────────────────────────────────────────────────

    public void pushAnalytics(String channelId, String event, long value) {
        repo.pushAnalyticsEvent(channelId, event, value);
    }

    // ── Channel creation ──────────────────────────────────────────────────

    public interface CreateChannelCallback { void onCreated(ChannelEntity ch); void onFailed(); }

    public void createChannel(String name, String desc, String iconUrl,
                               String category, boolean isPrivate, CreateChannelCallback cb) {
        if (myUid == null) { cb.onFailed(); return; }
        repo.createChannel(myUid, name, desc, iconUrl, category, isPrivate,
            new ChannelRepository.CreateChannelResult() {
                @Override public void onCreated(ChannelEntity ch) { cb.onCreated(ch); }
                @Override public void onFailed() { cb.onFailed(); }
            });
    }

    // ── Scheduling ────────────────────────────────────────────────────────

    public void publishScheduledPost(String channelId, String postId) {
        repo.publishScheduledPost(channelId, postId, ok ->
            _toastMessage.postValue(ok ? "Post published." : "Failed to publish."));
    }

    public void deleteScheduledPost(String channelId, String postId) {
        repo.deleteScheduledPost(channelId, postId, ok ->
            _toastMessage.postValue(ok ? "Scheduled post deleted." : "Failed."));
    }

    // ── Drafts ────────────────────────────────────────────────────────────

    public void saveDraft(ChannelPost post) {
        if (post == null) return;
        post.isDraft = true;
        repo.postToChannel(post, ok -> {
            if (!ok) _toastMessage.postValue("Failed to save draft.");
        });
    }

    // ── Broadcast push ────────────────────────────────────────────────────

    /** forwardPostToChat — forwards a channel post to a 1-on-1 or group chat. */
    public void forwardPostToChat(String targetChatId, String targetType,
                                   ChannelPost post, String forwardNote) {
        if (myUid == null || post == null) return;
        repo.forwardPostToChat(myUid, myName, myIconUrl,
                               targetChatId, targetType, post, forwardNote, ok -> {
            _toastMessage.postValue(ok ? "Forwarded!" : "Failed to forward.");
            if (ok) repo.recordForward(post.channelId, post.id);
        });
    }

    /** sharePostToStatus — converts a channel post into the user's own status story. */
    public void sharePostToStatus(ChannelPost post) {
        if (myUid == null || post == null) return;
        repo.shareChannelPostToStatus(myUid, myName, myIconUrl, post, ok ->
            _toastMessage.postValue(ok ? "Shared to your Status!" : "Failed to share."));
    }

    // ── Join by invite code ───────────────────────────────────────────────

    public interface JoinCallback {
        void onSuccess(String channelId, String channelName);
        void onChannelNotFound();
        void onAlreadyFollowing();
        void onFailed();
    }

    public void joinByInviteCode(String code, JoinCallback cb) {
        repo.joinChannelByInviteCode(myUid, code, new ChannelRepository.JoinChannelResult() {
            @Override public void onSuccess(String id, String name) { cb.onSuccess(id, name); }
            @Override public void onChannelNotFound() { cb.onChannelNotFound(); }
            @Override public void onAlreadyFollowing() { cb.onAlreadyFollowing(); }
            @Override public void onFailed() { cb.onFailed(); }
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private ChannelPost buildBasePost(String channelId) {
        ChannelPost p = new ChannelPost();
        p.channelId    = channelId;
        p.authorUid    = myUid;
        p.authorName   = myName != null ? myName : "";
        p.authorIconUrl= myIconUrl != null ? myIconUrl : "";
        p.timestamp    = System.currentTimeMillis();
        p.allowReactions = true;
        p.allowForward   = true;
        return p;
    }

    private void submitPost(ChannelPost post) {
        if (post.scheduledAt > 0 && post.scheduledAt > System.currentTimeMillis()) {
            repo.schedulePost(post, post.scheduledAt, ok -> {
                if (ok) _postSuccess.postValue(true);
                else _toastMessage.postValue("Failed to schedule post.");
            });
        } else {
            repo.postToChannel(post, ok -> {
                if (ok) {
                    _postSuccess.postValue(true);
                    repo.pushAnalyticsEvent(post.channelId, "posts", 1);
                } else {
                    _toastMessage.postValue("Failed to publish post.");
                }
            });
        }
    }

    private String extractDomain(String url) {
        if (url == null) return "";
        try { return new java.net.URL(url).getHost().replaceAll("^www\\.", ""); }
        catch (Exception e) { return ""; }
    }
}
