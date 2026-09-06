package com.callx.app.db.dao;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.MediaHashCacheEntity;

/**
 * DAO for the local content-hash media dedup cache. See
 * MediaHashCacheEntity's class doc for why this table exists.
 */
@Dao
public interface MediaHashCacheDao {

    @WorkerThread
    @Nullable
    @Query("SELECT * FROM media_hash_cache WHERE hash = :hash LIMIT 1")
    MediaHashCacheEntity getByHash(String hash);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(MediaHashCacheEntity entity);

    @WorkerThread
    @Query("DELETE FROM media_hash_cache WHERE cachedAt < :olderThan")
    void pruneOlderThan(long olderThan);
}
