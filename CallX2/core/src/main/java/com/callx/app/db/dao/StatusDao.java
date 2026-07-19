package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.callx.app.db.entity.StatusEntity;
import java.util.List;

/**
 * StatusDao — Room queries for the status table.
 * Used by StatusRepository for offline-first status data.
 */
@Dao
public interface StatusDao {

    // ── Live queries ──────────────────────────────────────────────────────

    /** Active (non-expired) statuses as LiveData — for StatusViewModel. */
    @Query("SELECT * FROM statuses WHERE (expiresAt IS NULL OR expiresAt > :now) AND (deleted IS NULL OR deleted = 0) ORDER BY timestamp DESC")
    LiveData<List<StatusEntity>> getActiveStatusesLive(long now);

    /** Statuses by owner — for "My Status" row. */
    @Query("SELECT * FROM statuses WHERE ownerUid = :ownerUid AND (deleted IS NULL OR deleted = 0) ORDER BY timestamp ASC")
    LiveData<List<StatusEntity>> getStatusesByOwner(String ownerUid);

    // ── Sync queries ──────────────────────────────────────────────────────

    /** Active statuses sync — for background thread / Room preload. */
    @Query("SELECT * FROM statuses WHERE (expiresAt IS NULL OR expiresAt > :now) AND (deleted IS NULL OR deleted = 0)")
    List<StatusEntity> getActiveStatuses(long now);

    /** Last status timestamp per owner — for delta sync. */
    @Query("SELECT MAX(timestamp) FROM statuses WHERE ownerUid = :ownerUid")
    Long getLastTimestamp(String ownerUid);

    // ── Writes ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertStatuses(List<StatusEntity> statuses);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertStatus(StatusEntity status);

    /** Prune expired statuses. Pass System.currentTimeMillis() or 0 to prune all. */
    @Query("DELETE FROM statuses WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    void pruneExpired(long now);

    /** Soft delete — keep row but mark deleted (matching Firebase). */
    @Query("UPDATE statuses SET deleted = 1 WHERE id = :statusId")
    void markDeleted(String statusId);

    /** Total active status count for a contact — for unread badge. */
    @Query("SELECT COUNT(*) FROM statuses WHERE ownerUid = :ownerUid AND (expiresAt IS NULL OR expiresAt > :now) AND (deleted IS NULL OR deleted = 0)")
    int getActiveCount(String ownerUid, long now);
}
