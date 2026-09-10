package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.ReelCommentCacheEntity;

import java.util.List;

/**
 * DAO for the offline-first Reel comments sheet cache.
 */
@Dao
public interface ReelCommentCacheDao {

    @WorkerThread
    @Query("SELECT * FROM reel_comment_cache WHERE reelId = :reelId " +
           "ORDER BY sortOrder ASC LIMIT :limit")
    List<ReelCommentCacheEntity> getPage(String reelId, int limit);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ReelCommentCacheEntity> items);

    /** Replaces the cached window for a reel atomically-enough for our needs. */
    @WorkerThread
    @Query("DELETE FROM reel_comment_cache WHERE reelId = :reelId")
    void clearForReel(String reelId);

    @WorkerThread
    @Query("DELETE FROM reel_comment_cache WHERE cachedAt < :olderThan")
    void pruneOlderThan(long olderThan);
}
