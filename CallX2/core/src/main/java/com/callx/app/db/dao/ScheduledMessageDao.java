package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.ScheduledMessageEntity;

import java.util.List;

/**
 * DAO for the local cache of pending scheduled messages.
 * See ScheduledMessageEntity for the table shape.
 */
@Dao
public interface ScheduledMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ScheduledMessageEntity entity);

    /** Drives the "Scheduled" banner — live count + soonest entry, ASC so
     *  the next-to-fire message is first. */
    @Query("SELECT * FROM scheduled_messages WHERE chatId = :chatId ORDER BY sendAt ASC")
    LiveData<List<ScheduledMessageEntity>> getForChat(String chatId);

    @WorkerThread
    @Query("SELECT * FROM scheduled_messages WHERE chatId = :chatId ORDER BY sendAt ASC")
    List<ScheduledMessageEntity> getForChatSync(String chatId);

    @WorkerThread
    @Query("SELECT * FROM scheduled_messages WHERE id = :id LIMIT 1")
    ScheduledMessageEntity getById(String id);

    @WorkerThread
    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    void deleteById(String id);
}
