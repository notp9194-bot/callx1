package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.CommunityEventEntity;

import java.util.List;

/**
 * v31: DAO for community events.
 */
@Dao
public interface CommunityEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertEvent(CommunityEventEntity event);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertEvents(List<CommunityEventEntity> events);

    @Query("SELECT * FROM community_events WHERE communityId = :communityId ORDER BY startTimeMs ASC")
    LiveData<List<CommunityEventEntity>> observeEvents(String communityId);

    @Query("SELECT * FROM community_events WHERE communityId = :communityId AND startTimeMs >= :nowMs ORDER BY startTimeMs ASC")
    LiveData<List<CommunityEventEntity>> observeUpcomingEvents(String communityId, long nowMs);

    @Query("UPDATE community_events SET rsvpCount = :count WHERE id = :eventId")
    void updateRsvpCount(String eventId, long count);

    @Query("UPDATE community_events SET rsvpJson = :rsvpJson WHERE id = :eventId")
    void updateRsvpJson(String eventId, String rsvpJson);
}
