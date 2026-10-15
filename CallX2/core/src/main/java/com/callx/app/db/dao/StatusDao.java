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

    /** Active (non-expired) statuses as LiveData — for StatusViewModel. Excludes not-yet-published scheduled statuses. */
    @Query("SELECT * FROM statuses WHERE (CASE WHEN expiresAt IS NOT NULL AND expiresAt > 0 THEN expiresAt ELSE IFNULL(timestamp,0) + 86400000 END) > :now AND (deleted IS NULL OR deleted = 0) AND scheduledAt = 0 ORDER BY timestamp DESC")
    LiveData<List<StatusEntity>> getActiveStatusesLive(long now);

    /** Statuses by owner — for "My Status" row. Excludes not-yet-published scheduled statuses. */
    @Query("SELECT * FROM statuses WHERE ownerUid = :ownerUid AND (deleted IS NULL OR deleted = 0) AND scheduledAt = 0 ORDER BY timestamp ASC")
    LiveData<List<StatusEntity>> getStatusesByOwner(String ownerUid);

    /** Scheduled (not yet published) statuses for a given owner — for the "Scheduled statuses" screen. */
    @Query("SELECT * FROM statuses WHERE ownerUid = :ownerUid AND scheduledAt > 0 AND (deleted IS NULL OR deleted = 0) ORDER BY scheduledAt ASC")
    LiveData<List<StatusEntity>> getScheduledStatuses(String ownerUid);

    /** Scheduled statuses whose time has arrived — used by the publish worker. */
    @Query("SELECT * FROM statuses WHERE scheduledAt > 0 AND scheduledAt <= :now AND (deleted IS NULL OR deleted = 0)")
    List<StatusEntity> getStatusesDueForPublishing(long now);

    /** Fetch a single status by id (sync) — used by the publish worker / schedule actions. */
    @Query("SELECT * FROM statuses WHERE id = :statusId LIMIT 1")
    StatusEntity getStatusByIdSync(String statusId);

    /** Flip a scheduled status live: clear scheduledAt, stamp the real publish time. */
    @Query("UPDATE statuses SET scheduledAt = 0, timestamp = :publishedAt WHERE id = :statusId")
    void publishScheduledStatus(String statusId, long publishedAt);

    /** Cancel/remove a scheduled status outright (hard delete — it never went live). */
    @Query("DELETE FROM statuses WHERE id = :statusId")
    void deleteScheduledStatus(String statusId);

    // ── Sync queries ──────────────────────────────────────────────────────

    /** Active statuses sync — for background thread / Room preload. */
    @Query("SELECT * FROM statuses WHERE (CASE WHEN expiresAt IS NOT NULL AND expiresAt > 0 THEN expiresAt ELSE IFNULL(timestamp,0) + 86400000 END) > :now AND (deleted IS NULL OR deleted = 0)")
    List<StatusEntity> getActiveStatuses(long now);

    /** Last status timestamp per owner — for delta sync. */
    @Query("SELECT MAX(timestamp) FROM statuses WHERE ownerUid = :ownerUid")
    Long getLastTimestamp(String ownerUid);

    // ── Writes ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertStatuses(List<StatusEntity> statuses);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertStatus(StatusEntity status);

    /** IDs of all locally stored, already-published statuses — used to drop rows Firebase no longer has. */
    @Query("SELECT id FROM statuses WHERE scheduledAt = 0")
    List<String> getAllPublishedIds();

    @Query("DELETE FROM statuses WHERE id IN (:ids)")
    void deleteByIds(List<String> ids);

    /** Prune expired statuses. Pass System.currentTimeMillis() or 0 to prune all. */
    @Query("DELETE FROM statuses WHERE (CASE WHEN expiresAt IS NOT NULL AND expiresAt > 0 THEN expiresAt ELSE IFNULL(timestamp,0) + 86400000 END) <= :now")
    void pruneExpired(long now);

    /** Soft delete — keep row but mark deleted (matching Firebase). */
    @Query("UPDATE statuses SET deleted = 1 WHERE id = :statusId")
    void markDeleted(String statusId);

    /** Total active status count for a contact — for unread badge. */
    @Query("SELECT COUNT(*) FROM statuses WHERE ownerUid = :ownerUid AND (CASE WHEN expiresAt IS NOT NULL AND expiresAt > 0 THEN expiresAt ELSE IFNULL(timestamp,0) + 86400000 END) > :now AND (deleted IS NULL OR deleted = 0)")
    int getActiveCount(String ownerUid, long now);
}
