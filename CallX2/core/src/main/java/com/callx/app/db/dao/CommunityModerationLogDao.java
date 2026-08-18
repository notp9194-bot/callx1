package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.CommunityModerationLogEntity;

import java.util.List;

/**
 * v31: DAO for moderation log entries.
 */
@Dao
public interface CommunityModerationLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertLog(CommunityModerationLogEntity log);

    @Query("SELECT * FROM community_moderation_logs WHERE communityId = :communityId ORDER BY createdAt DESC LIMIT 200")
    LiveData<List<CommunityModerationLogEntity>> observeLogs(String communityId);

    @Query("DELETE FROM community_moderation_logs WHERE communityId = :communityId AND createdAt < :olderThanMs")
    void pruneOld(String communityId, long olderThanMs);
}
