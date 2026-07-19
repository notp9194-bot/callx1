package com.callx.app.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.ChannelDao;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.db.entity.ChannelPostEntity;
import com.callx.app.models.Channel;
import com.callx.app.models.ChannelPost;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChannelRepository — Single source of truth for Channel data.
 *
 * Architecture:
 *   UI → ViewModel → ChannelRepository → (Room DB + Firebase)
 *
 * Offline-first: Room is always the source of truth.
 * Firebase writes flow: Firebase → Room → LiveData → ViewModel → UI.
 *
 * Replaces the old ChannelManager from feature-status.
 */
public class ChannelRepository {

    private static volatile ChannelRepository sInstance;

    private final ChannelDao        dao;
    private final ExecutorService   executor = Executors.newFixedThreadPool(3);
    private final Handler           mainHandler = new Handler(Looper.getMainLooper());

    // Active Firebase listeners (keyed by channelId for posts, "followed" for followed list)
    private final Map<String, ValueEventListener> activeListeners = new HashMap<>();

    private ChannelRepository(Context ctx) {
        this.dao = AppDatabase.getInstance(ctx).channelDao();
    }

    public static ChannelRepository getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (ChannelRepository.class) {
                if (sInstance == null) sInstance = new ChannelRepository(ctx.getApplicationContext());
            }
        }
        return sInstance;
    }

    // ── READ — LiveData (UI observes these) ───────────────────────────────

    /** Followed channels — Room LiveData, auto-updates as DB changes. */
    public LiveData<List<ChannelEntity>> getFollowedChannels() {
        return dao.getFollowedChannels();
    }

    /** Suggested channels — Room LiveData. */
    public LiveData<List<ChannelEntity>> getSuggestedChannels(int limit) {
        return dao.getSuggestedChannels(limit);
    }

    /** All channels for Explore screen. */
    public LiveData<List<ChannelEntity>> getAllChannels(int limit) {
        return dao.getAllChannels(limit);
    }

    /** Single channel — Room LiveData. */
    public LiveData<ChannelEntity> getChannel(String channelId) {
        return dao.getChannel(channelId);
    }

    /** Posts for a channel — Room LiveData, newest first. */
    public LiveData<List<ChannelPostEntity>> getChannelPosts(String channelId, int limit) {
        return dao.getChannelPosts(channelId, limit);
    }

    // ── SYNC — Pull from Firebase into Room ───────────────────────────────

    /**
     * Fetch followed channels from Firebase and cache them in Room.
     * Call from ViewModel.onStart() or when tab becomes visible.
     */
    public void syncFollowedChannels(String uid) {
        if (uid == null || uid.isEmpty()) return;
        FirebaseUtils.getChannelFollowsRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        if (Boolean.TRUE.equals(ds.getValue(Boolean.class)) && ds.getKey() != null)
                            ids.add(ds.getKey());
                    }
                    // Mark all previously-followed as unfollowed first (full sync)
                    executor.execute(() -> {
                        List<ChannelEntity> old = dao.getFollowedChannelsSync();
                        for (ChannelEntity ch : old) dao.setFollowed(ch.id, false);
                    });
                    fetchAndCacheChannels(ids, true);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * Fetch top channels from Firebase for the Suggest/Explore sections.
     */
    public void syncSuggestedChannels() {
        FirebaseUtils.getChannelsRef()
            .orderByChild("followers")
            .limitToLast(30)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    executor.execute(() -> {
                        for (DataSnapshot ds : snap.getChildren()) {
                            Channel ch = ds.getValue(Channel.class);
                            if (ch == null) continue;
                            ch.id = ds.getKey();
                            ChannelEntity existing = dao.getChannelSync(ch.id);
                            // Only insert if not already in DB (don't overwrite isFollowed flag)
                            if (existing == null) {
                                dao.insertChannel(modelToEntity(ch, false));
                            }
                        }
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * Sync posts for a specific channel. Attach a live listener so new posts
     * appear in real time via Room LiveData.
     */
    public void syncChannelPosts(String channelId) {
        if (activeListeners.containsKey(channelId)) return; // already listening
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                executor.execute(() -> {
                    List<ChannelPostEntity> posts = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        ChannelPost p = ds.getValue(ChannelPost.class);
                        if (p == null) continue;
                        p.id = ds.getKey();
                        p.channelId = channelId;
                        posts.add(postModelToEntity(p));
                    }
                    dao.insertPosts(posts);
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getChannelPostsRef(channelId)
            .orderByChild("timestamp")
            .limitToLast(50)
            .addValueEventListener(listener);
        activeListeners.put(channelId, listener);
    }

    /** Remove live listener for a channel (call from onStop). */
    public void stopSyncingChannelPosts(String channelId) {
        ValueEventListener l = activeListeners.remove(channelId);
        if (l != null)
            FirebaseUtils.getChannelPostsRef(channelId).removeEventListener(l);
    }

    // ── WRITE — Follow / Unfollow ─────────────────────────────────────────

    public interface Result { void onDone(boolean success); }

    /** Follow a channel — optimistic local update + Firebase write. */
    public void followChannel(String uid, String channelId, Result cb) {
        // 1. Optimistic Room update
        executor.execute(() -> {
            dao.setFollowed(channelId, true);
            dao.incrementFollowers(channelId);
        });

        // 2. Firebase write
        Map<String, Object> updates = new HashMap<>();
        updates.put("channelFollows/" + uid + "/" + channelId, true);
        FirebaseUtils.db().getReference("channels").child(channelId).child("followers")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long current = snap.getValue(Long.class) != null ? snap.getValue(Long.class) : 0;
                    updates.put("channels/" + channelId + "/followers", current + 1);
                    FirebaseUtils.db().getReference().updateChildren(updates,
                        (e, ref) -> mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (cb != null) mainHandler.post(() -> cb.onDone(false));
                }
            });
    }

    /** Unfollow a channel — optimistic local update + Firebase write. */
    public void unfollowChannel(String uid, String channelId, Result cb) {
        // 1. Optimistic Room update
        executor.execute(() -> {
            dao.setFollowed(channelId, false);
            dao.decrementFollowers(channelId);
        });

        // 2. Firebase write
        FirebaseUtils.getChannelFollowsRef(uid).child(channelId)
            .removeValue((e1, ref1) -> {
                if (e1 != null) { if (cb != null) mainHandler.post(() -> cb.onDone(false)); return; }
                FirebaseUtils.db().getReference("channels").child(channelId).child("followers")
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap) {
                            long current = snap.getValue(Long.class) != null ? snap.getValue(Long.class) : 0;
                            FirebaseUtils.db().getReference("channels")
                                .child(channelId).child("followers")
                                .setValue(Math.max(0, current - 1),
                                    (e2, r2) -> mainHandler.post(() -> {
                                        if (cb != null) cb.onDone(e2 == null);
                                    }));
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            if (cb != null) mainHandler.post(() -> cb.onDone(false));
                        }
                    });
            });
    }

    // ── WRITE — Create Channel ─────────────────────────────────────────────

    public void createChannel(Channel channel, Result cb) {
        DatabaseReference ref = FirebaseUtils.getChannelsRef().push();
        channel.id = ref.getKey();
        ref.setValue(channel, (e, r) -> {
            if (e == null) {
                executor.execute(() -> dao.insertChannel(modelToEntity(channel, true)));
            }
            if (cb != null) mainHandler.post(() -> cb.onDone(e == null));
        });
    }

    // ── WRITE — Post to Channel ────────────────────────────────────────────

    public void postToChannel(String channelId, ChannelPost post, Result cb) {
        DatabaseReference ref = FirebaseUtils.getChannelPostsRef(channelId).push();
        post.id  = ref.getKey();
        post.channelId = channelId;
        ref.setValue(post, (e, r) -> {
            if (e == null) {
                executor.execute(() -> dao.insertPost(postModelToEntity(post)));
                // Update channel last-post cache
                Map<String, Object> upd = new HashMap<>();
                upd.put("channels/" + channelId + "/lastPostText",
                        post.text != null ? post.text : "");
                upd.put("channels/" + channelId + "/lastPostMediaUrl",
                        post.mediaUrl != null ? post.mediaUrl : "");
                upd.put("channels/" + channelId + "/lastPostType",
                        post.type != null ? post.type : "text");
                upd.put("channels/" + channelId + "/lastPostAt", post.timestamp);
                FirebaseUtils.db().getReference().updateChildren(upd);
            }
            if (cb != null) mainHandler.post(() -> cb.onDone(e == null));
        });
    }

    // ── WRITE — Reactions / Views ─────────────────────────────────────────

    public void reactToPost(String uid, String channelId, String postId, String emoji) {
        FirebaseUtils.getChannelPostsRef(channelId)
            .child(postId).child("reactions").child(uid).setValue(emoji);
    }

    public void incrementPostView(String channelId, String postId) {
        executor.execute(() -> dao.incrementViewCount(postId));
        FirebaseUtils.getChannelPostsRef(channelId)
            .child(postId).child("viewCount")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long v = d.getValue(Long.class);
                    d.setValue(v == null ? 1 : v + 1);
                    return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) {}
            });
    }

    // ── Check isFollowing ─────────────────────────────────────────────────

    public void isFollowing(String uid, String channelId, Result cb) {
        executor.execute(() -> {
            boolean following = dao.isFollowed(channelId);
            mainHandler.post(() -> { if (cb != null) cb.onDone(following); });
        });
    }

    // ── Converters ────────────────────────────────────────────────────────

    private ChannelEntity modelToEntity(Channel ch, boolean isFollowed) {
        ChannelEntity e = new ChannelEntity();
        e.id              = ch.id != null ? ch.id : "";
        e.name            = ch.name;
        e.description     = ch.description;
        e.iconUrl         = ch.iconUrl;
        e.followers       = ch.followers;
        e.verified        = ch.verified;
        e.category        = ch.category;
        e.ownerUid        = ch.ownerUid;
        e.createdAt       = ch.createdAt;
        e.lastPostAt      = ch.lastPostAt;
        e.lastPostText    = ch.lastPostText;
        e.lastPostMediaUrl= ch.lastPostMediaUrl;
        e.lastPostType    = ch.lastPostType;
        e.isFollowed      = isFollowed;
        e.syncedAt        = System.currentTimeMillis();
        return e;
    }

    public Channel entityToModel(ChannelEntity e) {
        Channel ch = new Channel();
        ch.id             = e.id;
        ch.name           = e.name;
        ch.description    = e.description;
        ch.iconUrl        = e.iconUrl;
        ch.followers      = e.followers;
        ch.verified       = e.verified;
        ch.category       = e.category;
        ch.ownerUid       = e.ownerUid;
        ch.createdAt      = e.createdAt;
        ch.lastPostAt     = e.lastPostAt;
        ch.lastPostText   = e.lastPostText;
        ch.lastPostMediaUrl = e.lastPostMediaUrl;
        ch.lastPostType   = e.lastPostType;
        return ch;
    }

    private ChannelPostEntity postModelToEntity(ChannelPost p) {
        ChannelPostEntity e = new ChannelPostEntity();
        e.id           = p.id != null ? p.id : "";
        e.channelId    = p.channelId;
        e.text         = p.text;
        e.type         = p.type;
        e.mediaUrl     = p.mediaUrl;
        e.thumbnailUrl = p.thumbnailUrl;
        e.linkUrl      = p.linkUrl;
        e.linkTitle    = p.linkTitle;
        e.linkDescription = p.linkDescription;
        e.timestamp    = p.timestamp;
        e.viewCount    = p.viewCount;
        e.forwardCount = p.forwardCount;
        if (p.reactions != null) {
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<String, String> en : p.reactions.entrySet()) {
                sb.append("\"").append(en.getKey()).append("\":\"").append(en.getValue()).append("\",");
            }
            if (sb.length() > 1) sb.setCharAt(sb.length() - 1, '}');
            else sb.append("}");
            e.reactionsJson = sb.toString();
        }
        e.syncedAt = System.currentTimeMillis();
        return e;
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private void fetchAndCacheChannels(List<String> ids, boolean isFollowed) {
        if (ids.isEmpty()) return;
        for (String id : ids) {
            FirebaseUtils.getChannelsRef().child(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Channel ch = snap.getValue(Channel.class);
                        if (ch == null) return;
                        ch.id = snap.getKey();
                        executor.execute(() -> dao.insertChannel(modelToEntity(ch, isFollowed)));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        }
    }
}
