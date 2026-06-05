package com.callx.app.db.dao;

  import androidx.annotation.WorkerThread;
  import androidx.lifecycle.LiveData;
  import androidx.paging.PagingSource;
  import androidx.room.Dao;
  import androidx.room.Insert;
  import androidx.room.OnConflictStrategy;
  import androidx.room.Query;
  import androidx.room.Update;

  import com.callx.app.db.entity.MessageEntity;

  import java.util.List;

  /**
   * Message DAO — all Room queries for messages table.
   *
   * v7 FIX: Restored original method names (insertMessage, insertMessages,
   * getMessageById, updateMessage, getFailedMediaUploads, getAllPendingMessages)
   * alongside v6 new methods (searchMessages, getExpiredMessages, deleteById).
   */
  @Dao
  public interface MessageDao {

      // ── Live / reactive ───────────────────────────────────
      @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
      LiveData<List<MessageEntity>> getMessages(String chatId);

      @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
      PagingSource<Integer, MessageEntity> getMessagesPagingSource(String chatId);

      @WorkerThread
      @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
      List<MessageEntity> getMessagesPaged(String chatId, int limit, int offset);

      // ── Delta sync ────────────────────────────────────────
      @WorkerThread
      @Query("SELECT * FROM messages WHERE chatId = :chatId AND timestamp > :lastTimestamp ORDER BY timestamp ASC")
      List<MessageEntity> getMessagesSince(String chatId, long lastTimestamp);

      @WorkerThread
      @Query("SELECT MAX(timestamp) FROM messages WHERE chatId = :chatId")
      Long getLastTimestamp(String chatId);

      @WorkerThread
      @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
      MessageEntity getLastMessage(String chatId);

      // ── Single message lookup ─────────────────────────────
      /** Used by SyncWorker and NotificationReplyWorker */
      @WorkerThread
      @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
      MessageEntity getMessageById(String id);

      // ── Insert / upsert ───────────────────────────────────
      /** Single insert — used by NotificationReplyWorker, SyncWorker */
      @Insert(onConflict = OnConflictStrategy.REPLACE)
      void insertMessage(MessageEntity msg);

      /** Batch insert — used by ChatRepository, CacheManager */
      @Insert(onConflict = OnConflictStrategy.REPLACE)
      void insertMessages(List<MessageEntity> msgs);

      /** Alias — also available as insert() for adapters that prefer it */
      @Insert(onConflict = OnConflictStrategy.REPLACE)
      void insert(MessageEntity msg);

      @Insert(onConflict = OnConflictStrategy.REPLACE)
      void insertAll(List<MessageEntity> msgs);

      // ── Update ops ────────────────────────────────────────
      /** Full entity update — used by SyncWorker (media upload completion) */
      @Update
      void updateMessage(MessageEntity msg);

      @Update
      void update(MessageEntity msg);

      @WorkerThread
      @Query("UPDATE messages SET status = :status WHERE id = :msgId")
      void updateStatus(String msgId, String status);

      @WorkerThread
      @Query("UPDATE messages SET deleted = 1, text = '', mediaUrl = NULL WHERE id = :msgId")
      void markDeleted(String msgId);

      @WorkerThread
      @Query("UPDATE messages SET starred = :starred WHERE id = :msgId")
      void setStarred(String msgId, boolean starred);

      @WorkerThread
      @Query("UPDATE messages SET pinned = :pinned WHERE id = :msgId")
      void setPinned(String msgId, boolean pinned);

      @WorkerThread
      @Query("UPDATE messages SET text = :newText, edited = 1, editedAt = :editedAt WHERE id = :messageId")
      void updateText(String messageId, String newText, long editedAt);

      // ── Pending / failed media uploads ────────────────────
      /**
       * Messages with local media path but no uploaded URL yet.
       * Used by SyncWorker to retry offline-queued uploads.
       */
      @WorkerThread
      @Query("SELECT * FROM messages WHERE mediaLocalPath IS NOT NULL AND mediaUrl IS NULL ORDER BY timestamp ASC LIMIT 50")
      List<MessageEntity> getFailedMediaUploads();

      /**
       * All messages with status 'sending' (not yet confirmed by Firebase).
       * Used by SyncWorker on reconnect.
       */
      @WorkerThread
      @Query("SELECT * FROM messages WHERE status = 'sending' ORDER BY timestamp ASC LIMIT 100")
      List<MessageEntity> getAllPendingMessages();

      // ── Starred ───────────────────────────────────────────
      @Query("SELECT * FROM messages WHERE chatId = :chatId AND starred = 1 ORDER BY timestamp DESC")
      LiveData<List<MessageEntity>> getStarredMessages(String chatId);

      @WorkerThread
      @Query("SELECT * FROM messages WHERE starred = 1 ORDER BY timestamp DESC")
      List<MessageEntity> getAllStarredMessages();

      // ── v6: In-chat search ────────────────────────────────
      @WorkerThread
      @Query("SELECT * FROM messages WHERE chatId = :chatId AND text LIKE :query AND (deleted IS NULL OR deleted = 0) ORDER BY timestamp DESC LIMIT 200")
      List<MessageEntity> searchMessages(String chatId, String query);

      // ── v6: Disappearing messages ─────────────────────────
      @WorkerThread
      @Query("SELECT * FROM messages WHERE expiresAt > 0 AND expiresAt <= :now")
      List<MessageEntity> getExpiredMessages(long now);

      @WorkerThread
      @Query("DELETE FROM messages WHERE id = :id")
      void deleteById(String id);

      // ── v6: Broadcast / bulk ops ──────────────────────────
      @WorkerThread
      @Query("SELECT * FROM messages WHERE chatId IN (:chatIds) ORDER BY timestamp DESC LIMIT :limit")
      List<MessageEntity> getRecentFromChats(List<String> chatIds, int limit);

      // ── Pruning / cleanup ─────────────────────────────────
      @WorkerThread
      @Query("DELETE FROM messages WHERE chatId = :chatId AND timestamp < (SELECT timestamp FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1 OFFSET :keepCount)")
      void pruneOldMessages(String chatId, int keepCount);

      @WorkerThread
      @Query("DELETE FROM messages WHERE timestamp < :cutoffTimestamp AND deleted != 1")
      int deleteMessagesOlderThan(long cutoffTimestamp);

      @WorkerThread
      @Query("DELETE FROM messages WHERE chatId = :chatId")
      void deleteAllForChat(String chatId);

      // ── Stats ─────────────────────────────────────────────
      @WorkerThread
      @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
      int getMessageCount(String chatId);

      @WorkerThread
      @Query("SELECT COUNT(*) FROM messages")
      long getTotalMessageCount();

      @WorkerThread
      @Query("DELETE FROM messages")
      void deleteAll();
  }