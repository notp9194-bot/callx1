package com.callx.app.cache;

import android.content.Context;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ReelThumbCacheEntity;
import com.callx.app.models.ReelModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelThumbCacheManager — advance #6 (offline-first profile reels grid).
 *
 * Room-backed equivalent of the two-tier avatar cache already used for
 * chat profiles (UserEntity.thumbUrl/photoUrl): saves the thumbUrl +
 * blurHash (+ the small set of counters the grid overlay shows) for the
 * first page of a profile's reels tab, so the grid can render from disk
 * before Firebase responds — no blank grid / no flash on cold app open.
 */
public final class ReelThumbCacheManager {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final long MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000; // 2 weeks

    private ReelThumbCacheManager() {}

    /** Fire-and-forget: replaces the cached first page for (ownerUid, tab). */
    public static void savePage(Context ctx, String ownerUid, int tab, List<ReelModel> reels) {
        if (ownerUid == null || ownerUid.isEmpty() || reels == null || reels.isEmpty()) return;
        Context appCtx = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appCtx);
                List<ReelThumbCacheEntity> rows = new ArrayList<>();
                int order = 0;
                for (ReelModel r : reels) {
                    if (r == null || r.reelId == null || r.reelId.isEmpty()) continue;
                    ReelThumbCacheEntity e = new ReelThumbCacheEntity();
                    e.reelId        = r.reelId;
                    e.ownerUid      = ownerUid;
                    e.tab           = tab;
                    e.thumbUrl      = r.effectiveThumbUrl();
                    e.blurHash      = r.blurHash;
                    e.caption       = r.caption;
                    e.duration      = r.duration;
                    e.viewsCount    = r.viewsCount;
                    e.likesCount    = r.likesCount;
                    e.commentsCount = r.commentsCount;
                    e.timestamp     = r.timestamp;
                    e.sortOrder     = order++;
                    rows.add(e);
                }
                db.reelThumbCacheDao().clearForTab(ownerUid, tab);
                db.reelThumbCacheDao().insertAll(rows);
                db.reelThumbCacheDao().pruneOlderThan(System.currentTimeMillis() - MAX_AGE_MS);
            } catch (Exception ignored) {
                // Non-critical — grid still works from Firebase, just without the offline warm-start.
            }
        });
    }

    /** Synchronous read — call only from a background thread (matches existing loadFromRoom() pattern). */
    public static List<ReelModel> loadPageBlocking(Context ctx, String ownerUid, int tab, int limit) {
        List<ReelModel> out = new ArrayList<>();
        if (ownerUid == null || ownerUid.isEmpty()) return out;
        try {
            AppDatabase db = AppDatabase.getInstance(ctx.getApplicationContext());
            List<ReelThumbCacheEntity> rows = db.reelThumbCacheDao().getPage(ownerUid, tab, limit);
            for (ReelThumbCacheEntity e : rows) {
                ReelModel r = new ReelModel();
                r.reelId        = e.reelId;
                r.uid            = ownerUid;
                r.thumbUrl       = e.thumbUrl;
                r.blurHash       = e.blurHash;
                r.caption        = e.caption;
                r.duration       = e.duration;
                r.viewsCount     = e.viewsCount;
                r.likesCount     = e.likesCount;
                r.commentsCount  = e.commentsCount;
                r.timestamp      = e.timestamp;
                out.add(r);
            }
        } catch (Exception ignored) {
            // Cache miss/corruption — caller falls back to the normal Firebase load.
        }
        return out;
    }
}
