package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.callx.app.db.entity.HomeFeedCacheEntity;

import java.util.List;

/**
 * DAO for HomeFeedCacheEntity — the Home tab's cold-start instant-paint
 * cache. See HomeFeedCacheEntity's class doc for the full rationale.
 */
@Dao
public interface HomeFeedCacheDao {

    @WorkerThread
    @Query("SELECT * FROM home_feed_cache ORDER BY sortOrder ASC")
    List<HomeFeedCacheEntity> getCached();

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<HomeFeedCacheEntity> items);

    @WorkerThread
    @Query("DELETE FROM home_feed_cache")
    void clearAll();

    /**
     * Atomically replaces the whole cached page — called after every
     * successful full feed load (initial load, refresh, mode switch).
     * Never called from a load-more/pagination path, so this table only
     * ever holds the top-of-feed screenful, by design.
     */
    @WorkerThread
    @Transaction
    default void replaceAll(List<HomeFeedCacheEntity> items) {
        clearAll();
        if (items != null && !items.isEmpty()) insertAll(items);
    }
}
