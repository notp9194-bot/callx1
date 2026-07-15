package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.CommunityJoinRequestEntity;

import java.util.List;

/**
 * v31: DAO for community join requests.
 */
@Dao
public interface CommunityJoinRequestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertRequest(CommunityJoinRequestEntity request);

    @Query("SELECT * FROM community_join_requests WHERE communityId = :communityId AND status = 'pending' ORDER BY createdAt ASC")
    LiveData<List<CommunityJoinRequestEntity>> observePendingRequests(String communityId);

    @Query("SELECT COUNT(*) FROM community_join_requests WHERE communityId = :communityId AND status = 'pending'")
    LiveData<Integer> observePendingCount(String communityId);

    /** v33: sync check for "did I already request to join this community?" — used by the join gate. */
    @Query("SELECT COUNT(*) FROM community_join_requests WHERE communityId = :communityId AND requesterUid = :uid AND status = 'pending' AND groupId IS NULL")
    int countMyPendingSync(String communityId, String uid);

    @Query("UPDATE community_join_requests SET status = :status, processedAt = :processedAt, processedByUid = :processedByUid WHERE id = :requestId")
    void updateStatus(String requestId, String status, long processedAt, String processedByUid);

    @Query("DELETE FROM community_join_requests WHERE communityId = :communityId AND status != 'pending' AND processedAt < :olderThanMs")
    void pruneOld(String communityId, long olderThanMs);
}
