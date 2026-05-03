package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.UserEntity;

import java.util.List;

@Dao
public interface UserDao {

    @Query("SELECT * FROM users WHERE uid = :uid LIMIT 1")
    UserEntity getUser(String uid);

    @Query("SELECT * FROM users WHERE uid = :uid LIMIT 1")
    LiveData<UserEntity> getUserLive(String uid);

    @Query("SELECT * FROM users ORDER BY lastMessageAt DESC")
    LiveData<List<UserEntity>> getAllUsers();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertUser(UserEntity user);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertUsers(List<UserEntity> users);

    @Query("UPDATE users SET photoUrl = :url WHERE uid = :uid")
    void updatePhoto(String uid, String url);

    @Query("UPDATE users SET lastSeen = :ts WHERE uid = :uid")
    void updateLastSeen(String uid, long ts);

    @Query("UPDATE users SET fcmToken = :token WHERE uid = :uid")
    void updateFcmToken(String uid, String token);

    @Query("DELETE FROM users WHERE cachedAt < :cutoff")
    void pruneStale(long cutoff);

    @Query("SELECT COUNT(*) FROM users")
    int getUserCount();

    /** v17: Offline search — callxId se user dhundho */
    @Query("SELECT * FROM users WHERE LOWER(callxId) = LOWER(:callxId) LIMIT 5")
    List<UserEntity> searchByCallxId(String callxId);
}
