package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.CommunityScheduledPostEntity;

import java.util.List;

/**
 * v31: DAO for community scheduled posts.
 */
@Dao
public interface CommunityScheduledPostDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertScheduled(CommunityScheduledPostEntity post);

    @Query("SELECT * FROM community_scheduled_posts WHERE communityId = :communityId AND status = 'pending' ORDER BY scheduledAt ASC")
    LiveData<List<CommunityScheduledPostEntity>> observeScheduled(String communityId);

    /** Used by WorkManager to find posts ready to publish. */
    @Query("SELECT * FROM community_scheduled_posts WHERE status = 'pending' AND scheduledAt <= :nowMs")
    List<CommunityScheduledPostEntity> getDuePosts(long nowMs);

    @Query("UPDATE community_scheduled_posts SET status = :status WHERE id = :id")
    void updateStatus(String id, String status);
}
