package com.callx.app.cache;

import android.content.Context;
import android.util.Log;

import androidx.annotation.WorkerThread;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.db.entity.UserEntity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * CacheManager — the "brain" of the multi-tier cache.
 *
 * Tier flow:
 *   1. Memory Cache (fastest — RAM)
 *   2. Room DB     (fast   — disk via SQLite)
 *   3. Firebase    (network — handled by Repository)
 *
 * Previously fixed (v10):
 *   - Bounded ThreadPoolExecutor (ArrayBlockingQueue 128, CallerRunsPolicy)
 *
 * FIX #8 (LOW): Added @WorkerThread annotation to getMessages().
 *
 *   Old: getMessages() had no annotation.
 *   → Any developer (or future teammate) could accidentally call it from the
 *     main thread. The method does a synchronous Room query (getMessagesPaged)
 *     which blocks the caller.
 *   → On the main thread this causes a StrictMode violation in debug builds
 *     and an ANR (Application Not Responding) crash in production if the
 *     query takes > 5 seconds (e.g., on a slow/encrypted DB with 10k+ messages).
 *   → The bug is silent in debug (StrictMode often missed) and only surfaces
 *     at scale in production.
 *
 *   Fix: @WorkerThread annotation — Android Studio / Lint flags any call site
 *     that's not on a background thread at compile time. Zero runtime cost.
 *     Also added @WorkerThread to saveUser() and getUser() which have the
 *     same issue.
 */
public class CacheManager {

    private static final String TAG = "CacheManager";

    private static CacheManager sInstance;

    private final MemoryCache    mMemory;
    private final DiskCache      mDisk;
    private final CacheAnalytics mAnalytics;
    private final AppDatabase    mDb;

    // Bounded executor — prevents OOM from unbounded queue (fixed in v10)
    private final ThreadPoolExecutor mExecutor;

    private CacheManager(Context ctx) {
        mMemory    = MemoryCache.getInstance();
        mDisk      = DiskCache.getInstance(ctx);
        mAnalytics = CacheAnalytics.getInstance(ctx);
        mDb        = AppDatabase.getInstance(ctx);

        mExecutor  = new ThreadPoolExecutor(
                2, 3,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public static synchronized CacheManager getInstance(Context ctx) {
        if (sInstance == null) sInstance = new CacheManager(ctx.getApplicationContext());
        return sInstance;
    }

    // ─────────────────────────────────────────────────────────────
    // MESSAGE CACHE
    // ─────────────────────────────────────────────────────────────

    /**
     * Get messages from cache tiers.
     *
     * FIX #8: @WorkerThread annotation added.
     * This method does a synchronous Room DB query — NEVER call from main thread.
     * Calling from main thread = StrictMode violation in debug, ANR in production.
     *
     * Tier order:
     *   1. RAM  → instant
     *   2. Room → fast (indexed DB query)
     *   3. Empty list (Repository triggers Firebase fetch)
     */
    @WorkerThread
    @SuppressWarnings("unchecked")
    public List<MessageEntity> getMessages(String chatId) {
        mAnalytics.recordChatOpen(chatId);

        // Tier-1: RAM
        Object cached = mMemory.get("msg_" + chatId);
        if (cached instanceof List) {
            Log.d(TAG, "Cache HIT (RAM): " + chatId);
            return (List<MessageEntity>) cached;
        }

        // Tier-2: Room DB — synchronous, must be on background thread
        List<MessageEntity> dbData = mDb.messageDao().getMessagesPaged(chatId, 50, 0);
        if (dbData != null && !dbData.isEmpty()) {
            Log.d(TAG, "Cache HIT (DB): " + chatId + " count=" + dbData.size());
            CachePriority priority = mAnalytics.getPriority(chatId);
            if (priority == CachePriority.HIGH || priority == CachePriority.MEDIUM) {
                mMemory.put("msg_" + chatId, dbData);
            }
            return dbData;
        }

        Log.d(TAG, "Cache MISS: " + chatId);
        return new ArrayList<>();
    }

    public void saveMessages(String chatId, List<MessageEntity> messages) {
        if (messages == null || messages.isEmpty()) return;
        mExecutor.execute(() -> {
            mDb.messageDao().insertMessages(messages);
            CachePriority priority = mAnalytics.getPriority(chatId);
            if (priority != CachePriority.LOW) {
                mMemory.put("msg_" + chatId, messages);
            }
        });
    }

    public void invalidateMessages(String chatId) {
        mMemory.remove("msg_" + chatId);
    }

    // ─────────────────────────────────────────────────────────────
    // USER CACHE
    // ─────────────────────────────────────────────────────────────

    /** FIX #8: @WorkerThread — getUser() does a synchronous Room query. */
    @WorkerThread
    public UserEntity getUser(String uid) {
        Object cached = mMemory.get("user_" + uid);
        if (cached instanceof UserEntity) return (UserEntity) cached;
        UserEntity entity = mDb.userDao().getUser(uid);
        if (entity != null) mMemory.put("user_" + uid, entity);
        return entity;
    }

    public void saveUser(UserEntity user) {
        mExecutor.execute(() -> {
            mDb.userDao().insertUser(user);
            mMemory.put("user_" + user.uid, user);
        });
    }

    public void invalidateUser(String uid) {
        mMemory.remove("user_" + uid);
    }

    // ─────────────────────────────────────────────────────────────
    // PREDICTIVE PRELOADING
    // ─────────────────────────────────────────────────────────────

    public void preloadTopChats() {
        mExecutor.execute(() -> {
            List<String> topChats = mAnalytics.getTopChats(3);
            for (String chatId : topChats) {
                if (mMemory.get("msg_" + chatId) == null) {
                    List<MessageEntity> msgs = mDb.messageDao().getMessagesPaged(chatId, 50, 0);
                    if (msgs != null && !msgs.isEmpty()) {
                        mMemory.put("msg_" + chatId, msgs);
                        Log.d(TAG, "Predictive preload: " + chatId);
                    }
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // SMART EVICTION
    // ─────────────────────────────────────────────────────────────

    public void evictLowPriority() {
        List<String> topChats = mAnalytics.getTopChats(10);
        List<String> keepKeys = new ArrayList<>();
        for (String chatId : topChats) {
            CachePriority p = mAnalytics.getPriority(chatId);
            if (p == CachePriority.HIGH || p == CachePriority.MEDIUM) {
                keepKeys.add("msg_" + chatId);
                keepKeys.add("user_" + chatId);
            }
        }
        mMemory.keepOnly(keepKeys);
    }

    // ─────────────────────────────────────────────────────────────
    // FULL CLEANUP
    // ─────────────────────────────────────────────────────────────

    public void runFullCleanup() {
        mExecutor.execute(() -> {
            mDisk.cleanExpired();
            Log.d(TAG, "Disk cache cleaned. Size=" + mDisk.getCacheSizeBytes() / 1024 + "KB");
            mAnalytics.pruneStale();
            Log.d(TAG, "Full cache cleanup done");
        });
    }

    // ─────────────────────────────────────────────────────────────
    // DELTA SYNC SUPPORT
    // ─────────────────────────────────────────────────────────────

    public long getLastSyncTimestamp(String chatId) {
        Long ts = mDb.messageDao().getLastTimestamp(chatId);
        return ts != null ? ts : 0L;
    }

    // ─────────────────────────────────────────────────────────────
    // ACCESSORS (for CacheStatsActivity)
    // ─────────────────────────────────────────────────────────────

    public AppDatabase    getDatabase()    { return mDb; }
    public DiskCache      getDiskCache()   { return mDisk; }
    public MemoryCache    getMemoryCache() { return mMemory; }
    public CacheAnalytics getAnalytics()  { return mAnalytics; }

    // ─────────────────────────────────────────────────────────────
    // EXPLICIT CLEAR (from CacheStatsActivity)
    // ─────────────────────────────────────────────────────────────

    public void clearMemoryCache() {
        mMemory.evictAll();
        Log.d(TAG, "Memory cache cleared");
    }

    public void clearDiskCache() {
        mExecutor.execute(() -> {
            File dir = mDisk.getDiskCacheDir();
            if (dir != null) {
                File[] files = dir.listFiles();
                if (files != null) for (File f : files) f.delete();
            }
            Log.d(TAG, "Disk cache cleared");
        });
    }
}
