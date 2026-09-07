package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.OutboxOperationEntity;

import java.util.List;

@Dao
public interface OutboxOperationDao {

    @WorkerThread
    @Query("SELECT * FROM outbox_operations " +
            "WHERE state IN ('pending','failed') AND nextAttemptAt <= :now " +
            "ORDER BY createdAt ASC LIMIT :limit")
    List<OutboxOperationEntity> getDue(long now, int limit);

    @WorkerThread
    @Query("UPDATE outbox_operations SET state = 'pending', nextAttemptAt = :now, " +
            "updatedAt = :now, lastError = 'Recovered after interrupted attempt' " +
            "WHERE state = 'processing' AND updatedAt < :staleBefore")
    void recoverStaleProcessing(long staleBefore, long now);

    @WorkerThread
    @Query("SELECT * FROM outbox_operations WHERE id = :id LIMIT 1")
    OutboxOperationEntity get(String id);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(OutboxOperationEntity operation);

    @WorkerThread
    @Query("UPDATE outbox_operations SET state = :state, attemptCount = :attemptCount, " +
            "nextAttemptAt = :nextAttemptAt, updatedAt = :updatedAt, lastError = :lastError " +
            "WHERE id = :id")
    void updateAttempt(String id, String state, int attemptCount, long nextAttemptAt,
                       long updatedAt, String lastError);

    @WorkerThread
    @Query("DELETE FROM outbox_operations WHERE id = :id")
    void delete(String id);

    @WorkerThread
    @Query("DELETE FROM outbox_operations WHERE messageId = :messageId AND operationType = :type")
    void deleteForMessage(String messageId, String type);

    @WorkerThread
    @Query("DELETE FROM outbox_operations")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM outbox_operations " +
            "WHERE state IN ('pending','failed')")
    LiveData<Integer> observePendingCount();
}