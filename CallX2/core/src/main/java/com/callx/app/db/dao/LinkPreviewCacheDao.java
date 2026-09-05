package com.callx.app.db.dao;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.LinkPreviewCacheEntity;

/**
 * DAO for the disk-backed link preview cache. See LinkPreviewCacheEntity's
 * class doc for why this table exists.
 */
@Dao
public interface LinkPreviewCacheDao {

    @WorkerThread
    @Nullable
    @Query("SELECT * FROM link_preview_cache WHERE url = :url LIMIT 1")
    LinkPreviewCacheEntity getByUrl(String url);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(LinkPreviewCacheEntity entity);

    @WorkerThread
    @Query("DELETE FROM link_preview_cache WHERE cachedAt < :olderThan")
    void pruneOlderThan(long olderThan);

    @WorkerThread
    @Query("DELETE FROM link_preview_cache WHERE url = :url")
    void deleteByUrl(String url);
}
