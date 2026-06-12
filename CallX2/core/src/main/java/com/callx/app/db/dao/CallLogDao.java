package com.callx.app.db.dao;

import androidx.annotation.WorkerThread;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.CallLogEntity;

import java.util.List;

/**
 * v16: DAO for call history offline cache.
 */
@Dao
public interface CallLogDao {

    @WorkerThread
    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    List<CallLogEntity> getAllCallLogsSync();

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertCallLog(CallLogEntity log);

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertCallLogs(List<CallLogEntity> logs);

    @WorkerThread
    @Query("DELETE FROM call_logs WHERE id = :id")
    void deleteCallLog(String id);

    @WorkerThread
    @Query("SELECT COUNT(*) FROM call_logs")
    int getCount();

    /**
     * FIX #3: Purane call logs prune karo — sirf last 500 rakho.
     * SyncWorker heavy run pe call karega taaki DB size controlled rahe.
     */
    @WorkerThread
    @Query("DELETE FROM call_logs WHERE id NOT IN " +
           "(SELECT id FROM call_logs ORDER BY timestamp DESC LIMIT :keepCount)")
    int pruneOldLogs(int keepCount);

    /**
     * FIX #3: Ek specific cutoff timestamp se purane logs delete karo.
     * Auto-delete setting ke saath use hoga.
     */
    @WorkerThread
    @Query("DELETE FROM call_logs WHERE timestamp < :cutoffMs")
    int deleteLogsOlderThan(long cutoffMs);
}
