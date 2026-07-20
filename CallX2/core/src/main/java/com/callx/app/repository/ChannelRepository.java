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
 * WhatsApp-level v2 features:
 *   - Follow / unfollow with optimistic updates + follower reverse index
 *   - Mute / unmute per channel with expiry
 *   - Post: text, image, video, link, poll, audio, document
 *   - Edit post (owner/admin only)
 *   - Delete post (soft delete)
 *   - Forward post (to Firebase chat / group)
 *   - Emoji reactions (full map: uid → emoji)
 *   - Poll voting (single + multi-select + expiry)
 *   - View count (debounced per-session)
 *   - Pin / unpin post
 *   - Schedule post (publish at future timestamp)
 *   - Draft save / discard
 *   - Channel admin management (add, remove, transfer ownership)
 *   - Report channel / report post
 *   - Pagination (cursor-based)
 *   - Per-channel notification prefs
 *   - Unread count tracking
 *   - Invite link generation (for private channels)
 *   - Block / unblock followers
 *   - Channel edit (name, desc, icon, category, privacy)
 *   - Trending sort (weeklyGrowth)
 *   - Channel followers list (admin)
 */
public class ChannelRepository {

    private static volatile ChannelRepository sInstance;

    private final ChannelDao        dao;
    private final ExecutorService   executor = Executors.newFixedThreadPool(4);
    private final Handler           mainHandler = new Handler(Looper.getMainLooper());

    // Active Firebase listeners (keyed by channelId for posts, "followed" for followed list)
    private final Map<String, ValueEventListener> activeListeners = new HashMap<>();

    // Debounce set for view counts (avoid double-counting the same post in one session)
    private final Set<String> viewedPostIds = Collections.synchronizedSet(new HashSet<>());

    public interface Result { void onDone(boolean success); }
    public interface StringResult { void onResult(String value); }
    public interface MapResult { void onResult(Map<String, String> map); }
    public interface ListResult<T> { void onResult(List<T> list); }

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

    public LiveData<List<ChannelEntity>> getFollowedChannels() {
        return dao.getFollowedChannels();
    }

    public LiveData<List<ChannelEntity>> getSuggestedChannels(int limit) {
        return dao.getSuggestedChannels(limit);
    }

    public LiveData<List<ChannelEntity>> getAllChannels(int limit) {
        return dao.getAllChannels(limit);
    }

    public LiveData<List<ChannelEntity>> getTrendingChannels(int limit) {
        return dao.getTrendingChannels(limit);
    }

    public LiveData<List<ChannelEntity>> getChannelsByCategory(String category, int limit) {
        return dao.getChannelsByCategory(category, limit);
    }

    public LiveData<ChannelEntity> getChannel(String channelId) {
        return dao.getChannel(channelId);
    }

    public LiveData<List<ChannelPostEntity>> getChannelPosts(String channelId, int limit) {
        return dao.getChannelPosts(channelId, limit);
    }

    public LiveData<ChannelPostEntity> getPinnedPost(String channelId) {
        return dao.getPinnedPost(channelId);
    }

    public LiveData<List<ChannelPostEntity>> getScheduledPosts(String channelId) {
        return dao.getScheduledPosts(channelId);
    }

    public LiveData<List<ChannelPostEntity>> getDraftPosts(String channelId) {
        return dao.getDraftPosts(channelId);
    }

    public LiveData<List<ChannelPostEntity>> getChannelPostsBefore(String channelId,
                                                                    long beforeTimestamp,
                                                                    int limit) {
        return dao.getChannelPostsBefore(channelId, beforeTimestamp, limit);
    }

    // ── SYNC ──────────────────────────────────────────────────────────────

    public void syncFollowedChannels(String uid) {
        if (uid == null || uid.isEmpty()) return;
        FirebaseUtils.getChannelFollowsRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) ids.add(ds.getKey());
                    fetchAndCacheChannels(ids, true);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    public void syncSuggestedChannels() {
        FirebaseUtils.getChannelsRef().orderByChild("followers").limitToLast(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    executor.execute(() -> {
                        List<ChannelEntity> toInsert = new ArrayList<>();
                        for (DataSnapshot ds : snap.getChildren()) {
                            Channel ch = ds.getValue(Channel.class);
                            if (ch == null) continue;
                            ch.id = ds.getKey();
                            ChannelEntity existing = dao.getChannelSync(ch.id);
                            boolean isFollowed = existing != null && existing.isFollowed;
                            toInsert.add(modelToEntity(ch, isFollowed));
                        }
                        if (!toInsert.isEmpty()) dao.insertChannels(toInsert);
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    public void syncTrendingChannels() {
        FirebaseUtils.getChannelsRef().orderByChild("weeklyGrowth").limitToLast(30)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    executor.execute(() -> {
                        for (DataSnapshot ds : snap.getChildren()) {
                            Channel ch = ds.getValue(Channel.class);
                            if (ch == null) continue;
                            ch.id = ds.getKey();
                            ChannelEntity existing = dao.getChannelSync(ch.id);
                            boolean isFollowed = existing != null && existing.isFollowed;
                            dao.insertChannel(modelToEntity(ch, isFollowed));
                        }
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    public void syncMutedChannels(String uid) {
        if (uid == null || uid.isEmpty()) return;
        FirebaseUtils.getChannelMutesRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long now = System.currentTimeMillis();
                    executor.execute(() -> {
                        for (DataSnapshot ds : snap.getChildren()) {
                            String channelId = ds.getKey();
                            Long mutedUntil = ds.child("mutedUntil").getValue(Long.class);
                            // If mutedUntil == 0 → permanent; if > now → still muted; else expired
                            boolean muted = (mutedUntil == null || mutedUntil == 0L || mutedUntil > now);
                            dao.setMuted(channelId, muted);
                        }
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    public void syncChannelPosts(String channelId) {
        if (channelId == null) return;
        // Remove stale listener first
        stopSyncingPosts(channelId);
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                executor.execute(() -> {
                    for (DataSnapshot ds : snap.getChildren()) {
                        ChannelPost p = ds.getValue(ChannelPost.class);
                        if (p == null) continue;
                        p.id = ds.getKey();
                        p.channelId = channelId;
                        dao.insertPost(postModelToEntity(p));
                    }
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getChannelPostsRef(channelId)
            .orderByChild("timestamp").limitToLast(50)
            .addValueEventListener(listener);
        activeListeners.put(channelId, listener);
    }

    public void stopSyncingPosts(String channelId) {
        ValueEventListener old = activeListeners.remove(channelId);
        if (old != null)
            FirebaseUtils.getChannelPostsRef(channelId).removeEventListener(old);
    }

    public void loadMorePosts(String channelId, long beforeTimestamp, Result cb) {
        FirebaseUtils.getChannelPostsRef(channelId)
            .orderByChild("timestamp").endBefore(beforeTimestamp).limitToLast(20)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    executor.execute(() -> {
                        for (DataSnapshot ds : snap.getChildren()) {
                            ChannelPost p = ds.getValue(ChannelPost.class);
                            if (p == null) continue;
                            p.id = ds.getKey(); p.channelId = channelId;
                            dao.insertPost(postModelToEntity(p));
                        }
                        mainHandler.post(() -> { if (cb != null) cb.onDone(true); });
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    mainHandler.post(() -> { if (cb != null) cb.onDone(false); });
                }
            });
    }

    // ── FOLLOW / UNFOLLOW ─────────────────────────────────────────────────

    public void followChannel(String uid, String channelId, Result cb) {
        executor.execute(() -> {
            dao.setFollowed(channelId, true);
            dao.incrementFollowers(channelId);
        });
        Map<String, Object> updates = new HashMap<>();
        updates.put("channelFollows/" + uid + "/" + channelId, true);
        updates.put("channelFollowers/" + channelId + "/" + uid + "/uid", uid);
        updates.put("channelFollowers/" + channelId + "/" + uid + "/joinedAt",
            ServerValue.TIMESTAMP);
        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) -> {
            if (e == null) {
                // Atomic increment
                FirebaseUtils.getChannelRef(channelId).child("followers")
                    .runTransaction(new Transaction.Handler() {
                        @NonNull @Override
                        public Transaction.Result doTransaction(@NonNull MutableData d) {
                            Long v = d.getValue(Long.class);
                            d.setValue(v == null ? 1 : v + 1);
                            return Transaction.success(d);
                        }
                        @Override public void onComplete(DatabaseError er, boolean committed,
                                                         DataSnapshot s) {}
                    });
                mainHandler.post(() -> { if (cb != null) cb.onDone(true); });
            } else {
                executor.execute(() -> {
                    dao.setFollowed(channelId, false);
                    dao.decrementFollowers(channelId);
                });
                mainHandler.post(() -> { if (cb != null) cb.onDone(false); });
            }
        });
    }

    public void unfollowChannel(String uid, String channelId, Result cb) {
        executor.execute(() -> {
            dao.setFollowed(channelId, false);
            dao.decrementFollowers(channelId);
        });
        Map<String, Object> updates = new HashMap<>();
        updates.put("channelFollows/" + uid + "/" + channelId, null);
        updates.put("channelFollowers/" + channelId + "/" + uid, null);
        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) -> {
            if (e == null) {
                FirebaseUtils.getChannelRef(channelId).child("followers")
                    .runTransaction(new Transaction.Handler() {
                        @NonNull @Override
                        public Transaction.Result doTransaction(@NonNull MutableData d) {
                            Long v = d.getValue(Long.class);
                            d.setValue(v == null || v <= 0 ? 0 : v - 1);
                            return Transaction.success(d);
                        }
                        @Override public void onComplete(DatabaseError er, boolean committed,
                                                         DataSnapshot s) {}
                    });
                mainHandler.post(() -> { if (cb != null) cb.onDone(true); });
            } else {
                executor.execute(() -> {
                    dao.setFollowed(channelId, true);
                    dao.incrementFollowers(channelId);
                });
                mainHandler.post(() -> { if (cb != null) cb.onDone(false); });
            }
        });
    }

    // ── MUTE / UNMUTE ─────────────────────────────────────────────────────

    public void muteChannel(String uid, String channelId, long mutedUntilMs, Result cb) {
        executor.execute(() -> dao.setMuted(channelId, true));
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("mutedUntil", mutedUntilMs);
        FirebaseUtils.getChannelMuteRef(uid, channelId).setValue(prefs, (e, ref) ->
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
    }

    public void unmuteChannel(String uid, String channelId, Result cb) {
        executor.execute(() -> dao.setMuted(channelId, false));
        FirebaseUtils.getChannelMuteRef(uid, channelId).removeValue((e, ref) ->
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
    }

    // ── CREATE CHANNEL ────────────────────────────────────────────────────

    public interface CreateChannelResult { void onCreated(ChannelEntity ch); void onFailed(); }

    public void createChannel(String uid, String name, String desc, String iconUrl,
                               String category, boolean isPrivate, CreateChannelResult cb) {
        String channelId = FirebaseUtils.getChannelsRef().push().getKey();
        if (channelId == null) { mainHandler.post(cb::onFailed); return; }

        String inviteCode = isPrivate ? generateInviteCode() : null;
        String inviteLink = isPrivate ? "https://callx.app/channel/join/" + inviteCode : null;

        Channel ch = new Channel();
        ch.id          = channelId;
        ch.name        = name;
        ch.description = desc;
        ch.iconUrl     = iconUrl;
        ch.ownerUid    = uid;
        ch.category    = category != null ? category : "General";
        ch.isPrivate   = isPrivate;
        ch.inviteCode  = inviteCode;
        ch.inviteLink  = inviteLink;
        ch.createdAt   = System.currentTimeMillis();
        ch.followers   = 1L;
        ch.verified    = false;

        Map<String, Object> updates = new HashMap<>();
        updates.put("channels/" + channelId, channelToMap(ch));
        updates.put("channelFollows/" + uid + "/" + channelId, true);
        updates.put("channelFollowers/" + channelId + "/" + uid + "/uid", uid);
        updates.put("channelFollowers/" + channelId + "/" + uid + "/joinedAt",
            ServerValue.TIMESTAMP);
        updates.put("channelAdmins/" + channelId + "/" + uid, "owner");
        if (inviteCode != null)
            updates.put("channelInviteCodes/" + inviteCode, channelId);

        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) -> {
            if (e != null) { mainHandler.post(cb::onFailed); return; }
            ChannelEntity entity = modelToEntity(ch, true);
            entity.isAdmin = true;
            executor.execute(() -> {
                dao.insertChannel(entity);
                mainHandler.post(() -> cb.onCreated(entity));
            });
        });
    }

    // ── EDIT CHANNEL ──────────────────────────────────────────────────────

    public void editChannel(String channelId, String name, String desc, String iconUrl,
                             String category, boolean isPrivate, Result cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("channels/" + channelId + "/name",        name);
        updates.put("channels/" + channelId + "/description", desc);
        updates.put("channels/" + channelId + "/iconUrl",     iconUrl);
        updates.put("channels/" + channelId + "/category",    category);
        updates.put("channels/" + channelId + "/isPrivate",   isPrivate);
        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) -> {
            if (e == null) {
                executor.execute(() -> dao.updateChannelMeta(channelId, name, desc, iconUrl, category, isPrivate));
            }
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
        });
    }

    // ── INVITE LINK ───────────────────────────────────────────────────────

    public void generateInviteLink(String channelId, StringResult cb) {
        String code = generateInviteCode();
        String link = "https://callx.app/channel/join/" + code;
        Map<String, Object> updates = new HashMap<>();
        updates.put("channels/" + channelId + "/inviteCode", code);
        updates.put("channels/" + channelId + "/inviteLink", link);
        updates.put("channelInviteCodes/" + code, channelId);
        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) -> {
            if (e == null) executor.execute(() -> dao.setInviteLink(channelId, code, link));
            mainHandler.post(() -> { if (cb != null) cb.onResult(e == null ? link : null); });
        });
    }

    public void revokeInviteLink(String channelId, String oldCode, Result cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("channels/" + channelId + "/inviteCode", null);
        updates.put("channels/" + channelId + "/inviteLink", null);
        if (oldCode != null) updates.put("channelInviteCodes/" + oldCode, null);
        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) -> {
            if (e == null) executor.execute(() -> dao.setInviteLink(channelId, null, null));
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
        });
    }

    // ── POST TO CHANNEL ───────────────────────────────────────────────────

    public void postToChannel(ChannelPost post, Result cb) {
        String postId = FirebaseUtils.getChannelPostsRef(post.channelId).push().getKey();
        if (postId == null) { mainHandler.post(() -> { if (cb != null) cb.onDone(false); }); return; }
        post.id = postId;
        if (post.timestamp == 0) post.timestamp = System.currentTimeMillis();

        Map<String, Object> updates = new HashMap<>();
        updates.put("channelPosts/" + post.channelId + "/" + postId, postToMap(post));
        // Update channel last-post cache
        updates.put("channels/" + post.channelId + "/lastPostAt",     post.timestamp);
        updates.put("channels/" + post.channelId + "/lastPostText",   post.text != null ? post.text : "");
        updates.put("channels/" + post.channelId + "/lastPostType",   post.type);
        updates.put("channels/" + post.channelId + "/lastPostMediaUrl",
            post.mediaUrl != null ? post.mediaUrl : "");
        updates.put("channels/" + post.channelId + "/totalPosts",     ServerValue.increment(1));

        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) -> {
            if (e == null) {
                executor.execute(() -> {
                    dao.insertPost(postModelToEntity(post));
                    dao.incrementPostCount(post.channelId);
                });
            }
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
        });
    }

    // ── EDIT POST ─────────────────────────────────────────────────────────

    public void editPost(String channelId, String postId, String newText, Result cb) {
        long now = System.currentTimeMillis();
        FirebaseUtils.getChannelPostRef(channelId, postId)
            .child("text").setValue(newText, (e, ref) -> {
                FirebaseUtils.getChannelPostRef(channelId, postId)
                    .child("editedAt").setValue(now);
                if (e == null) executor.execute(() -> dao.updatePostText(postId, newText, now));
                mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
            });
    }

    // ── DELETE POST ───────────────────────────────────────────────────────

    public void deletePost(String channelId, String postId, Result cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("channelPosts/" + channelId + "/" + postId + "/isDeleted", true);
        updates.put("channelPosts/" + channelId + "/" + postId + "/text",      "");
        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) -> {
            if (e == null) executor.execute(() -> dao.softDeletePost(postId));
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
        });
    }

    // ── PIN / UNPIN POST ──────────────────────────────────────────────────

    public void pinPost(String channelId, String postId, Result cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("channels/" + channelId + "/pinnedPostId", postId);
        updates.put("channelPosts/" + channelId + "/" + postId + "/isPinned", true);
        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) -> {
            if (e == null) {
                executor.execute(() -> {
                    dao.clearAllPinned(channelId);
                    dao.setPinned(postId, true);
                    dao.setPinnedPost(channelId, postId);
                });
            }
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
        });
    }

    public void unpinPost(String channelId, String postId, Result cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("channels/" + channelId + "/pinnedPostId", null);
        updates.put("channelPosts/" + channelId + "/" + postId + "/isPinned", false);
        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) -> {
            if (e == null) {
                executor.execute(() -> {
                    dao.setPinned(postId, false);
                    dao.setPinnedPost(channelId, null);
                });
            }
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
        });
    }

    // ── SCHEDULE POST ─────────────────────────────────────────────────────

    public void schedulePost(ChannelPost post, long scheduledAtMs, Result cb) {
        post.scheduledAt = scheduledAtMs;
        String postId = FirebaseUtils.getChannelScheduledRef(post.channelId).push().getKey();
        if (postId == null) { mainHandler.post(() -> { if (cb != null) cb.onDone(false); }); return; }
        post.id = postId;
        FirebaseUtils.getChannelScheduledRef(post.channelId).child(postId)
            .setValue(postToMap(post), (e, ref) -> {
                if (e == null) executor.execute(() -> dao.insertPost(postModelToEntity(post)));
                mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
            });
    }

    public void publishScheduledPost(String channelId, String postId, Result cb) {
        long now = System.currentTimeMillis();
        executor.execute(() -> {
            ChannelPostEntity entity = dao.getPostSync(postId);
            if (entity == null) { mainHandler.post(() -> { if (cb != null) cb.onDone(false); }); return; }
            entity.scheduledAt = 0;
            entity.timestamp   = now;
            dao.insertPost(entity);
            // Move from scheduled to main posts on Firebase
            Map<String, Object> updates = new HashMap<>();
            updates.put("channelScheduled/" + channelId + "/" + postId, null);
            updates.put("channelPosts/" + channelId + "/" + postId + "/scheduledAt", 0);
            updates.put("channelPosts/" + channelId + "/" + postId + "/timestamp", now);
            FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) ->
                mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
        });
    }

    public void deleteScheduledPost(String channelId, String postId, Result cb) {
        FirebaseUtils.getChannelScheduledRef(channelId).child(postId).removeValue((e, ref) -> {
            if (e == null) executor.execute(() -> dao.softDeletePost(postId));
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
        });
    }

    // ── REACT TO POST ─────────────────────────────────────────────────────

    public void reactToPost(String uid, String channelId, String postId, String emoji, Result cb) {
        FirebaseUtils.getChannelPostReactionRef(channelId, postId, uid)
            .setValue(emoji, (e, ref) -> {
                if (e == null) {
                    executor.execute(() -> {
                        ChannelPostEntity entity = dao.getPostSync(postId);
                        if (entity != null) {
                            Map<String, String> map = parseReactionsJson(entity.reactionsJson);
                            map.put(uid, emoji);
                            entity.reactionsJson = reactionsToJson(map);
                            dao.insertPost(entity);
                        }
                    });
                }
                mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
            });
    }

    public void removeReaction(String uid, String channelId, String postId, Result cb) {
        FirebaseUtils.getChannelPostReactionRef(channelId, postId, uid)
            .removeValue((e, ref) -> {
                if (e == null) {
                    executor.execute(() -> {
                        ChannelPostEntity entity = dao.getPostSync(postId);
                        if (entity != null) {
                            Map<String, String> map = parseReactionsJson(entity.reactionsJson);
                            map.remove(uid);
                            entity.reactionsJson = reactionsToJson(map);
                            dao.insertPost(entity);
                        }
                    });
                }
                mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
            });
    }

    // ── POLL VOTING ───────────────────────────────────────────────────────

    public void voteOnPoll(String uid, String channelId, String postId, int optionIndex, Result cb) {
        FirebaseUtils.getChannelPostPollVoteRef(channelId, postId, uid)
            .setValue((long) optionIndex, (e, ref) -> {
                if (e == null) {
                    executor.execute(() -> {
                        ChannelPostEntity entity = dao.getPostSync(postId);
                        if (entity != null) {
                            Map<String, Long> votes = parsePollVotesJson(entity.pollVotesJson);
                            votes.put(uid, (long) optionIndex);
                            entity.pollVotesJson  = pollVotesToJson(votes);
                            entity.pollTotalVotes = votes.size();
                            dao.insertPost(entity);
                        }
                    });
                }
                mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
            });
    }

    // ── VIEW COUNT ────────────────────────────────────────────────────────

    public void incrementPostView(String channelId, String postId) {
        if (!viewedPostIds.add(postId)) return; // already counted in this session
        FirebaseUtils.getChannelPostRef(channelId, postId).child("viewCount")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long v = d.getValue(Long.class);
                    d.setValue(v == null ? 1 : v + 1);
                    return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean committed,
                                                 DataSnapshot s) {
                    if (committed) executor.execute(() -> dao.incrementViewCount(postId));
                }
            });
    }

    public void recordForward(String channelId, String postId) {
        FirebaseUtils.getChannelPostRef(channelId, postId).child("forwardCount")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long v = d.getValue(Long.class);
                    d.setValue(v == null ? 1 : v + 1);
                    return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean committed,
                                                 DataSnapshot s) {
                    if (committed) executor.execute(() -> dao.incrementForwardCount(postId));
                }
            });
    }

    // ── FORWARD POST TO CHAT ──────────────────────────────────────────────

    public void forwardPostToChat(String fromUid, String chatId, boolean isGroup,
                                   ChannelPost post, String channelName, Result cb) {
        DatabaseReference msgRef = isGroup
            ? FirebaseUtils.getGroupMessagesRef(chatId).push()
            : FirebaseUtils.getMessagesRef(chatId).push();
        if (msgRef == null) { mainHandler.post(() -> { if (cb != null) cb.onDone(false); }); return; }

        Map<String, Object> msg = new HashMap<>();
        msg.put("id",          msgRef.getKey());
        msg.put("senderId",    fromUid);
        msg.put("timestamp",   ServerValue.TIMESTAMP);
        msg.put("type",        "channelForward");
        msg.put("text",        post.text != null ? post.text : "");
        msg.put("mediaUrl",    post.mediaUrl != null ? post.mediaUrl : "");
        msg.put("postType",    post.type);
        msg.put("channelName", channelName);
        msg.put("channelId",   post.channelId);
        msg.put("postId",      post.id);

        msgRef.setValue(msg, (e, ref) ->
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
    }

    // ── ADMIN MANAGEMENT ──────────────────────────────────────────────────

    public void addAdmin(String channelId, String targetUid, Result cb) {
        FirebaseUtils.getChannelAdminsRef(channelId).child(targetUid).setValue("admin",
            (e, ref) -> {
                if (e == null) {
                    FirebaseUtils.getChannelRef(channelId).child("adminRoles")
                        .child(targetUid).setValue("admin");
                }
                mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
            });
    }

    public void removeAdmin(String channelId, String targetUid, Result cb) {
        FirebaseUtils.getChannelAdminsRef(channelId).child(targetUid).removeValue((e, ref) -> {
            if (e == null) {
                FirebaseUtils.getChannelRef(channelId).child("adminRoles")
                    .child(targetUid).removeValue();
            }
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
        });
    }

    public void transferOwnership(String channelId, String currentOwnerUid, String newOwnerUid,
                                   Result cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("channels/" + channelId + "/ownerUid",             newOwnerUid);
        updates.put("channelAdmins/" + channelId + "/" + newOwnerUid,  "owner");
        updates.put("channelAdmins/" + channelId + "/" + currentOwnerUid, "admin");
        updates.put("channels/" + channelId + "/adminRoles/" + newOwnerUid,     "owner");
        updates.put("channels/" + channelId + "/adminRoles/" + currentOwnerUid, "admin");
        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) ->
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
    }

    public void loadAdmins(String channelId, MapResult cb) {
        FirebaseUtils.getChannelAdminsRef(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Map<String, String> map = new HashMap<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        String role = ds.getValue(String.class);
                        if (ds.getKey() != null && role != null) map.put(ds.getKey(), role);
                    }
                    mainHandler.post(() -> { if (cb != null) cb.onResult(map); });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    mainHandler.post(() -> { if (cb != null) cb.onResult(new HashMap<>()); });
                }
            });
    }

    // ── FOLLOWERS ─────────────────────────────────────────────────────────

    public void loadChannelFollowers(String channelId, int limit,
                                      ListResult<Map<String, Object>> cb) {
        FirebaseUtils.getChannelFollowersRef(channelId).limitToLast(limit)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("uid", ds.getKey());
                        Object joinedAt = ds.child("joinedAt").getValue();
                        entry.put("joinedAt", joinedAt != null ? joinedAt : 0L);
                        list.add(entry);
                    }
                    mainHandler.post(() -> { if (cb != null) cb.onResult(list); });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    mainHandler.post(() -> { if (cb != null) cb.onResult(new ArrayList<>()); });
                }
            });
    }

    public void blockFollower(String channelId, String targetUid, Result cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("channelBlockedFollowers/" + channelId + "/" + targetUid, true);
        updates.put("channelFollowers/" + channelId + "/" + targetUid, null);
        updates.put("channelFollows/" + targetUid + "/" + channelId, null);
        FirebaseUtils.db().getReference().updateChildren(updates, (e, ref) ->
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
    }

    public void unblockFollower(String channelId, String targetUid, Result cb) {
        FirebaseUtils.getChannelBlockedFollowersRef(channelId).child(targetUid)
            .removeValue((e, ref) ->
                mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
    }

    // ── SEARCH ────────────────────────────────────────────────────────────

    public void searchChannels(String query, ListResult<ChannelEntity> cb) {
        executor.execute(() -> {
            List<ChannelEntity> results = dao.searchChannels("%" + query + "%", 50);
            mainHandler.post(() -> { if (cb != null) cb.onResult(results); });
        });
    }

    public void searchPosts(String channelId, String query, ListResult<ChannelPostEntity> cb) {
        executor.execute(() -> {
            List<ChannelPostEntity> results = dao.searchPosts(channelId, "%" + query + "%");
            mainHandler.post(() -> { if (cb != null) cb.onResult(results); });
        });
    }

    // ── READ TRACKING ─────────────────────────────────────────────────────

    public void markChannelRead(String uid, String channelId, long latestTimestamp) {
        executor.execute(() -> dao.markAllRead(channelId, latestTimestamp));
        FirebaseUtils.getChannelLastSeenRef(uid, channelId).setValue(latestTimestamp);
    }

    // ── REPORT ────────────────────────────────────────────────────────────

    public void reportChannel(String uid, String channelId, String reason, Result cb) {
        DatabaseReference ref = FirebaseUtils.getChannelReportsRef(channelId).push();
        Map<String, Object> report = new HashMap<>();
        report.put("reporterUid", uid);
        report.put("reason",      reason);
        report.put("timestamp",   ServerValue.TIMESTAMP);
        ref.setValue(report, (e, r) ->
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
    }

    public void reportPost(String uid, String channelId, String postId, String reason, Result cb) {
        DatabaseReference ref = FirebaseUtils.getChannelPostReportsRef(channelId, postId).push();
        Map<String, Object> report = new HashMap<>();
        report.put("reporterUid", uid);
        report.put("reason",      reason);
        report.put("timestamp",   ServerValue.TIMESTAMP);
        ref.setValue(report, (e, r) ->
            mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); }));
    }

    // ── INTERACTION FLAGS ─────────────────────────────────────────────────

    public void setAllowReactions(String channelId, String postId, boolean allow, Result cb) {
        FirebaseUtils.getChannelPostRef(channelId, postId).child("allowReactions").setValue(allow,
            (e, ref) -> {
                if (e == null) executor.execute(() -> dao.setAllowReactions(postId, allow));
                mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
            });
    }

    public void setAllowForward(String channelId, String postId, boolean allow, Result cb) {
        FirebaseUtils.getChannelPostRef(channelId, postId).child("allowForward").setValue(allow,
            (e, ref) -> {
                if (e == null) executor.execute(() -> dao.setAllowForward(postId, allow));
                mainHandler.post(() -> { if (cb != null) cb.onDone(e == null); });
            });
    }

    // ── ANALYTICS ─────────────────────────────────────────────────────────

    public void loadChannelAnalytics(String channelId, MapResult cb) {
        FirebaseUtils.getChannelAnalyticsRef(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Map<String, String> data = new HashMap<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        Object val = ds.getValue();
                        if (ds.getKey() != null && val != null) data.put(ds.getKey(), val.toString());
                    }
                    mainHandler.post(() -> { if (cb != null) cb.onResult(data); });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    mainHandler.post(() -> { if (cb != null) cb.onResult(new HashMap<>()); });
                }
            });
    }

    // ── ENTITY ↔ MODEL MAPPING ────────────────────────────────────────────

    private ChannelEntity modelToEntity(Channel ch, boolean isFollowed) {
        ChannelEntity e = new ChannelEntity();
        e.id             = ch.id != null ? ch.id : "";
        e.name           = ch.name;
        e.description    = ch.description;
        e.iconUrl        = ch.iconUrl;
        e.followers      = ch.followers;
        e.verified       = ch.verified;
        e.category       = ch.category;
        e.ownerUid       = ch.ownerUid;
        e.ownerName      = ch.ownerName;
        e.ownerIconUrl   = ch.ownerIconUrl;
        e.createdAt      = ch.createdAt;
        e.isPrivate      = ch.isPrivate;
        e.inviteLink     = ch.inviteLink;
        e.inviteCode     = ch.inviteCode;
        e.totalPosts     = ch.totalPosts;
        e.totalViews     = ch.totalViews;
        e.weeklyGrowth   = ch.weeklyGrowth;
        e.pinnedPostId   = ch.pinnedPostId;
        e.lastPostAt     = ch.lastPostAt;
        e.lastPostText   = ch.lastPostText;
        e.lastPostMediaUrl = ch.lastPostMediaUrl;
        e.lastPostType   = ch.lastPostType;
        e.isFollowed     = isFollowed;
        e.syncedAt       = System.currentTimeMillis();
        return e;
    }

    private ChannelPostEntity postModelToEntity(ChannelPost p) {
        ChannelPostEntity e = new ChannelPostEntity();
        e.id               = p.id != null ? p.id : "";
        e.channelId        = p.channelId;
        e.authorUid        = p.authorUid;
        e.authorName       = p.authorName;
        e.authorIconUrl    = p.authorIconUrl;
        e.text             = p.text;
        e.type             = p.type;
        e.mediaUrl         = p.mediaUrl;
        e.thumbnailUrl     = p.thumbnailUrl;
        e.mediaWidth       = p.mediaWidth;
        e.mediaHeight      = p.mediaHeight;
        e.linkUrl          = p.linkUrl;
        e.linkTitle        = p.linkTitle;
        e.linkDescription  = p.linkDescription;
        e.linkImageUrl     = p.linkImageUrl;
        e.linkDomain       = p.linkDomain;
        e.pollQuestion     = p.pollQuestion;
        e.pollOptionsJson  = pollOptionsToJson(p.pollOptions);
        e.pollVotesJson    = pollVotesToJson(p.pollVotes);
        e.pollTotalVotes   = p.getTotalVotes();
        e.pollMultiSelect  = p.pollMultiSelect;
        e.pollExpiresAt    = p.pollExpiresAt;
        e.audioUrl         = p.audioUrl;
        e.audioDurationMs  = p.audioDurationMs;
        e.audioWaveformJson= p.audioWaveformJson;
        e.documentUrl      = p.documentUrl;
        e.documentName     = p.documentName;
        e.documentSizeBytes= p.documentSizeBytes;
        e.documentMimeType = p.documentMimeType;
        e.isPinned         = p.isPinned;
        e.scheduledAt      = p.scheduledAt;
        e.isDraft          = p.isDraft;
        e.timestamp        = p.timestamp;
        e.editedAt         = p.editedAt;
        e.isDeleted        = p.isDeleted;
        e.viewCount        = p.viewCount;
        e.forwardCount     = p.forwardCount;
        e.replyCount       = p.replyCount;
        e.allowReactions   = p.allowReactions;
        e.allowForward     = p.allowForward;
        e.reactionsJson    = reactionsToJson(p.reactions);
        e.syncedAt         = System.currentTimeMillis();
        return e;
    }

    private Map<String, Object> channelToMap(Channel ch) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",          ch.id);
        m.put("name",        ch.name);
        m.put("description", ch.description != null ? ch.description : "");
        m.put("iconUrl",     ch.iconUrl != null ? ch.iconUrl : "");
        m.put("ownerUid",    ch.ownerUid);
        m.put("ownerName",   ch.ownerName != null ? ch.ownerName : "");
        m.put("ownerIconUrl",ch.ownerIconUrl != null ? ch.ownerIconUrl : "");
        m.put("category",    ch.category != null ? ch.category : "General");
        m.put("isPrivate",   ch.isPrivate);
        m.put("inviteCode",  ch.inviteCode != null ? ch.inviteCode : "");
        m.put("inviteLink",  ch.inviteLink != null ? ch.inviteLink : "");
        m.put("followers",   ch.followers);
        m.put("verified",    ch.verified);
        m.put("createdAt",   ServerValue.TIMESTAMP);
        m.put("totalPosts",  ch.totalPosts);
        m.put("weeklyGrowth",ch.weeklyGrowth);
        return m;
    }

    private Map<String, Object> postToMap(ChannelPost p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",               p.id);
        m.put("channelId",        p.channelId);
        m.put("authorUid",        p.authorUid != null ? p.authorUid : "");
        m.put("authorName",       p.authorName != null ? p.authorName : "");
        m.put("authorIconUrl",    p.authorIconUrl != null ? p.authorIconUrl : "");
        m.put("text",             p.text != null ? p.text : "");
        m.put("type",             p.type != null ? p.type : "text");
        m.put("mediaUrl",         p.mediaUrl != null ? p.mediaUrl : "");
        m.put("thumbnailUrl",     p.thumbnailUrl != null ? p.thumbnailUrl : "");
        m.put("mediaWidth",       p.mediaWidth);
        m.put("mediaHeight",      p.mediaHeight);
        m.put("linkUrl",          p.linkUrl != null ? p.linkUrl : "");
        m.put("linkTitle",        p.linkTitle != null ? p.linkTitle : "");
        m.put("linkDescription",  p.linkDescription != null ? p.linkDescription : "");
        m.put("linkImageUrl",     p.linkImageUrl != null ? p.linkImageUrl : "");
        m.put("linkDomain",       p.linkDomain != null ? p.linkDomain : "");
        m.put("pollQuestion",     p.pollQuestion != null ? p.pollQuestion : "");
        m.put("pollOptions",      p.pollOptions != null ? p.pollOptions : new ArrayList<>());
        m.put("pollMultiSelect",  p.pollMultiSelect);
        m.put("pollExpiresAt",    p.pollExpiresAt);
        m.put("audioUrl",         p.audioUrl != null ? p.audioUrl : "");
        m.put("audioDurationMs",  p.audioDurationMs);
        m.put("audioWaveformJson",p.audioWaveformJson != null ? p.audioWaveformJson : "");
        m.put("documentUrl",      p.documentUrl != null ? p.documentUrl : "");
        m.put("documentName",     p.documentName != null ? p.documentName : "");
        m.put("documentSizeBytes",p.documentSizeBytes);
        m.put("documentMimeType", p.documentMimeType != null ? p.documentMimeType : "");
        m.put("isPinned",         p.isPinned);
        m.put("scheduledAt",      p.scheduledAt);
        m.put("isDraft",          p.isDraft);
        m.put("timestamp",        p.scheduledAt > 0 ? p.timestamp : ServerValue.TIMESTAMP);
        m.put("editedAt",         p.editedAt);
        m.put("isDeleted",        p.isDeleted);
        m.put("viewCount",        p.viewCount);
        m.put("forwardCount",     p.forwardCount);
        m.put("allowReactions",   p.allowReactions);
        m.put("allowForward",     p.allowForward);
        return m;
    }

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

    // ── SERIALIZATION HELPERS ─────────────────────────────────────────────

    private String pollOptionsToJson(List<String> options) {
        if (options == null || options.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < options.size(); i++) {
            sb.append("\"").append(options.get(i).replace("\"", "\\\"")).append("\"");
            if (i < options.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private String pollVotesToJson(Map<String, Long> votes) {
        if (votes == null || votes.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> e : votes.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private Map<String, Long> parsePollVotesJson(String json) {
        Map<String, Long> map = new HashMap<>();
        try {
            if (json == null || json.trim().isEmpty() || "{}".equals(json.trim())) return map;
            String s = json.trim().replaceAll("[{}]", "");
            for (String entry : s.split(",")) {
                int colon = entry.lastIndexOf(":");
                if (colon > 0) {
                    String k = entry.substring(0, colon).replaceAll("\"", "").trim();
                    String v = entry.substring(colon + 1).trim();
                    try { map.put(k, Long.parseLong(v)); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private String reactionsToJson(Map<String, String> reactions) {
        if (reactions == null || reactions.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : reactions.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private Map<String, String> parseReactionsJson(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            if (json == null || json.trim().isEmpty() || "{}".equals(json.trim())) return map;
            String s = json.trim();
            if (s.startsWith("{")) s = s.substring(1);
            if (s.endsWith("}"))   s = s.substring(0, s.length() - 1);
            for (String entry : s.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                int colon = entry.lastIndexOf(":");
                if (colon > 0) {
                    String k = entry.substring(0, colon).replaceAll("\"", "").trim();
                    String v = entry.substring(colon + 1).replaceAll("\"", "").trim();
                    if (!k.isEmpty()) map.put(k, v);
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private String generateInviteCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(10);
        java.util.Random rng = new java.util.Random();
        for (int i = 0; i < 10; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }
    // ═══════════════════════════════════════════════════════════════════════
    // ── REPLY SYSTEM (v3) ─────────────────────────────────────────────────

    public interface ReplyResult { void onDone(boolean success); }

    public void addReply(String uid, String name, String iconUrl,
                         String channelId, String postId, String text, ReplyResult cb) {
        DatabaseReference ref = FirebaseUtils.db().getReference()
                .child("channelPostReplies").child(channelId).child(postId).push();
        if (ref == null) { mainHandler.post(() -> { if (cb != null) cb.onDone(false); }); return; }
        Map<String, Object> reply = new HashMap<>();
        reply.put("id", ref.getKey());
        reply.put("authorUid", uid != null ? uid : "");
        reply.put("authorName", name != null ? name : "Unknown");
        reply.put("authorIconUrl", iconUrl != null ? iconUrl : "");
        reply.put("text", text);
        reply.put("timestamp", ServerValue.TIMESTAMP);
        ref.setValue(reply, (err, r) -> {
            if (err == null) {
                FirebaseUtils.db().getReference()
                    .child("channelPosts").child(channelId).child(postId).child("replyCount")
                    .runTransaction(new Transaction.Handler() {
                        @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                            Long v = d.getValue(Long.class); d.setValue(v == null ? 1 : v + 1); return Transaction.success(d);
                        }
                        @Override public void onComplete(DatabaseError e, boolean ok, DataSnapshot s) {
                            if (ok) executor.execute(() -> dao.incrementReplyCount(postId));
                        }
                    });
            }
            mainHandler.post(() -> { if (cb != null) cb.onDone(err == null); });
        });
    }

    public void reactToReply(String uid, String channelId, String postId,
                              String replyId, String emoji, ReplyResult cb) {
        FirebaseUtils.db().getReference()
            .child("channelPostReplies").child(channelId).child(postId)
            .child(replyId).child("reactions").child(uid)
            .setValue(emoji, (err, r) -> mainHandler.post(() -> { if (cb != null) cb.onDone(err == null); }));
    }

    // ── JOIN BY INVITE CODE (v3) ──────────────────────────────────────────

    public interface JoinChannelResult {
        void onSuccess(String channelId, String channelName);
        void onChannelNotFound();
        void onAlreadyFollowing();
        void onFailed();
    }

    public void joinChannelByInviteCode(String uid, String inviteCode, JoinChannelResult cb) {
        FirebaseUtils.db().getReference().child("channelInviteCodes").child(inviteCode)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String channelId = snap.getValue(String.class);
                    if (channelId == null || channelId.isEmpty()) { mainHandler.post(cb::onChannelNotFound); return; }
                    executor.execute(() -> {
                        boolean already = dao.isFollowed(channelId);
                        if (already) { mainHandler.post(cb::onAlreadyFollowing); return; }
                        followChannel(uid, channelId, ok -> {
                            if (ok) {
                                executor.execute(() -> {
                                    ChannelEntity ch = dao.getChannelSync(channelId);
                                    String cname = ch != null ? ch.name : channelId;
                                    mainHandler.post(() -> cb.onSuccess(channelId, cname));
                                });
                            } else { mainHandler.post(cb::onFailed); }
                        });
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { mainHandler.post(cb::onFailed); }
            });
    }

    // ── ANALYTICS PUSH (v3) ──────────────────────────────────────────────

    public void pushAnalyticsEvent(String channelId, String eventType, long value) {
        String dateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        FirebaseUtils.getChannelAnalyticsRef(channelId).child(eventType).child(dateKey)
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long v = d.getValue(Long.class); d.setValue(v == null ? value : v + value); return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean c, DataSnapshot s) {}
            });
    }



    // ════════════════════════════════════════════════════════════════════════
    // WELCOME MESSAGE / AUTO-REPLY  (v5)
    // ════════════════════════════════════════════════════════════════════════

    public void getWelcomeMessage(String channelId, Callback<String> cb) {
        db.getReference("channelSettings").child(channelId).child("welcomeMessage")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    Object v = s.getValue(); mainHandler.post(() -> cb.onResult(v != null ? v.toString() : null));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { mainHandler.post(() -> cb.onResult(null)); }
            });
    }

    public void setWelcomeMessage(String channelId, String message, BooleanCallback cb) {
        DatabaseReference ref = db.getReference("channelSettings").child(channelId).child("welcomeMessage");
        if (message == null || message.isEmpty()) {
            ref.removeValue().addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
               .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
        } else {
            ref.setValue(message).addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
               .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
        }
    }

    /** Send a welcome DM from the channel to a new follower's chat inbox. */
    public void sendWelcomeDm(String channelId, String toUid, String message, BooleanCallback cb) {
        // Look up channel name + icon to set as DM sender
        db.getReference("channels").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    Object nameObj = s.child("name").getValue();
                    Object iconObj = s.child("iconUrl").getValue();
                    String chName = nameObj != null ? nameObj.toString() : "Channel";
                    String chIcon = iconObj != null ? iconObj.toString() : "";

                    // Write a synthetic DM into the recipient's inbox
                    String dmId = db.getReference("messages").child(toUid).push().getKey();
                    if (dmId == null) { if (cb != null) mainHandler.post(() -> cb.onResult(false)); return; }

                    java.util.Map<String, Object> dm = new java.util.LinkedHashMap<>();
                    dm.put("senderUid",   "channel_" + channelId);
                    dm.put("senderName",  chName);
                    dm.put("senderIcon",  chIcon);
                    dm.put("text",        message);
                    dm.put("timestamp",   ServerValue.TIMESTAMP);
                    dm.put("type",        "channel_welcome");
                    dm.put("channelId",   channelId);

                    db.getReference("messages").child(toUid).child(dmId).setValue(dm)
                      .addOnSuccessListener(v -> { if (cb != null) mainHandler.post(() -> cb.onResult(true)); })
                      .addOnFailureListener(e -> { if (cb != null) mainHandler.post(() -> cb.onResult(false)); });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (cb != null) mainHandler.post(() -> cb.onResult(false));
                }
            });
    }

    // ════════════════════════════════════════════════════════════════════════
    // BOOKMARK SAVE / REMOVE  (v5 — Firebase cross-device sync)
    // ════════════════════════════════════════════════════════════════════════

    public void saveBookmark(String myUid, String channelId, String postId, BooleanCallback cb) {
        String key = channelId + "_" + postId;
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("channelId", channelId);
        data.put("postId",    postId);
        data.put("savedAt",   ServerValue.TIMESTAMP);
        db.getReference("channelBookmarks").child(myUid).child(key).setValue(data)
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    public void removeBookmark(String myUid, String channelId, String postId, BooleanCallback cb) {
        String key = channelId + "_" + postId;
        db.getReference("channelBookmarks").child(myUid).child(key).removeValue()
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // FCM BROADCAST PUSH  (v5 — sends Firebase topic message)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * sendBroadcastPush — triggers a Firebase Cloud Messaging push to all followers
     * of the channel via the Firebase topic "channel_{channelId}".
     *
     * The actual FCM fan-out is done by a Cloud Function that listens to
     * channelBroadcastTriggers/{channelId}/{triggerKey}. We write a trigger
     * record here; the Cloud Function reads it and sends the topic push.
     * This avoids embedding FCM server keys in the Android client.
     */
    public void sendBroadcastPush(String channelId, String text, String priority, BooleanCallback cb) {
        String triggerKey = db.getReference("channelBroadcastTriggers")
            .child(channelId).push().getKey();
        if (triggerKey == null) { mainHandler.post(() -> cb.onResult(false)); return; }

        java.util.Map<String, Object> trigger = new java.util.LinkedHashMap<>();
        trigger.put("text",      text.length() > 200 ? text.substring(0, 200) : text);
        trigger.put("priority",  priority != null ? priority : "normal");
        trigger.put("timestamp", ServerValue.TIMESTAMP);
        trigger.put("channelId", channelId);

        db.getReference("channelBroadcastTriggers").child(channelId).child(triggerKey)
          .setValue(trigger)
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // ADMIN PERMISSION LEVELS  (v5)
    // ════════════════════════════════════════════════════════════════════════

    public void setAdminPermissions(String channelId, String adminUid,
                                     boolean canPost, boolean canEdit, boolean canManage,
                                     BooleanCallback cb) {
        java.util.Map<String, Object> perms = new java.util.LinkedHashMap<>();
        perms.put("canPost",   canPost);
        perms.put("canEdit",   canEdit);
        perms.put("canManage", canManage);
        db.getReference("channelAdminPerms").child(channelId).child(adminUid).setValue(perms)
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // TOPIC TAGS  (v5)
    // ════════════════════════════════════════════════════════════════════════

    public void setChannelTopicTags(String channelId, java.util.List<String> tags, BooleanCallback cb) {
        db.getReference("channels").child(channelId).child("topicTags").setValue(tags)
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    public void setPostTopicTags(String channelId, String postId,
                                  java.util.List<String> tags, BooleanCallback cb) {
        db.getReference("channelPosts").child(channelId).child(postId).child("topicTags").setValue(tags)
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // MILESTONE TRACKING  (v5)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * checkAndMarkMilestone — checks if a milestone has already been celebrated;
     * if not, marks it and invokes cb(true). Otherwise cb(false).
     */
    public void checkAndMarkMilestone(String channelId, long milestone, BooleanCallback cb) {
        String key = String.valueOf(milestone);
        DatabaseReference ref = db.getReference("channelMilestones").child(channelId).child(key);
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (s.exists()) {
                    mainHandler.post(() -> cb.onResult(false)); // already celebrated
                } else {
                    ref.setValue(ServerValue.TIMESTAMP)
                       .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
                       .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                mainHandler.post(() -> cb.onResult(false));
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    // SHARE POST TO STATUS  (v5)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * shareChannelPostToStatus — copies a channel post into the user's own Status feed
     * (statusUpdates/{myUid}) so it shows as a story for their contacts.
     */
    public void shareChannelPostToStatus(String myUid, String myName, String myIconUrl,
                                          com.callx.app.models.ChannelPost post, BooleanCallback cb) {
        String statusId = db.getReference("statusUpdates").child(myUid).push().getKey();
        if (statusId == null) { mainHandler.post(() -> cb.onResult(false)); return; }

        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("statusId",       statusId);
        status.put("ownerUid",       myUid);
        status.put("ownerName",      myName != null ? myName : "");
        status.put("ownerIconUrl",   myIconUrl != null ? myIconUrl : "");
        status.put("timestamp",      ServerValue.TIMESTAMP);
        status.put("expiresAt",      System.currentTimeMillis() + 24L * 3600 * 1000);
        status.put("type",           "channel_share");
        status.put("channelId",      post.channelId);
        status.put("postId",         post.id);
        status.put("postType",       post.type != null ? post.type : "text");
        if (post.text       != null) status.put("text",      post.text);
        if (post.mediaUrl   != null) status.put("mediaUrl",  post.mediaUrl);
        if (post.thumbnailUrl != null) status.put("thumbUrl", post.thumbnailUrl);

        db.getReference("statusUpdates").child(myUid).child(statusId).setValue(status)
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // RSVP EVENT  (v5)
    // ════════════════════════════════════════════════════════════════════════

    public void rsvpEvent(String myUid, String channelId, String postId,
                           String status, BooleanCallback cb) {
        db.getReference("channelEventRsvp").child(channelId).child(postId).child(myUid)
          .setValue(status)
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // BLOCK / UNBLOCK FOLLOWER  (v5)
    // ════════════════════════════════════════════════════════════════════════

    public void blockFollower(String channelId, String followerUid, BooleanCallback cb) {
        java.util.Map<String, Object> u = new java.util.LinkedHashMap<>();
        u.put("blocked", true); u.put("blockedAt", ServerValue.TIMESTAMP);
        db.getReference("channelFollowers").child(channelId).child(followerUid).updateChildren(u)
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    public void unblockFollower(String channelId, String followerUid, BooleanCallback cb) {
        db.getReference("channelFollowers").child(channelId).child(followerUid).child("blocked")
          .removeValue()
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // FORWARD POST TO CHAT  (v5)
    // ════════════════════════════════════════════════════════════════════════

    public void forwardPostToChat(String myUid, String myName, String myIconUrl,
                                   String targetChatId, String targetType,
                                   com.callx.app.models.ChannelPost post,
                                   String note, BooleanCallback cb) {
        String node = "group".equals(targetType) ? "groupMessages" : "messages";
        String msgId = db.getReference(node).child(targetChatId).push().getKey();
        if (msgId == null) { mainHandler.post(() -> cb.onResult(false)); return; }

        java.util.Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("messageId",    msgId);
        msg.put("senderUid",    myUid != null ? myUid : "");
        msg.put("senderName",   myName != null ? myName : "");
        msg.put("senderIcon",   myIconUrl != null ? myIconUrl : "");
        msg.put("type",         "channel_forward");
        msg.put("timestamp",    ServerValue.TIMESTAMP);
        msg.put("channelId",    post.channelId);
        msg.put("postId",       post.id);
        msg.put("postType",     post.type != null ? post.type : "text");
        if (post.text    != null && !post.text.isEmpty()) msg.put("postText",  post.text);
        if (post.mediaUrl!= null)                         msg.put("mediaUrl",  post.mediaUrl);
        if (note         != null && !note.isEmpty())      msg.put("forwardNote", note);

        db.getReference(node).child(targetChatId).child(msgId).setValue(msg)
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // SCHEDULE POST  (v5 — WorkManager bridge)
    // ════════════════════════════════════════════════════════════════════════

    public void schedulePost(com.callx.app.models.ChannelPost post, long scheduledAtMs, BooleanCallback cb) {
        // Mark post as scheduled in Firebase; ChannelScheduledPostWorker picks it up
        post.scheduledAt = scheduledAtMs;
        post.isDraft     = false;
        postToChannel(post, cb);
    }

    // ════════════════════════════════════════════════════════════════════════
    // REPORT CHANNEL  (v5)
    // ════════════════════════════════════════════════════════════════════════

    public void reportChannel(String myUid, String channelId, String reason, BooleanCallback cb) {
        String key = db.getReference("reports").push().getKey();
        if (key == null) { mainHandler.post(() -> cb.onResult(false)); return; }
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("type", "channel"); r.put("channelId", channelId); r.put("reporterUid", myUid);
        r.put("reason", reason); r.put("timestamp", ServerValue.TIMESTAMP);
        db.getReference("reports").child(key).setValue(r)
          .addOnSuccessListener(v -> mainHandler.post(() -> cb.onResult(true)))
          .addOnFailureListener(e -> mainHandler.post(() -> cb.onResult(false)));
    }

}
