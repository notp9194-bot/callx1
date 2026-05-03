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
