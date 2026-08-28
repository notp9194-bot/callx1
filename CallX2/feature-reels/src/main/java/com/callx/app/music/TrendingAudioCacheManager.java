package com.callx.app.music;

import android.content.Context;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.TrendingAudioCacheEntity;
import com.callx.app.music.ReelTrendingAudioActivity.Audio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TrendingAudioCacheManager — offline-first warm-start for the Trending
 * Audio browser (ReelTrendingAudioActivity).
 *
 * Room-backed, same shape as ReelThumbCacheManager: the last successfully
 * loaded page of `allTracks` ("library") / `allSoundsTracks` ("sounds") is
 * persisted here, so reopening the screen (even fully offline, e.g. a cold
 * app start with no network) can render immediately from disk while the
 * real Firebase read runs in the background and silently replaces it once
 * it lands. Search results, saved-only tracks, and demo fallback tracks
 * are NOT cached — only the plain top-N page each tab starts from.
 */
final class TrendingAudioCacheManager {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final long MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000; // 3 days — this list churns fast

    static final String SOURCE_LIBRARY = "library";
    static final String SOURCE_SOUNDS  = "sounds";

    private TrendingAudioCacheManager() {}

    /** Fire-and-forget: replaces the cached page for a source ("library"/"sounds"). */
    static void savePage(Context ctx, String source, List<Audio> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        Context appCtx = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appCtx);
                List<TrendingAudioCacheEntity> rows = new ArrayList<>();
                int order = 0;
                for (Audio a : tracks) {
                    if (a == null || a.id == null || a.id.isEmpty()) continue;
                    TrendingAudioCacheEntity e = new TrendingAudioCacheEntity();
                    e.audioId         = a.id;
                    e.source          = source;
                    e.title           = a.title;
                    e.artist          = a.artist;
                    e.audioUrl        = a.audioUrl;
                    e.previewAudioUrl = a.previewAudioUrl;
                    e.coverUrl        = a.coverUrl;
                    e.genre           = a.genre;
                    e.mood            = a.mood;
                    e.usageCount      = a.usageCount;
                    e.durationMs      = a.durationMs;
                    e.trendingRank    = a.trendingRank;
                    e.bpm             = a.bpm;
                    e.addedAt         = a.addedAt;
                    e.sortOrder       = order++;
                    rows.add(e);
                }
                db.trendingAudioCacheDao().clearForSource(source);
                db.trendingAudioCacheDao().insertAll(rows);
                db.trendingAudioCacheDao().pruneOlderThan(System.currentTimeMillis() - MAX_AGE_MS);
            } catch (Exception ignored) {
                // Non-critical — screen still works from Firebase, just without the offline warm-start.
            }
        });
    }

    /** Synchronous read — call only from a background thread. */
    static List<Audio> loadPageBlocking(Context ctx, String source, int limit) {
        List<Audio> out = new ArrayList<>();
        try {
            AppDatabase db = AppDatabase.getInstance(ctx.getApplicationContext());
            List<TrendingAudioCacheEntity> rows = db.trendingAudioCacheDao().getPage(source, limit);
            for (TrendingAudioCacheEntity e : rows) {
                Audio a = new Audio();
                a.id              = e.audioId;
                a.title           = e.title;
                a.artist          = e.artist;
                a.audioUrl        = e.audioUrl;
                a.previewAudioUrl = e.previewAudioUrl;
                a.coverUrl        = e.coverUrl;
                a.genre           = e.genre;
                a.mood            = e.mood;
                a.usageCount      = e.usageCount;
                a.durationMs      = e.durationMs;
                a.trendingRank    = e.trendingRank;
                a.bpm             = e.bpm;
                a.addedAt         = e.addedAt;
                a.buildSearchCache();
                out.add(a);
            }
        } catch (Exception ignored) {
            // Cache miss/corruption — caller falls back to the normal Firebase load.
        }
        return out;
    }
}
