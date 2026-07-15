package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.GroupMemberEntity;

import java.util.List;

/**
 * v31: DAO for group members offline cache.
 */
@Dao
public interface GroupMemberDao {

    @WorkerThread
    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    List<GroupMemberEntity> getMembersSync(String groupId);

    @WorkerThread
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND uid = :uid LIMIT 1")
    GroupMemberEntity getMember(String groupId, String uid);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMember(GroupMemberEntity member);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMembers(List<GroupMemberEntity> members);

    @WorkerThread
    @Query("DELETE FROM group_members WHERE groupId = :groupId AND uid = :uid")
    void deleteMember(String groupId, String uid);

    @WorkerThread
    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    void deleteMembersForGroup(String groupId);

    @WorkerThread
    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId")
    int getCount(String groupId);
}
