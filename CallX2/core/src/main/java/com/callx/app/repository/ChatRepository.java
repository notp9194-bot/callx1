package com.callx.app.repository;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.callx.app.cache.CacheManager;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
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

    private ChatRepository(Context ctx) {
        mCache    = CacheManager.getInstance(ctx);
        mDb       = AppDatabase.getInstance(ctx);
        mExecutor = Executors.newFixedThreadPool(4);
        mFirebase = FirebaseDatabase.getInstance(Constants.DB_URL);
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
        mExecutor.execute(() -> {
            // TraceSectionMetric("ChatRepo#syncDelta") — full Firebase delta sync
            // wall time per chat open (background thread). Measures network +
            // Room insert. If > 2s consistently → consider WebSocket or push-triggered
            // sync instead of open-triggered pull.
            long lastTs = mCache.getLastSyncTimestamp(chatId);
            Log.d(TAG, "Delta sync chatId=" + chatId + " since=" + lastTs);

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
                if (lastTs == 0) {
                    query = mFirebase.getReference("messages")
                        .child(chatId)
                        .orderByChild("timestamp")
                        .limitToLast(PAGE_SIZE);
                } else {
                    query = mFirebase.getReference("messages")
                        .child(chatId)
                        .orderByChild("timestamp")
                        .startAfter((double) lastTs)
                        .limitToLast(PAGE_SIZE);
                }
            } finally {
                android.os.Trace.endSection();
            }

            query.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    List<MessageEntity> newMessages = new ArrayList<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        Message m = child.getValue(Message.class);
                        if (m == null) continue;
                        if (m.id == null) m.id = child.getKey();
                        newMessages.add(toEntity(m, chatId));
                    }
                    if (!newMessages.isEmpty()) {
                        mExecutor.execute(() -> {
                            // TraceSectionMetric("DB#insertMessages") — Room bulk insert
                            // cost per delta sync batch. Target: < 50ms for PAGE_SIZE=50
                            // rows. If > 100ms, chatId+timestamp index likely missing —
                            // verify MIGRATION_17_18 ran on this device.
                            android.os.Trace.beginSection("DB#insertMessages");
                            try {
                                mDb.messageDao().insertMessages(newMessages);
                            } finally {
                                android.os.Trace.endSection();
                            }
                            mCache.invalidateMessages(chatId);
                            Log.d(TAG, "Delta sync: inserted " + newMessages.size() + " new messages for " + chatId);
                        });
                    }
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    Log.w(TAG, "Delta sync cancelled: " + error.getMessage());
                }
            });
        });
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
    public io.reactivex.rxjava3.core.Single<Integer> fetchOlderMessagesFromFirebase(
            String chatId, long beforeTimestamp, int pageSize) {
        return io.reactivex.rxjava3.core.Single.<Integer>create(emitter -> {
            Query query = mFirebase.getReference("messages")
                    .child(chatId)
                    .orderByChild("timestamp")
                    .endBefore((double) beforeTimestamp)
                    .limitToLast(pageSize);

            query.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    List<MessageEntity> older = new ArrayList<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        Message m = child.getValue(Message.class);
                        if (m == null) continue;
                        if (m.id == null) m.id = child.getKey();
                        older.add(toEntity(m, chatId));
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

    // ─────────────────────────────────────────────────────────────
    // HELPERS — model ↔ entity conversion
    // ─────────────────────────────────────────────────────────────

    private MessageEntity toEntity(Message m, String chatId) {
        MessageEntity e = new MessageEntity();
        e.id              = m.id != null ? m.id : "";
        e.chatId          = chatId;
        e.senderId        = m.senderId;
        e.senderName      = m.senderName;
        e.text            = m.text;
        e.type            = m.type != null ? m.type : "text";
        e.mediaUrl        = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
        e.thumbnailUrl    = m.thumbnailUrl;
        e.fileName        = m.fileName;
        e.fileSize        = m.fileSize;
        e.duration        = m.duration;
        e.timestamp       = m.timestamp;
        e.status          = m.status;
        e.deliveredAt     = m.deliveredAt;
        e.readAt          = m.readAt;
        e.replyToId       = m.replyToId;
        e.replyToText     = m.replyToText;
        e.replyToSenderName = m.replyToSenderName;
        e.edited          = m.edited;
        e.editedAt        = m.editedAt;
        e.editHistoryJson = com.callx.app.utils.EditHistoryJsonUtil.historyToJson(m.editHistory);
        e.deleted         = m.deleted;
        e.forwardedFrom   = m.forwardedFrom;
        e.starred         = m.starred;
        e.pinned          = m.pinned;
        e.mediaItemsJson  = com.callx.app.utils.MediaItemsJsonUtil.mediaItemsToJson(m.mediaItems);
        e.caption         = m.caption;
        e.contactName     = m.contactName;
        e.contactPhone    = m.contactPhone;
        e.contactPhone2   = m.contactPhone2;
        e.contactPhotoUrl = m.contactPhotoUrl;
        e.locationLat     = m.locationLat;
        e.locationLng     = m.locationLng;
        e.locationAddress = m.locationAddress;
        e.syncedAt        = System.currentTimeMillis();
        return e;
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
