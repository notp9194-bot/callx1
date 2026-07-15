package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.ReelThumbCacheEntity;

import java.util.List;

/**
 * v33: DAO for the offline-first profile reels grid cache.
 */
@Dao
public interface ReelThumbCacheDao {

    @WorkerThread
    @Query("SELECT * FROM reel_thumb_cache WHERE ownerUid = :ownerUid AND tab = :tab " +
           "ORDER BY sortOrder ASC LIMIT :limit")
    List<ReelThumbCacheEntity> getPage(String ownerUid, int tab, int limit);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ReelThumbCacheEntity> items);

    /** Replaces the cached first page for (ownerUid, tab) atomically-enough for our needs. */
    @WorkerThread
    @Query("DELETE FROM reel_thumb_cache WHERE ownerUid = :ownerUid AND tab = :tab")
    void clearForTab(String ownerUid, int tab);

    @WorkerThread
    @Query("DELETE FROM reel_thumb_cache WHERE cachedAt < :olderThan")
    void pruneOlderThan(long olderThan);
}
