package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.ThumbHashCacheEntity;

import java.util.List;

/**
 * DAO for ThumbHashPlaceholder's disk-persisted L2 cache.
 */
@Dao
public interface ThumbHashCacheDao {

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ThumbHashCacheEntity entity);

    /** Most-recently-cached rows first, so warm-up never loads more than
     *  the in-memory LruCache can actually hold. */
    @WorkerThread
    @Query("SELECT * FROM thumbhash_cache ORDER BY cachedAt DESC LIMIT :limit")
    List<ThumbHashCacheEntity> getRecent(int limit);

    @WorkerThread
    @Query("SELECT * FROM thumbhash_cache WHERE cacheKey = :cacheKey LIMIT 1")
    ThumbHashCacheEntity get(String cacheKey);

    @WorkerThread
    @Query("DELETE FROM thumbhash_cache WHERE cachedAt < :olderThan")
    void pruneOlderThan(long olderThan);
}
