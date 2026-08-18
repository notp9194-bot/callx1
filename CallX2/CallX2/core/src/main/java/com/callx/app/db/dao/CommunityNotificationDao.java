package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.CommunityNotificationEntity;

import java.util.List;

/**
 * v31: DAO for in-app community notifications.
 */
@Dao
public interface CommunityNotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertNotification(CommunityNotificationEntity notification);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertNotifications(List<CommunityNotificationEntity> notifications);

    @Query("SELECT * FROM community_notifications WHERE targetUid = :uid AND communityId = :communityId ORDER BY createdAt DESC LIMIT 50")
    LiveData<List<CommunityNotificationEntity>> observeNotificationsForCommunity(String uid, String communityId);

    @Query("SELECT COUNT(*) FROM community_notifications WHERE targetUid = :uid AND isRead = 0")
    LiveData<Integer> observeUnreadCount(String uid);

    @Query("UPDATE community_notifications SET isRead = 1 WHERE id = :notifId")
    void markRead(String notifId);

    @Query("UPDATE community_notifications SET isRead = 1 WHERE targetUid = :uid")
    void markAllRead(String uid);

    @Query("DELETE FROM community_notifications WHERE targetUid = :uid AND createdAt < :olderThanMs")
    void pruneOld(String uid, long olderThanMs);
}
