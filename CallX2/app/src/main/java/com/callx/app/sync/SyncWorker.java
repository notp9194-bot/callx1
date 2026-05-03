package com.callx.app.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.cache.CacheManager;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.repository.ChatRepository;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Background Smart Sync Worker.
 *
 * FIX #2 (HIGH): Three problems fixed:
 *
 *   A) No backoff policy:
 *      Old: Result.retry() → WorkManager default LINEAR backoff (30s initial).
 *      New: BackoffPolicy.EXPONENTIAL with 1-minute initial delay → retries
 *           at 1m, 2m, 4m, 8m… up to WorkManager's 5h cap.
 *           Prevents battery drain from tight retry loops on permanent errors
 *           (DB corruption, no auth, no network).
 *
 *   B) No max retry limit:
 *      Old: infinite retry on any exception.
 *      New: getRunAttemptCount() check — after MAX_ATTEMPTS consecutive failures,
 *           returns Result.failure() so WorkManager stops retrying until the
 *           next scheduled period. Prevents perpetual battery drain.
 *
 *   C) Light and Heavy sync ran identical code:
 *      Old: Both PeriodicWorkRequests called the same doWork() blindly.
 *      New: InputData key SYNC_TYPE distinguishes LIGHT vs HEAVY.
 *           LIGHT (15 min): delta sync + predictive preload only (fast).
 *           HEAVY (30 min, WiFi+charging): full cleanup + DB prune + analytics prune.
 */
public class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";

    // InputData key for sync type
    public static final String KEY_SYNC_TYPE   = "sync_type";
    public static final String TYPE_LIGHT      = "light";
    public static final String TYPE_HEAVY      = "heavy";

    private static final String WORK_NAME_LIGHT = "callx_sync_light";
    private static final String WORK_NAME_HEAVY = "callx_sync_heavy";

    // FIX #2B: max consecutive attempts before giving up until next period
    private static final int MAX_ATTEMPTS = 3;

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {

        // FIX #2B: stop retrying after MAX_ATTEMPTS consecutive failures
        if (getRunAttemptCount() >= MAX_ATTEMPTS) {
            Log.w(TAG, "Max retry attempts reached — giving up until next period");
            return Result.failure();
        }

        // FIX #2C: differentiate light vs heavy via InputData
        String syncType = getInputData().getString(KEY_SYNC_TYPE);
        boolean isHeavy = TYPE_HEAVY.equals(syncType);

        Log.d(TAG, "SyncWorker started — type=" + syncType
                + " attempt=" + getRunAttemptCount());

        try {
            Context ctx = getApplicationContext();
            CacheManager   cache = CacheManager.getInstance(ctx);
            ChatRepository repo  = ChatRepository.getInstance(ctx);
            AppDatabase    db    = AppDatabase.getInstance(ctx);

            // ── Step 1 (BOTH): Delta sync all known chats ────────────────
            List<ChatEntity> chats = db.chatDao().getAllChatsSync();
            if (chats != null && !chats.isEmpty()) {
                for (ChatEntity chat : chats) {
                    repo.syncMessagesDelta(chat.chatId);
                }
                Log.d(TAG, "Delta sync triggered for " + chats.size() + " chats");
            }

            // ── Step 2 (BOTH): Predictive preload top 3 chats into RAM ───
            cache.preloadTopChats();

            // ── Step 3 (HEAVY ONLY): Full cleanup + DB prune ─────────────
            if (isHeavy) {
                // Prune old messages (keep last 200 per chat)
                if (chats != null) {
                    for (ChatEntity chat : chats) {
                        repo.pruneOldMessages(chat.chatId, 200);
                    }
                    Log.d(TAG, "DB pruned for " + chats.size() + " chats");
                }

                // Full cache cleanup: disk expired files + analytics prune
                cache.runFullCleanup();

                // Prune stale user records older than 7 days
                long cutoff7d = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
                db.userDao().pruneStale(cutoff7d);

                Log.d(TAG, "Heavy sync cleanup complete");
            }

            Log.d(TAG, "SyncWorker finished — type=" + syncType);
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "SyncWorker failed (attempt " + getRunAttemptCount() + "): "
                    + e.getMessage(), e);
            // FIX #2A: EXPONENTIAL backoff configured in schedule() below
            return Result.retry();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SCHEDULE — call once from CallxApp.onCreate()
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Schedules two periodic sync workers:
     *
     *  LIGHT — every 15 min, any network.
     *          Does: delta sync + predictive preload.
     *          Fast, minimal battery impact.
     *
     *  HEAVY — every 30 min, WiFi + charging only.
     *          Does: everything LIGHT does + full DB prune + disk cleanup +
     *          analytics prune + stale user purge.
     *
     * FIX #2A: EXPONENTIAL backoff (initial 1 min, doubles per retry, max 5h).
     * FIX #2C: InputData tells doWork() which path to execute.
     */
    public static void schedule(Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);

        // ── LIGHT SYNC ────────────────────────────────────────────────────
        Data lightData = new Data.Builder()
                .putString(KEY_SYNC_TYPE, TYPE_LIGHT)
                .build();

        Constraints lightConstraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest lightSync = new PeriodicWorkRequest.Builder(
                SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(lightConstraints)
                .setInputData(lightData)
                // FIX #2A: exponential backoff — 1 min initial, doubles per retry
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build();

        wm.enqueueUniquePeriodicWork(
                WORK_NAME_LIGHT,
                ExistingPeriodicWorkPolicy.KEEP,
                lightSync
        );

        // ── HEAVY SYNC (WiFi + charging) ──────────────────────────────────
        Data heavyData = new Data.Builder()
                .putString(KEY_SYNC_TYPE, TYPE_HEAVY)
                .build();

        Constraints heavyConstraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build();

        PeriodicWorkRequest heavySync = new PeriodicWorkRequest.Builder(
                SyncWorker.class, 30, TimeUnit.MINUTES)
                .setConstraints(heavyConstraints)
                .setInputData(heavyData)
                // FIX #2A: exponential backoff — 1 min initial
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build();

        wm.enqueueUniquePeriodicWork(
                WORK_NAME_HEAVY,
                ExistingPeriodicWorkPolicy.KEEP,
                heavySync
        );

        Log.d(TAG, "SyncWorker scheduled: LIGHT(15min/any) + HEAVY(30min/WiFi+charging)");
    }

    public static void cancel(Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);
        wm.cancelUniqueWork(WORK_NAME_LIGHT);
        wm.cancelUniqueWork(WORK_NAME_HEAVY);
    }
}
