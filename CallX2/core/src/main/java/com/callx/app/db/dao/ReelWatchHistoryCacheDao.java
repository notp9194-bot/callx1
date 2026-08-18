package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.ReelWatchHistoryCacheEntity;

import java.util.List;

/**
 * v49: DAO for the local reel-watch-history cache — see
 * ReelWatchHistoryCacheEntity for what this table is and isn't for.
 */
@Dao
public interface ReelWatchHistoryCacheDao {

    /** All cached reelIds — grid render only needs membership, not the timestamp. */
    @WorkerThread
    @Query("SELECT reelId FROM reel_watch_history_cache")
    List<String> getAllReelIds();

    /**
     * GAP FIX: "Just watched" previously never expired — a reel watched
     * months ago would still show the badge forever (only the row-count
     * cap in pruneToMax() ever removed anything, and only once 2000 rows
     * had piled up). Instagram's own indicator is a recency thing, not a
     * permanent "ever watched" mark. Grid binds should call this instead of
     * getAllReelIds(), passing (now - RECENT_WATCH_WINDOW_MS).
     */
    @WorkerThread
    @Query("SELECT reelId FROM reel_watch_history_cache WHERE watchedAt >= :sinceTimestamp")
    List<String> getRecentReelIds(long sinceTimestamp);

    /** Newest watchedAt currently cached — used as the Firebase incremental-sync cursor. */
    @WorkerThread
    @Query("SELECT MAX(watchedAt) FROM reel_watch_history_cache")
    Long getLatestWatchedAt();

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ReelWatchHistoryCacheEntity> items);

    /** Time-based expiry — actually deletes rows past the "Just watched" recency window, not just caps table size. */
    @WorkerThread
    @Query("DELETE FROM reel_watch_history_cache WHERE watchedAt < :olderThan")
    void pruneOlderThan(long olderThan);

    /** Oldest-first prune so the cache doesn't grow unbounded for a heavy reels user. */
    @WorkerThread
    @Query("DELETE FROM reel_watch_history_cache WHERE reelId IN " +
           "(SELECT reelId FROM reel_watch_history_cache ORDER BY watchedAt ASC " +
           "LIMIT MAX(0, (SELECT COUNT(*) FROM reel_watch_history_cache) - :keepMax))")
    void pruneToMax(int keepMax);
}
