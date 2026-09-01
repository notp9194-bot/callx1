package com.callx.app.repository;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.callx.app.cache.CacheManager;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.db.paging.MessageCursor;
import com.callx.app.db.entity.UserEntity;
import com.callx.app.models.Message;
import com.callx.app.models.User;
import com.callx.app.utils.Constants;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * ChatRepository — Offline-First + Predictive + Delta Sync.
 *
 * Strategy:
 *   1. Serve from local cache immediately (zero latency for UI)
 *   2. Fetch delta from Firebase (only new messages since last sync)
 *   3. Merge & save — LiveData auto-updates the UI
 */
public class ChatRepository {

    private static final String TAG      = "ChatRepository";
    private static final int    PAGE_SIZE = 50;

    private static ChatRepository sInstance;

    private final CacheManager   mCache;
    private final AppDatabase    mDb;
    private final ExecutorService mExecutor;
    private final FirebaseDatabase mFirebase;
    private final Context mAppContext;
    // One network delta request per chat at a time, regardless of whether it
    // was requested by Room observation, preload, network recovery, or
    // WorkManager. The realtime chat listener remains the live-update path.
    private final ConcurrentHashMap<String, Boolean> mSyncInFlight = new ConcurrentHashMap<>();

    private ChatRepository(Context ctx) {
        mCache      = CacheManager.getInstance(ctx);
        mDb         = AppDatabase.getInstance(ctx);
        mExecutor   = Executors.newFixedThreadPool(4);
        mFirebase   = FirebaseDatabase.getInstance(Constants.DB_URL);
        mAppContext = ctx.getApplicationContext();
    }

    public static synchronized ChatRepository getInstance(Context ctx) {
        if (sInstance == null) sInstance = new ChatRepository(ctx.getApplicationContext());
        return sInstance;
    }

    // ─────────────────────────────────────────────────────────────
    // MESSAGES — offline-first LiveData
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns LiveData backed by Room DB (instant cached UI).
     * Then triggers a background delta sync from Firebase.
     */
    public LiveData<List<MessageEntity>> getMessages(String chatId) {
        // Trigger background delta sync silently
        syncMessagesDelta(chatId);
        // Return Room LiveData — UI auto-updates when DB changes
        return mDb.messageDao().getMessages(chatId);
    }

    /**
     * Delta sync: only fetch messages newer than the last cached timestamp.
     * Reduces Firebase reads by 90%+ on repeat opens.
     */
    /**
     * PERF FIX: warms the in-memory LastMessagesCache straight from Room —
     * pure local disk read, no network wait — so that by the time the user
     * actually taps this chat, ChatActivity.onCreate()'s warmCacheHit fast
     * path has real data ready and renders on the very first frame.
     *
     * This closes the gap where preload only wrote into Room (via
     * syncMessagesDelta) but never touched LastMessagesCache, so the
     * "instant render" path only ever fired on a chat's 2nd+ open within
     * the same ChatActivity lifecycle — never on the very first tap, which
     * is what the user actually experiences as "1 sec delay every time."
     *
     * Safe to call as often as needed — cheap indexed query, background
     * thread only, never touches the UI thread.
     */
    public void warmLastMessagesCache(String chatId) {
        primeChatFromRoom(chatId, null);
    }

    /**
     * Same Room read as warmLastMessagesCache(), but with a completion
     * callback delivered on the main thread — used by chat-list tap
     * handlers to navigate into ChatActivity only once local data is
     * actually ready (WhatsApp-style: local disk read completes, THEN
     * the screen opens with content already in it), instead of opening a
     * blank screen and racing Paging/Firebase to fill it in afterward.
     *
     * `callback` fires exactly once, always on the main thread:
     *   - as soon as the Room read completes (typically a few ms — it's an
     *     indexed, LIMIT-20 query against local disk, no network involved), or
     *   - immediately if this chat's cache is already warm (skips the read).
     *
     * There is deliberately no artificial delay here (no Thread.sleep, no
     * postDelayed) — the callback fires the moment real data is available,
     * never before and never "padded" to feel slower. Callers should still
     * apply their own short safety cap (see ChatListAdapter.openChat) in
     * case a device's disk I/O is unusually slow, so a tap never feels stuck.
     */
    public void primeChatFromRoom(String chatId, @androidx.annotation.Nullable Runnable callback) {
        if (chatId == null) {
            if (callback != null) callback.run();
            return;
        }
        if (com.callx.app.cache.LastMessagesCache.getInstance().has(chatId)) {
            // Already warm from a previous open this session — nothing to wait for.
            if (callback != null) callback.run();
            return;
        }
        mExecutor.execute(() -> {
            java.util.List<MessageEntity> entities = mDb.messageDao().getLastMessagesAsc(chatId, 20);
            java.util.List<Message> models = new ArrayList<>(entities.size());
            for (MessageEntity e : entities) {
                Message m = com.callx.app.utils.MessageEntityMapper.toModel(e);
                if (m != null) models.add(m);
            }
            if (!models.isEmpty()) {
                com.callx.app.cache.LastMessagesCache.getInstance().seed(chatId, models);
            }
            if (callback != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(callback);
            }
        });
    }

    public void syncMessagesDelta(String chatId) {
        if (chatId == null || chatId.isEmpty()) return;
        if (mSyncInFlight.putIfAbsent(chatId, Boolean.TRUE) != null) {
            Log.d(TAG, "Delta sync already in flight — coalescing chatId=" + chatId);
            return;
        }
        mExecutor.execute(() -> {
            // TraceSectionMetric("ChatRepo#syncDelta") — full Firebase delta sync
            // wall time per chat open (background thread). Measures network +
            // Room insert. If > 2s consistently → consider WebSocket or push-triggered
            // sync instead of open-triggered pull.
            MessageCursor cursor = mCache.getLastSyncCursor(chatId);
            Log.d(TAG, "Delta sync chatId=" + chatId + " since=" + cursor);

            // FIX: startAfter(null, "timestamp") is invalid Firebase syntax when lastTs==0.
            // When no prior sync: use limitToLast to get the most recent PAGE_SIZE messages.
            // When delta sync: use startAfter((double)lastTs) which is the correct overload.
            Query query;
            // PERF FIX v8: Firebase path was WRONG ("chats/{id}/messages").
            // Correct path matches ChatActivity: "messages/{chatId}"
            // Old wrong path = Room always empty = 3-4s load on every open.
            // TraceSectionMetric("ChatRepo#syncDelta") — synchronous query-build cost
            // on the executor thread (Trace sections are thread-local; we only wrap
            // the synchronous portion here; the async Firebase round-trip is tracked
            // separately via DB#insertMessages in the onDataChange callback).
            android.os.Trace.beginSection("ChatRepo#syncDelta");
            try {
                if (cursor == null) {
                    query = mFirebase.getReference("messages")
                        .child(chatId)
                        .orderByChild("timestamp")
                        .limitToLast(PAGE_SIZE);
                } else if (cursor.hasSeq()) {
                    // GAP FIX (#1 — true server cursor): once this chat has a
                    // server-assigned seq to anchor on, prefer it outright —
                    // startAt(seq) on the "seq" index is a genuine ack-style
                    // cursor (see Message#seq), not a client-derived keyset
                    // approximation. inclusive startAt is fine here the same
                    // way it is for the timestamp path below: the isAfter()
                    // check in onDataChange still filters out the boundary
                    // row itself using seq comparison.
                    query = mFirebase.getReference("messages")
                        .child(chatId)
                        .orderByChild("seq")
                        .startAt((double) cursor.seq)
                        .limitToFirst(PAGE_SIZE);
                } else {
                    query = mFirebase.getReference("messages")
                        .child(chatId)
                        .orderByChild("timestamp")
                        // Inclusive start + client-side id filtering is the
                        // RTDB equivalent of a (timestamp, id) keyset cursor.
                        // limitToFirst catches up oldest-first instead of
                        // jumping over a burst larger than PAGE_SIZE.
                        .startAt((double) cursor.timestamp)
                        .limitToFirst(PAGE_SIZE);
                }
            } finally {
                android.os.Trace.endSection();
            }

            final boolean seqAnchored = cursor != null && cursor.hasSeq();

            query.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    List<MessageEntity> newMessages = new ArrayList<>();
                    long maxTimestamp = Long.MIN_VALUE;
                    String maxId = null;
                    Long maxSeq = null;
                    for (DataSnapshot child : snapshot.getChildren()) {
                        Message m = child.getValue(Message.class);
                        if (m == null) continue;
                        if (m.id == null) m.id = child.getKey();
                        if (m.id == null || m.timestamp == null) continue;

                        boolean isNew = seqAnchored
                                ? isAfterSeq(m, cursor)
                                : (cursor == null || isAfter(m.timestamp, m.id, cursor));
                        if (isNew) {
                            newMessages.add(toEntity(m, chatId));
                        }
                        // Track the newest row in this batch by whichever
                        // ordering this query actually ran under, so the
                        // cursor we advance to matches the query's own
                        // ordering instead of silently mixing the two.
                        if (seqAnchored) {
                            if (m.seq != null && (maxSeq == null || m.seq > maxSeq)) {
                                maxSeq = m.seq;
                                maxTimestamp = m.timestamp;
                                maxId = m.id;
                            }
                        } else if (maxId == null || isAfter(m.timestamp, m.id,
                                new MessageCursor(maxTimestamp, maxId))) {
                            maxTimestamp = m.timestamp;
                            maxId = m.id;
                            maxSeq = m.seq;
                        }
                    }
                    final long finalMaxTimestamp = maxTimestamp;
                    final String finalMaxId = maxId;
                    final Long finalMaxSeq = maxSeq;
                    mExecutor.execute(() -> {
                        try {
                            // TraceSectionMetric("DB#insertMessages") — Room bulk insert
                            // cost per delta sync batch. Target: < 50ms for PAGE_SIZE=50
                            // rows. If > 100ms, chatId+timestamp index likely missing —
                            // verify MIGRATION_17_18 ran on this device.
                            android.os.Trace.beginSection("DB#insertMessages");
                            try {
                            if (!newMessages.isEmpty()) {
                                mDb.messageDao().insertMessages(newMessages);
                            }
                            if (finalMaxId != null) {
                                mCache.advanceSyncCursor(chatId, finalMaxTimestamp, finalMaxId, finalMaxSeq);
                            }
                            } finally {
                                android.os.Trace.endSection();
                            }
                            mCache.invalidateMessages(chatId);
                            Log.d(TAG, "Delta sync: inserted " + newMessages.size()
                                    + " new messages for " + chatId);
                        } finally {
                            mSyncInFlight.remove(chatId);
                        }
                    });
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    Log.w(TAG, "Delta sync cancelled: " + error.getMessage());
                    mSyncInFlight.remove(chatId);
                }
            });
        });
    }

    private static boolean isAfterSeq(Message m, MessageCursor cursor) {
        return m.seq != null && cursor.seq != null && m.seq > cursor.seq;
    }

    private static boolean isAfter(Long timestamp, String id, MessageCursor cursor) {
        if (timestamp == null || id == null || cursor == null) return false;
        return timestamp > cursor.timestamp
                || (timestamp == cursor.timestamp && id.compareTo(cursor.id) > 0);
    }

    // ─────────────────────────────────────────────────────────────
    // REMOTE MEDIATOR SUPPORT — on-demand older-history fetch
    // ─────────────────────────────────────────────────────────────

    /**
     * Fetches one page of messages OLDER than `beforeTimestamp` directly from
     * Firebase and inserts them into Room. Used by {@link com.callx.app.db.paging.MessageRemoteMediator}
     * on PREPEND, when the local Room table has run out of older messages for
     * this chat and infinite-scroll needs to reach further back than the
     * initial delta sync ever fetched (delta sync only ever pulls the most
     * recent PAGE_SIZE messages — see syncMessagesDelta above).
     *
     * Returns the number of NEW rows actually inserted — the caller uses
     * `inserted < pageSize` to decide endOfPaginationReached (fewer rows than
     * requested means Firebase has no more history above this point).
     */
    /**
     * TELEGRAM-LEVEL FIX: bootstrap fetch for when {@link MessageRemoteMediator}'s
     * PREPEND runs with NOTHING loaded in Room yet for this chat — the exact
     * situation right after an uninstall+reinstall+relogin (Room is a brand
     * new empty database) combined with Paging3's own automatic prefetch,
     * which can fire a PREPEND probe before {@link #syncMessagesDelta}'s
     * async Firebase call has had a chance to land.
     *
     * Previously that "nothing loaded yet" case was read as "no older
     * history exists on the server" and permanently marked
     * endOfPaginationReached=true for the rest of that chat screen's
     * session — so scrolling up after a fresh install silently showed
     * nothing beyond whatever first page happened to arrive, exactly like
     * the reported bug. This fetches the newest window directly from
     * Firebase (same query syncMessagesDelta uses for a first-ever sync)
     * so PREPEND always gets a real, current answer instead of guessing
     * "empty" from a Room table that just hasn't caught up yet.
     */
    public io.reactivex.rxjava3.core.Single<Integer> fetchInitialMessagesFromFirebase(
            String chatId, int pageSize) {
        return io.reactivex.rxjava3.core.Single.<Integer>create(emitter -> {
            Query query = mFirebase.getReference("messages")
                    .child(chatId)
                    .orderByChild("timestamp")
                    .limitToLast(pageSize);

            query.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    List<MessageEntity> initial = new ArrayList<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        Message m = child.getValue(Message.class);
                        if (m == null) continue;
                        if (m.id == null) m.id = child.getKey();
                        initial.add(toEntity(m, chatId));
                    }
                    mExecutor.execute(() -> {
                        if (!initial.isEmpty()) {
                            mDb.messageDao().insertMessages(initial);
                            MessageEntity newest = initial.get(initial.size() - 1);
                            if (newest.timestamp != null && newest.id != null) {
                                mCache.advanceSyncCursor(
                                        chatId, newest.timestamp, newest.id, newest.seq);
                            }
                            mCache.invalidateMessages(chatId);
                        }
                        if (!emitter.isDisposed()) emitter.onSuccess(initial.size());
                    });
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    if (!emitter.isDisposed()) emitter.onError(error.toException());
                }
            });
        }).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io());
    }

    public io.reactivex.rxjava3.core.Single<Integer> fetchOlderMessagesFromFirebase(
            String chatId, MessageCursor beforeCursor, int pageSize) {
        return io.reactivex.rxjava3.core.Single.<Integer>create(emitter -> {
            Query query = mFirebase.getReference("messages")
                    .child(chatId)
                    .orderByChild("timestamp")
                    // Inclusive timestamp + client-side id filtering gives
                    // Firebase the same compound boundary as Room paging.
                    .endAt((double) beforeCursor.timestamp)
                    .limitToLast(pageSize);

            query.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    List<MessageEntity> older = new ArrayList<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        Message m = child.getValue(Message.class);
                        if (m == null) continue;
                        if (m.id == null) m.id = child.getKey();
                        if (m.id == null || m.timestamp == null) continue;
                        if (isBefore(m.timestamp, m.id, beforeCursor)) {
                            older.add(toEntity(m, chatId));
                        }
                    }
                    mExecutor.execute(() -> {
                        if (!older.isEmpty()) {
                            mDb.messageDao().insertMessages(older);
                        }
                        if (!emitter.isDisposed()) emitter.onSuccess(older.size());
                    });
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    if (!emitter.isDisposed()) emitter.onError(error.toException());
                }
            });
        }).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io());
    }

    private static boolean isBefore(Long timestamp, String id, MessageCursor cursor) {
        if (timestamp == null || id == null || cursor == null) return false;
        return timestamp < cursor.timestamp
                || (timestamp == cursor.timestamp && id.compareTo(cursor.id) < 0);
    }

    // ─────────────────────────────────────────────────────────────
    // USER PROFILE — offline-first
    // ─────────────────────────────────────────────────────────────

    public LiveData<UserEntity> getUserProfile(String uid) {
        // Trigger background refresh
        refreshUserProfile(uid);
        return mDb.userDao().getUserLive(uid);
    }

    private void refreshUserProfile(String uid) {
        mExecutor.execute(() -> {
            mFirebase.getReference("users").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        User u = snapshot.getValue(User.class);
                        if (u == null) return;
                        if (u.uid == null) u.uid = snapshot.getKey();
                        UserEntity entity = userToEntity(u);
                        mExecutor.execute(() -> mCache.saveUser(entity));
                    }
                    @Override
                    public void onCancelled(DatabaseError error) {}
                });
        });
    }

    // ─────────────────────────────────────────────────────────────
    // PREDICTIVE PRELOADING
    // ─────────────────────────────────────────────────────────────

    /**
     * When user opens Chat A, preload Chat B + C (most recent chats) in background.
     * Called from ChatActivity.
     */
    public void preloadRecentChats(String currentChatId) {
        mExecutor.execute(() -> {
            List<String> topChats = mCache.getAnalytics().getTopChats(5);
            for (String chatId : topChats) {
                if (!chatId.equals(currentChatId)) {
                    syncMessagesDelta(chatId);
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // CLEANUP — prune old DB records
    // ─────────────────────────────────────────────────────────────

    public void pruneOldMessages(String chatId, int keepCount) {
        mExecutor.execute(() -> mDb.messageDao().pruneOldMessages(chatId, keepCount));
    }

    /**
     * GAP FIX (#3): the storage-pressure-gated entry point pruning call
     * sites should use instead of {@link #pruneOldMessages} directly.
     * Skips the delete entirely — no DB query at all, not even a read —
     * unless {@link DeviceStorageUtils#isDeviceStorageLow} says the device
     * genuinely needs the space back right now. `keepCount` here is
     * intentionally much higher than the old per-open (500) / periodic
     * (200) values: this only fires under real pressure, so when it does
     * fire it should still leave a generous amount of recent history
     * intact rather than clawing all the way down to a small number.
     */
    public void pruneOldMessagesIfLowStorage(Context ctx, String chatId, int keepCountWhenLow) {
        if (!com.callx.app.utils.DeviceStorageUtils.isDeviceStorageLow(ctx)) return;
        pruneOldMessages(chatId, keepCountWhenLow);
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS — model ↔ entity conversion
    // ─────────────────────────────────────────────────────────────

    /**
     * E2EE: decrypts m.text in place if it's a ratchet envelope (1:1 chat
     * text only). This repository is a SECOND, independent path (besides
     * ChatActivity's live ChildEventListener) that pulls messages straight
     * from Firebase into Room — used for the initial delta sync on chat
     * open and for older-history pagination (MessageRemoteMediator). Both
     * paths must decrypt before touching Room, or whichever one writes
     * last wins the race and can silently overwrite an already-decrypted
     * row with raw ciphertext.
     *
     * GAP FIX (#6): this used to be its own hand-written copy of the same
     * logic ChatActivity#decryptIncomingIfNeeded implements — two copies of
     * a subtle rule (own-message-echo vs. real decrypt) that could drift
     * apart. Both now delegate to the single canonical implementation in
     * {@link com.callx.app.sync.MessageDecryptor}, so there's exactly one
     * place this rule lives.
     */
    private void decryptIfNeeded(Message m) {
        com.callx.app.sync.MessageDecryptor.decryptIfNeeded(
                mAppContext, m, com.callx.app.utils.FirebaseUtils.getCurrentUid());
    }

    private MessageEntity toEntity(Message m, String chatId) {
        decryptIfNeeded(m);
        return com.callx.app.utils.MessageEntityMapper.fromModel(m, chatId);
    }

    private UserEntity userToEntity(User u) {
        UserEntity e = new UserEntity();
        e.uid           = u.uid != null ? u.uid : "";
        e.email         = u.email;
        e.name          = u.name;
        e.emoji         = u.emoji;
        e.callxId       = u.callxId;
        e.about         = u.about;
        e.photoUrl      = u.photoUrl;
        e.fcmToken      = u.fcmToken;
        e.lastSeen      = u.lastSeen;
        e.lastMessage   = u.lastMessage;
        e.lastMessageAt = u.lastMessageAt;
        e.unread        = u.unread;
        e.cachedAt      = System.currentTimeMillis();
        return e;
    }
}
