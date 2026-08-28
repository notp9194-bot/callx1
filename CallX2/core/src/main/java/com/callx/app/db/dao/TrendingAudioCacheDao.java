package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.TrendingAudioCacheEntity;

import java.util.List;

/**
 * v50: DAO for the offline-first Trending Audio browser cache.
 */
@Dao
public interface TrendingAudioCacheDao {

    @WorkerThread
    @Query("SELECT * FROM trending_audio_cache WHERE source = :source " +
           "ORDER BY sortOrder ASC LIMIT :limit")
    List<TrendingAudioCacheEntity> getPage(String source, int limit);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<TrendingAudioCacheEntity> items);

    /** Replaces the cached page for a source ("library"/"sounds") atomically-enough for our needs. */
    @WorkerThread
    @Query("DELETE FROM trending_audio_cache WHERE source = :source")
    void clearForSource(String source);

    @WorkerThread
    @Query("DELETE FROM trending_audio_cache WHERE cachedAt < :olderThan")
    void pruneOlderThan(long olderThan);
}
