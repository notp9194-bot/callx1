package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.StatusEntity;

import java.util.List;

/**
 * v17: DAO for status cache.
 */
@Dao
public interface StatusDao {

    @WorkerThread
    @Query("SELECT * FROM statuses WHERE deleted != 1 AND expiresAt > :now ORDER BY timestamp ASC")
    List<StatusEntity> getActiveStatuses(long now);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertStatuses(List<StatusEntity> statuses);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertStatus(StatusEntity status);

    /** Purane expired statuses cleanup */
    @WorkerThread
    @Query("DELETE FROM statuses WHERE expiresAt < :now")
    void pruneExpired(long now);

    @WorkerThread
    @Query("SELECT COUNT(*) FROM statuses")
    int getCount();
}
