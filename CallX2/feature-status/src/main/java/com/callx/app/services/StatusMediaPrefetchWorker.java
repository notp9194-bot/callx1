package com.callx.app.services;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.callx.app.cache.StatusCachePrefs;
import com.callx.app.cache.StatusMediaManifest;
import com.callx.app.cache.StatusMediaPreloader;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusHideManager;
import com.callx.app.utils.StatusMuteManager;
import com.callx.app.utils.StorySeenState;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DataSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * StatusMediaPrefetchWorker — WhatsApp-style background pre-download.
 *
 * Hourly, UNMETERED (Wi-Fi) + battery-not-low, even when the app / Status tab is closed, and only while the
 * user's "Auto-download on Wi-Fi" switch is on:
 *   1. drops cached videos of expired statuses, 2. pulls contacts' CURRENT statuses straight from Firebase
 *   (falls back to Room), 3. skips own / muted / hidden / already-seen, 4. downloads newest-first inside a
 *   per-run byte budget AND status' quota of the shared player cache, 5. stops mid-download when WorkManager stops it.
 */
public class StatusMediaPrefetchWorker extends Worker {
    public static final String UNIQUE_NAME = "status_media_prefetch";
    private static final long TIME_BUDGET_MS = 8L * 60_000L;
    private static final long RUN_BUDGET_BYTES = 100L * 1024 * 1024;
    private static final long EST_VIDEO_BYTES = 8L * 1024 * 1024;
    private static final int  MAX_ITEMS = 60;
    private static final int  MAX_CONTACTS = 100;

    public StatusMediaPrefetchWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context ctx = getApplicationContext();
        try {
            StatusMediaManifest.sweepNow(ctx);
            if (!StatusCachePrefs.isAutoDownloadOnWifi(ctx)) return Result.success();
            final long now = System.currentTimeMillis();
            final long deadline = now + TIME_BUDGET_MS;
            final String me = FirebaseUtils.getCurrentUid();
            if (me == null || me.isEmpty()) return Result.success();
            final Set<String> muted = StatusMuteManager.getMutedSet(ctx);
            final Set<String> hidden = StatusHideManager.getHiddenSet(ctx);

            List<StatusItem> cands = fetchFresh(me, now, deadline, muted, hidden);
            if (cands.isEmpty()) cands = fromRoom(ctx, now);               // offline-ish / Firebase failed → what the app already knows
            cands.sort((a, b) -> Long.compare(b.timestamp == null ? 0 : b.timestamp, a.timestamp == null ? 0 : a.timestamp));

            long added = 0;
            int done = 0;
            for (StatusItem e : cands) {
                if (isStopped() || System.currentTimeMillis() > deadline || done >= MAX_ITEMS || added >= RUN_BUDGET_BYTES) break;
                if (e == null || e.ownerUid == null || e.ownerUid.equals(me)) continue;
                if (muted.contains(e.ownerUid) || hidden.contains(e.ownerUid)) continue;
                if (e.timestamp != null && StorySeenState.isSeenLocally(ctx, e.ownerUid, e.timestamp)) continue; // already watched
                final long exp = StatusItem.effectiveExpiry(e.expiresAt, e.timestamp);
                try {
                    if ("video".equals(e.type) && has(e.mediaUrl)) {
                        if (!StatusMediaManifest.hasRoom(ctx, EST_VIDEO_BYTES)) break;   // status quota full → never evict for prefetch
                        added += StatusMediaPreloader.cacheVideoBlocking(ctx, e.mediaUrl, true, exp, this::isStopped);
                        if (has(e.thumbnailUrl)) StatusMediaPreloader.cacheImageBlocking(ctx, e.thumbnailUrl);
                        done++;
                    } else if ("image".equals(e.type) && has(e.mediaUrl)) {
                        StatusMediaPreloader.cacheImageBlocking(ctx, e.mediaUrl);
                        if (has(e.thumbnailUrl) && !e.thumbnailUrl.equals(e.mediaUrl))
                            StatusMediaPreloader.cacheImageBlocking(ctx, e.thumbnailUrl);
                        done++;
                    } else if (has(e.thumbnailUrl)) {
                        StatusMediaPreloader.cacheImageBlocking(ctx, e.thumbnailUrl);
                        done++;
                    }
                } catch (Exception skip) { /* this one failed — keep going with the rest */ }
            }
            StatusMediaManifest.trimToQuota(ctx);
        } catch (Exception ignored) {}
        return Result.success(); // never retry-storm; the next hourly run tries again
    }

    /** Contacts' live statuses straight from Firebase (so statuses posted while the app was closed are included). */
    private List<StatusItem> fetchFresh(String me, long now, long deadline, Set<String> muted, Set<String> hidden) {
        final List<StatusItem> out = new ArrayList<>();
        try {
            final DataSnapshot contacts = Tasks.await(FirebaseUtils.getContactsRef(me).get(), 20, TimeUnit.SECONDS);
            int n = 0;
            for (DataSnapshot c : contacts.getChildren()) {
                if (isStopped() || System.currentTimeMillis() > deadline || n >= MAX_CONTACTS) break;
                final String uid = c.getKey();
                if (uid == null || uid.equals(me) || muted.contains(uid) || hidden.contains(uid)) continue;
                n++;
                try {
                    final DataSnapshot snap = Tasks.await(FirebaseUtils.getUserStatusRef(uid).get(), 10, TimeUnit.SECONDS);
                    for (DataSnapshot ds : snap.getChildren()) {
                        StatusItem it;
                        try { it = ds.getValue(StatusItem.class); } catch (Exception bad) { continue; }
                        if (it == null || Boolean.TRUE.equals(it.deleted) || !it.isLive(now)) continue;
                        it.ownerUid = uid;
                        if (it.id == null) it.id = ds.getKey();
                        out.add(it);
                    }
                } catch (Exception skip) { /* one contact failed */ }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private List<StatusItem> fromRoom(Context ctx, long now) {
        final List<StatusItem> out = new ArrayList<>();
        try {
            final List<StatusEntity> rows = AppDatabase.getInstance(ctx).statusDao().getActiveStatuses(now);
            if (rows == null) return out;
            for (StatusEntity r : rows) {
                if (r == null) continue;
                final StatusItem it = new StatusItem();
                it.id = r.id; it.ownerUid = r.ownerUid; it.type = r.type;
                it.mediaUrl = r.mediaUrl; it.thumbnailUrl = r.thumbnailUrl;
                it.timestamp = r.timestamp; it.expiresAt = r.expiresAt;
                out.add(it);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean has(String s) { return s != null && !s.isEmpty(); }

    public static void schedule(@NonNull Context ctx) {
        try {
            final PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                    StatusMediaPrefetchWorker.class, 1, TimeUnit.HOURS)
                    .setConstraints(new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresBatteryNotLow(true)
                            .build())
                    .build();
            WorkManager.getInstance(ctx.getApplicationContext())
                    .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Exception ignored) {}
    }
}
