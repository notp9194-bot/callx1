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
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ChannelViewModel — bridges ChannelRepository and all channel UI screens.
 *
 * WhatsApp-level v2 — covers all features:
 *   - Follow / unfollow with loading state
 *   - Mute / unmute (with duration)
 *   - Post: text, image, video, link, poll, audio, document (with Firebase Storage)
 *   - Edit / delete post
 *   - Pin / unpin post
 *   - Schedule post / publish scheduled post / delete scheduled post
 *   - Draft save
 *   - React / remove reaction
 *   - Poll voting
 *   - View count / forward count
 *   - Forward post to chat / group (actual Firebase send)
 *   - Channel admin management (add, remove, transfer ownership)
 *   - Block / unblock followers
 *   - Channel edit (name, desc, icon, category, privacy)
 *   - Invite link generate / revoke
 *   - Report channel / post
 *   - Search posts / channels
 *   - Pagination
 *   - Mark channel as read
 *   - Trending / category channels
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

    public ChannelViewModel(@NonNull Application app) {
        super(app);
        repo      = ChannelRepository.getInstance(app);
        myUid     = FirebaseUtils.getCurrentUid();
        myName    = FirebaseUtils.getCurrentName();
        myIconUrl = FirebaseUtils.getCurrentPhotoUrl();

        followedChannels  = repo.getFollowedChannels();
        suggestedChannels = repo.getSuggestedChannels(20);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    public void refresh() {
        repo.syncFollowedChannels(myUid);
        repo.syncSuggestedChannels();
        repo.syncTrendingChannels();
        repo.syncMutedChannels(myUid);
    }

    // ── Read ──────────────────────────────────────────────────────────────

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

    public LiveData<List<ChannelEntity>> getAllChannels(int limit) {
        return repo.getAllChannels(limit);
    }

    public LiveData<List<ChannelEntity>> getTrendingChannels(int limit) {
        return repo.getTrendingChannels(limit);
    }

    public LiveData<List<ChannelEntity>> getChannelsByCategory(String category, int limit) {
        return repo.getChannelsByCategory(category, limit);
    }

    // ── Follow / Unfollow ─────────────────────────────────────────────────

    public void followChannel(ChannelEntity ch) {
        if (myUid == null || myUid.isEmpty() || ch.id == null) return;
        repo.followChannel(myUid, ch.id, ok -> {
            if (ok) _toastMessage.postValue("Following " + ch.name);
            else    _toastMessage.postValue("Failed to follow. Try again.");
        });
    }

    public void unfollowChannel(ChannelEntity ch) {
        if (myUid == null || myUid.isEmpty() || ch.id == null) return;
        repo.unfollowChannel(myUid, ch.id, ok -> {
            if (ok) _toastMessage.postValue("Unfollowed " + ch.name);
            else    _toastMessage.postValue("Failed to unfollow. Try again.");
        });
    }

    // ── Mute / Unmute ─────────────────────────────────────────────────────

    public void muteChannel(ChannelEntity ch, long mutedUntilMs) {
        if (ch == null) return;
        repo.muteChannel(myUid, ch.id, mutedUntilMs, ok ->
            _toastMessage.postValue(ok ? "Channel muted" : "Failed to mute channel"));
    }

    public void unmuteChannel(ChannelEntity ch) {
        if (ch == null) return;
        repo.unmuteChannel(myUid, ch.id, ok ->
            _toastMessage.postValue(ok ? "Channel unmuted" : "Failed to unmute channel"));
    }

    // ── Create Channel ────────────────────────────────────────────────────

    public interface CreateCallback {
        void onCreated(ChannelEntity ch);
        void onFailed();
    }

    public void createChannel(String name, String desc, String iconUrl, String category,
                               boolean isPrivate, CreateCallback cb) {
        _loading.postValue(true);
        repo.createChannel(myUid, name, desc, iconUrl, category, isPrivate, new ChannelRepository.CreateChannelResult() {
            @Override public void onCreated(ChannelEntity ch) {
                _loading.postValue(false);
                if (cb != null) cb.onCreated(ch);
            }
            @Override public void onFailed() {
                _loading.postValue(false);
                _toastMessage.postValue("Failed to create channel. Try again.");
                if (cb != null) cb.onFailed();
            }
        });
    }

    // ── Edit Channel ──────────────────────────────────────────────────────

    public void editChannel(String channelId, String name, String desc, String iconUrl,
                             String category, boolean isPrivate) {
        _loading.postValue(true);
        repo.editChannel(channelId, name, desc, iconUrl, category, isPrivate, ok -> {
            _loading.postValue(false);
            _toastMessage.postValue(ok ? "Channel updated!" : "Failed to update channel.");
        });
    }

    // ── Invite Link ───────────────────────────────────────────────────────

    public void generateInviteLink(String channelId) {
        repo.generateInviteLink(channelId, link -> {
            if (link != null) {
                _inviteLink.postValue(link);
                _toastMessage.postValue("Invite link generated!");
            } else {
                _toastMessage.postValue("Failed to generate invite link.");
            }
        });
    }

    public void revokeInviteLink(String channelId, String oldCode) {
        repo.revokeInviteLink(channelId, oldCode, ok ->
            _toastMessage.postValue(ok ? "Invite link revoked." : "Failed to revoke link."));
    }

    // ── Post Creation ─────────────────────────────────────────────────────

    public void createTextPost(String channelId, String text) {
        createTextPost(channelId, text, 0);
    }

    public void createTextPost(String channelId, String text, long scheduledAtMs) {
        ChannelPost p = basePost(channelId);
        p.type = "text";
        p.text = text;
        if (scheduledAtMs > 0) schedulePost(p, scheduledAtMs);
        else submitPost(p);
    }

    public void createMediaPost(String channelId, Uri mediaUri, String mediaType, String caption,
                                 android.content.Context ctx) {
        createMediaPost(channelId, mediaUri, mediaType, caption, ctx, 0);
    }

    public void createMediaPost(String channelId, Uri mediaUri, String mediaType, String caption,
                                 android.content.Context ctx, long scheduledAtMs) {
        _uploadProgress.postValue(true);
        _uploadPercent.postValue(0);
        String uid = myUid;
        String ext = "image".equals(mediaType) ? ".jpg" : ".mp4";
        StorageReference ref = FirebaseStorage.getInstance().getReference()
            .child("channelMedia").child(channelId)
            .child(uid + "_" + System.currentTimeMillis() + ext);

        UploadTask uploadTask = ref.putFile(mediaUri);
        uploadTask
            .addOnProgressListener(snap -> {
                int pct = (int)(100.0 * snap.getBytesTransferred() / snap.getTotalByteCount());
                _uploadPercent.postValue(pct);
            })
            .addOnSuccessListener(snap -> ref.getDownloadUrl()
                .addOnSuccessListener(uri -> {
                    ChannelPost p = basePost(channelId);
                    p.type     = mediaType;
                    p.text     = caption != null ? caption : "";
                    p.mediaUrl = uri.toString();
                    if (scheduledAtMs > 0) schedulePost(p, scheduledAtMs);
                    else submitPost(p);
                })
                .addOnFailureListener(e -> {
                    _uploadProgress.postValue(false);
                    _toastMessage.postValue("Upload failed: " + e.getMessage());
                }))
            .addOnFailureListener(e -> {
                _uploadProgress.postValue(false);
                _toastMessage.postValue("Upload failed: " + e.getMessage());
            });
    }

    public void createLinkPost(String channelId, String text, String linkUrl, String linkTitle,
                                String linkDesc, String linkImageUrl) {
        createLinkPost(channelId, text, linkUrl, linkTitle, linkDesc, linkImageUrl, 0);
    }

    public void createLinkPost(String channelId, String text, String linkUrl, String linkTitle,
                                String linkDesc, String linkImageUrl, long scheduledAtMs) {
        ChannelPost p = basePost(channelId);
        p.type           = "link";
        p.text           = text;
        p.linkUrl        = linkUrl;
        p.linkTitle      = linkTitle;
        p.linkDescription= linkDesc;
        p.linkImageUrl   = linkImageUrl;
        // Extract domain
        try {
            java.net.URL url = new java.net.URL(linkUrl);
            p.linkDomain = url.getHost().replaceAll("^www\\.", "");
        } catch (Exception ignored) { p.linkDomain = linkUrl; }
        if (scheduledAtMs > 0) schedulePost(p, scheduledAtMs);
        else submitPost(p);
    }

    public void createPollPost(String channelId, String text, String question,
                                List<String> options, boolean multiSelect, long expiresAt) {
        createPollPost(channelId, text, question, options, multiSelect, expiresAt, 0);
    }

    public void createPollPost(String channelId, String text, String question,
                                List<String> options, boolean multiSelect, long expiresAt,
                                long scheduledAtMs) {
        ChannelPost p = basePost(channelId);
        p.type          = "poll";
        p.text          = text;
        p.pollQuestion  = question;
        p.pollOptions   = options;
        p.pollMultiSelect = multiSelect;
        p.pollExpiresAt = expiresAt;
        if (scheduledAtMs > 0) schedulePost(p, scheduledAtMs);
        else submitPost(p);
    }

    public void createAudioPost(String channelId, Uri audioUri, long durationMs, String caption,
                                 android.content.Context ctx) {
        createAudioPost(channelId, audioUri, durationMs, caption, ctx, 0);
    }

    public void createAudioPost(String channelId, Uri audioUri, long durationMs, String caption,
                                 android.content.Context ctx, long scheduledAtMs) {
        _uploadProgress.postValue(true);
        _uploadPercent.postValue(0);
        StorageReference ref = FirebaseStorage.getInstance().getReference()
            .child("channelAudio").child(channelId)
            .child(myUid + "_" + System.currentTimeMillis() + ".aac");

        ref.putFile(audioUri)
            .addOnProgressListener(snap -> {
                int pct = (int)(100.0 * snap.getBytesTransferred() / snap.getTotalByteCount());
                _uploadPercent.postValue(pct);
            })
            .addOnSuccessListener(snap -> ref.getDownloadUrl()
                .addOnSuccessListener(uri -> {
                    ChannelPost p = basePost(channelId);
                    p.type          = "audio";
                    p.text          = caption != null ? caption : "";
                    p.audioUrl      = uri.toString();
                    p.audioDurationMs = durationMs;
                    if (scheduledAtMs > 0) schedulePost(p, scheduledAtMs);
                    else submitPost(p);
                })
                .addOnFailureListener(e -> {
                    _uploadProgress.postValue(false);
                    _toastMessage.postValue("Audio upload failed.");
                }))
            .addOnFailureListener(e -> {
                _uploadProgress.postValue(false);
                _toastMessage.postValue("Audio upload failed: " + e.getMessage());
            });
    }

    public void createDocumentPost(String channelId, Uri documentUri, String documentName,
                                    long documentSizeBytes, String mimeType, String caption,
                                    android.content.Context ctx) {
        createDocumentPost(channelId, documentUri, documentName, documentSizeBytes, mimeType, caption, ctx, 0);
    }

    public void createDocumentPost(String channelId, Uri documentUri, String documentName,
                                    long documentSizeBytes, String mimeType, String caption,
                                    android.content.Context ctx, long scheduledAtMs) {
        _uploadProgress.postValue(true);
        _uploadPercent.postValue(0);
        String ext = mimeType != null && mimeType.contains("pdf") ? ".pdf" : "";
        StorageReference ref = FirebaseStorage.getInstance().getReference()
            .child("channelDocs").child(channelId)
            .child(myUid + "_" + System.currentTimeMillis() + ext);

        ref.putFile(documentUri)
            .addOnProgressListener(snap -> {
                int pct = (int)(100.0 * snap.getBytesTransferred() / snap.getTotalByteCount());
                _uploadPercent.postValue(pct);
            })
            .addOnSuccessListener(snap -> ref.getDownloadUrl()
                .addOnSuccessListener(uri -> {
                    ChannelPost p = basePost(channelId);
                    p.type              = "document";
                    p.text              = caption != null ? caption : "";
                    p.documentUrl       = uri.toString();
                    p.documentName      = documentName;
                    p.documentSizeBytes = documentSizeBytes;
                    p.documentMimeType  = mimeType;
                    if (scheduledAtMs > 0) schedulePost(p, scheduledAtMs);
                    else submitPost(p);
                })
                .addOnFailureListener(e -> {
                    _uploadProgress.postValue(false);
                    _toastMessage.postValue("Document upload failed.");
                }))
            .addOnFailureListener(e -> {
                _uploadProgress.postValue(false);
                _toastMessage.postValue("Document upload failed: " + e.getMessage());
            });
    }

    private void submitPost(ChannelPost p) {
        repo.postToChannel(p, ok -> {
            _uploadProgress.postValue(false);
            _uploadPercent.postValue(0);
            if (ok) {
                _postSuccess.postValue(true);
                _toastMessage.postValue("Posted!");
            } else {
                _toastMessage.postValue("Failed to post. Try again.");
            }
        });
    }

    // ── Schedule Post ─────────────────────────────────────────────────────

    public void schedulePost(ChannelPost post, long scheduledAtMs) {
        repo.schedulePost(post, scheduledAtMs, ok ->
            _toastMessage.postValue(ok ? "Post scheduled!" : "Failed to schedule post."));
    }

    public void publishScheduledPost(String channelId, String postId) {
        repo.publishScheduledPost(channelId, postId, ok ->
            _toastMessage.postValue(ok ? "Post published!" : "Failed to publish."));
    }

    public void deleteScheduledPost(String channelId, String postId) {
        repo.deleteScheduledPost(channelId, postId, ok ->
            _toastMessage.postValue(ok ? "Scheduled post deleted." : "Failed to delete."));
    }

    // ── Edit / Delete Post ────────────────────────────────────────────────

    public void editPost(String channelId, String postId, String newText) {
        repo.editPost(channelId, postId, newText, ok ->
            _toastMessage.postValue(ok ? "Post updated." : "Failed to edit post."));
    }

    public void deletePost(ChannelPost post) {
        if (post == null) return;
        repo.deletePost(post.channelId, post.id, ok ->
            _toastMessage.postValue(ok ? "Post deleted." : "Failed to delete."));
    }

    // ── Pin / Unpin Post ──────────────────────────────────────────────────

    public void pinPost(String channelId, String postId) {
        repo.pinPost(channelId, postId, ok ->
            _toastMessage.postValue(ok ? "Post pinned." : "Failed to pin post."));
    }

    public void unpinPost(String channelId, String postId) {
        repo.unpinPost(channelId, postId, ok ->
            _toastMessage.postValue(ok ? "Post unpinned." : "Failed to unpin."));
    }

    // ── Reactions ─────────────────────────────────────────────────────────

    public void reactToPost(String channelId, String postId, String emoji) {
        repo.reactToPost(myUid, channelId, postId, emoji, null);
    }

    public void removeReaction(String channelId, String postId) {
        repo.removeReaction(myUid, channelId, postId, null);
    }

    // ── Poll Voting ───────────────────────────────────────────────────────

    public void voteOnPoll(ChannelPost post, int optionIndex) {
        if (post == null) return;
        repo.voteOnPoll(myUid, post.channelId, post.id, optionIndex, ok -> {
            if (!ok) _toastMessage.postValue("Failed to vote. Try again.");
        });
    }

    // ── View / Forward Count ──────────────────────────────────────────────

    public void incrementPostView(String channelId, String postId) {
        repo.incrementPostView(channelId, postId);
    }

    public void recordForward(String channelId, String postId) {
        repo.recordForward(channelId, postId);
    }

    // ── Forward Post to Chat ──────────────────────────────────────────────

    public void forwardPostToChat(String chatId, boolean isGroup, ChannelPost post, String channelName) {
        repo.forwardPostToChat(myUid, chatId, isGroup, post, channelName, ok ->
            _toastMessage.postValue(ok ? "Forwarded!" : "Failed to forward."));
    }

    // ── Admin Management ──────────────────────────────────────────────────

    public void loadAdmins(String channelId, ChannelRepository.MapResult cb) {
        repo.loadAdmins(channelId, cb);
    }

    public void addAdmin(String channelId, String uid) {
        repo.addAdmin(channelId, uid, ok ->
            _toastMessage.postValue(ok ? "Admin added." : "Failed to add admin."));
    }

    public void removeAdmin(String channelId, String uid) {
        repo.removeAdmin(channelId, uid, ok ->
            _toastMessage.postValue(ok ? "Admin removed." : "Failed to remove admin."));
    }

    public void transferOwnership(String channelId, String currentOwnerUid, String newOwnerUid) {
        repo.transferOwnership(channelId, currentOwnerUid, newOwnerUid, ok ->
            _toastMessage.postValue(ok ? "Ownership transferred!" : "Failed to transfer ownership."));
    }

    // ── Followers ─────────────────────────────────────────────────────────

    public void loadFollowers(String channelId, int limit) {
        repo.loadChannelFollowers(channelId, limit, list -> _followers.postValue(list));
    }

    public void blockFollower(String channelId, String targetUid) {
        repo.blockFollower(channelId, targetUid, ok ->
            _toastMessage.postValue(ok ? "Follower removed." : "Failed to remove follower."));
    }

    public void unblockFollower(String channelId, String targetUid) {
        repo.unblockFollower(channelId, targetUid, ok ->
            _toastMessage.postValue(ok ? "Follower unblocked." : "Failed."));
    }

    // ── Report ────────────────────────────────────────────────────────────

    public void reportChannel(String channelId, String reason) {
        repo.reportChannel(myUid, channelId, reason, ok ->
            _toastMessage.postValue(ok ? "Channel reported. Thank you." : "Report failed."));
    }

    public void reportPost(String channelId, String postId, String reason) {
        repo.reportPost(myUid, channelId, postId, reason, ok ->
            _toastMessage.postValue(ok ? "Post reported. Thank you." : "Report failed."));
    }

    // ── Interaction Flags ─────────────────────────────────────────────────

    public void setAllowReactions(String channelId, String postId, boolean allow) {
        repo.setAllowReactions(channelId, postId, allow, ok ->
            _toastMessage.postValue(ok ? (allow ? "Reactions enabled." : "Reactions disabled.")
                                       : "Failed to update setting."));
    }

    public void setAllowForward(String channelId, String postId, boolean allow) {
        repo.setAllowForward(channelId, postId, allow, ok ->
            _toastMessage.postValue(ok ? (allow ? "Forwarding enabled." : "Forwarding disabled.")
                                       : "Failed to update setting."));
    }

    // ── Search ────────────────────────────────────────────────────────────

    public void searchPosts(String channelId, String query) {
        if (query == null || query.trim().isEmpty()) {
            _searchResults.postValue(new ArrayList<>());
            return;
        }
        repo.searchPosts(channelId, query.trim(), results ->
            _searchResults.postValue(results != null ? results : new ArrayList<>()));
    }

    public void searchChannels(String query) {
        if (query == null || query.trim().isEmpty()) {
            _channelSearchResults.postValue(new ArrayList<>());
            return;
        }
        repo.searchChannels(query.trim(), results ->
            _channelSearchResults.postValue(results != null ? results : new ArrayList<>()));
    }

    // ── Read Tracking ─────────────────────────────────────────────────────

    public void markChannelRead(String channelId, long latestPostTimestamp) {
        repo.markChannelRead(myUid, channelId, latestPostTimestamp);
    }

    // ── Sync ─────────────────────────────────────────────────────────────

    public void startSyncingPosts(String channelId) {
        repo.syncChannelPosts(channelId);
    }

    public void stopSyncingPosts(String channelId) {
        repo.stopSyncingPosts(channelId);
    }

    public void loadMorePosts(String channelId, long beforeTimestamp) {
        repo.loadMorePosts(channelId, beforeTimestamp, null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    public boolean isOwner(ChannelEntity ch) {
        return ch != null && myUid != null && myUid.equals(ch.ownerUid);
    }

    public boolean isAdminOrOwner(ChannelEntity ch) {
        return ch != null && (myUid.equals(ch.ownerUid) || ch.isAdmin);
    }

    public String getMyUid()     { return myUid; }
    public String getMyName()    { return myName; }
    public String getMyIconUrl() { return myIconUrl; }

    private ChannelPost basePost(String channelId) {
        ChannelPost p = new ChannelPost();
        p.channelId   = channelId;
        p.authorUid   = myUid;
        p.authorName  = myName;
        p.authorIconUrl = myIconUrl;
        p.timestamp   = System.currentTimeMillis();
        p.allowReactions = true;
        p.allowForward   = true;
        return p;
    }

    /** @deprecated Use createTextPost instead. */
    @Deprecated
    public void createPost(String channelId, String text) { createTextPost(channelId, text); }

    private void postToast(String msg) { _toastMessage.postValue(msg); }

    // ═══════════════════════════════════════════════════════════════════════
    // ── REPLY SYSTEM (v3) ─────────────────────────────────────────────────

    public void addReply(String channelId, String postId, String text) {
        if (channelId == null || postId == null || text == null || text.trim().isEmpty()) return;
        repo.addReply(myUid, myName, myIconUrl, channelId, postId, text.trim(),
                ok -> { if (!ok) postToast("Failed to send reply."); });
    }

    public void reactToReply(String channelId, String postId, String replyId, String emoji) {
        repo.reactToReply(myUid, channelId, postId, replyId, emoji,
                ok -> { if (!ok) postToast("Failed to add reaction."); });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ── JOIN BY INVITE CODE (v3) ──────────────────────────────────────────

    public void joinChannelByInviteCode(String inviteCode, ChannelRepository.JoinChannelResult cb) {
        repo.joinChannelByInviteCode(myUid, inviteCode, cb);
    }

}