package com.callx.app.viewmodel;

import android.app.Application;
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
import java.util.List;

/**
 * ChannelViewModel — bridges ChannelRepository (core) and the Updates-tab UI.
 *
 * WhatsApp-level pattern:
 *   UI (StatusFragment / ChannelViewerActivity)
 *     → observes LiveData from this ViewModel
 *     → never touches Firebase or Room directly
 *
 * All write ops (follow, unfollow, create, post) go through the Repository.
 */
public class ChannelViewModel extends AndroidViewModel {

    private final ChannelRepository repo;
    private final String myUid;

    // ── Exposed LiveData ──────────────────────────────────────────────────

    /** Channels the current user follows — drives the main Channels list. */
    public final LiveData<List<ChannelEntity>> followedChannels;

    /** Suggested channels — drives the "Find channels to follow" section. */
    public final LiveData<List<ChannelEntity>> suggestedChannels;

    // ── One-shot events ────────────────────────────────────────────────────

    private final MutableLiveData<String> _toastMessage = new MutableLiveData<>();
    public final LiveData<String> toastMessage = _toastMessage;

    private final MutableLiveData<Boolean> _loading = new MutableLiveData<>(false);
    public final LiveData<Boolean> loading = _loading;

    public ChannelViewModel(@NonNull Application app) {
        super(app);
        repo   = ChannelRepository.getInstance(app);
        myUid  = FirebaseUtils.getCurrentUid();

        followedChannels  = repo.getFollowedChannels();
        suggestedChannels = repo.getSuggestedChannels(20);
    }

    // ── Called from UI lifecycle (onStart / onResume) ─────────────────────

    /** Refresh channel data from Firebase → Room → LiveData (auto-notified). */
    public void refresh() {
        repo.syncFollowedChannels(myUid);
        repo.syncSuggestedChannels();
    }

    // ── Follow / Unfollow ─────────────────────────────────────────────────

    public void followChannel(ChannelEntity ch) {
        if (myUid == null || myUid.isEmpty() || ch.id == null) return;
        repo.followChannel(myUid, ch.id, ok -> {
            if (ok) _toastMessage.postValue("Following " + ch.name);
        });
    }

    public void unfollowChannel(ChannelEntity ch) {
        if (myUid == null || myUid.isEmpty() || ch.id == null) return;
        repo.unfollowChannel(myUid, ch.id, ok -> {
            if (ok) _toastMessage.postValue("Unfollowed " + ch.name);
        });
    }

    // ── Create channel ────────────────────────────────────────────────────

    public interface CreateCallback { void onCreated(ChannelEntity channel); void onFailed(); }

    public void createChannel(String name, String description, String iconUrl,
                              CreateCallback cb) {
        _loading.postValue(true);
        Channel ch = new Channel();
        ch.name        = name;
        ch.description = description;
        ch.iconUrl     = iconUrl;
        ch.ownerUid    = myUid;
        ch.verified    = false;
        ch.category    = "General";
        ch.followers   = 1;
        ch.createdAt   = System.currentTimeMillis();

        repo.createChannel(ch, ok -> {
            _loading.postValue(false);
            if (ok) {
                // Auto-follow own channel
                if (ch.id != null) repo.followChannel(myUid, ch.id, null);
                // Build entity for callback
                com.callx.app.db.entity.ChannelEntity e = new com.callx.app.db.entity.ChannelEntity();
                e.id       = ch.id != null ? ch.id : "";
                e.name     = ch.name;
                e.iconUrl  = ch.iconUrl;
                e.ownerUid = ch.ownerUid;
                e.isFollowed = true;
                if (cb != null) cb.onCreated(e);
            } else {
                if (cb != null) cb.onFailed();
            }
        });
    }

    // ── All channels (Explore screen) ────────────────────────────────────

    /** All channels ordered by followers — drives ExploreChannelsActivity. */
    public LiveData<List<ChannelEntity>> getAllChannels(int limit) {
        return repo.getAllChannels(limit);
    }

    /** Single channel LiveData — for ChannelViewerActivity header / follow state. */
    public LiveData<ChannelEntity> getChannel(String channelId) {
        return repo.getChannel(channelId);
    }

    // ── Posts ────────────────────────────────────────────────────────────

    /** Get LiveData of posts for a channel — for ChannelViewerActivity. */
    public LiveData<List<ChannelPostEntity>> getChannelPosts(String channelId) {
        repo.syncChannelPosts(channelId);
        return repo.getChannelPosts(channelId, 50);
    }

    public void stopSyncingPosts(String channelId) {
        repo.stopSyncingChannelPosts(channelId);
    }

    public void reactToPost(String channelId, String postId, String emoji) {
        repo.reactToPost(myUid, channelId, postId, emoji);
    }

    public void incrementView(String channelId, String postId) {
        repo.incrementPostView(channelId, postId);
    }

    /** True if the current user owns this channel — drives the "New Post" button. */
    public boolean isOwner(ChannelEntity ch) {
        return ch != null && myUid != null && myUid.equals(ch.ownerUid);
    }

    /** Post a new text update into the channel. */
    public void createPost(String channelId, String text) {
        if (text == null || text.trim().isEmpty()) return;
        _loading.postValue(true);
        com.callx.app.models.ChannelPost post = new com.callx.app.models.ChannelPost();
        post.text      = text.trim();
        post.type      = "text";
        post.timestamp = System.currentTimeMillis();
        repo.postToChannel(channelId, post, ok -> {
            _loading.postValue(false);
            _toastMessage.postValue(ok ? "Posted" : "Failed to post");
        });
    }

    // ── Helper ────────────────────────────────────────────────────────────

    /** Convert Channel model → ChannelEntity for passing to createChannel. */
    private com.callx.app.db.entity.ChannelEntity modelToEntity(Channel ch) {
        com.callx.app.db.entity.ChannelEntity e = new com.callx.app.db.entity.ChannelEntity();
        e.id       = ch.id != null ? ch.id : "";
        e.name     = ch.name;
        e.iconUrl  = ch.iconUrl;
        e.ownerUid = ch.ownerUid;
        return e;
    }
}
