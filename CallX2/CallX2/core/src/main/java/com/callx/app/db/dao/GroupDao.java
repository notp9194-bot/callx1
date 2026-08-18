package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.GroupEntity;

import java.util.List;

/**
 * v16: DAO for groups offline cache.
 */
@Dao
public interface GroupDao {

    @WorkerThread
    @Query("SELECT * FROM groups ORDER BY lastMessageAt DESC")
    List<GroupEntity> getAllGroupsSync();

    /** v18 IMPROVEMENT 3: Single group by id — GroupInfoActivity offline fallback ke liye. */
    @WorkerThread
    @Query("SELECT * FROM groups WHERE id = :id LIMIT 1")
    GroupEntity getGroup(String id);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertGroup(GroupEntity group);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertGroups(List<GroupEntity> groups);

    @WorkerThread
    @Query("DELETE FROM groups WHERE id = :id")
    void deleteGroup(String id);

    @WorkerThread
    @Query("SELECT COUNT(*) FROM groups")
    int getCount();
}
