// ═══════════════════════════════════════════════════════════════════
// ADD THESE METHODS TO MessageDao.java
// (inside the existing @Dao interface, before the closing brace)
// ═══════════════════════════════════════════════════════════════════

    // ─────────────────────────────────────────────────────────────
    // IN-CHAT SEARCH
    // ─────────────────────────────────────────────────────────────

    /**
     * Full-text search within a single chat.
     *
     * Searches:
     *   - message text (text content)
     *   - file names (for file/document messages)
     *
     * Query format: use SQL LIKE wildcards, e.g. "%hello%"
     * Caller should wrap query: "%" + userInput + "%"
     *
     * Results ordered oldest → newest so they match the chat timeline.
     * Deleted messages are excluded.
     *
     * Usage from SearchInChatActivity:
     *   List<MessageEntity> results =
     *       db.messageDao().searchMessages(chatId, "%" + query + "%");
     */
    @WorkerThread
    @Query("SELECT * FROM messages " +
           "WHERE chatId = :chatId " +
           "AND deleted != 1 " +
           "AND (" +
           "    (text IS NOT NULL AND LOWER(text) LIKE LOWER(:query)) " +
           "    OR " +
           "    (fileName IS NOT NULL AND LOWER(fileName) LIKE LOWER(:query))" +
           ") " +
           "ORDER BY timestamp ASC " +
           "LIMIT 200")
    List<MessageEntity> searchMessages(String chatId, String query);

    /**
     * Search across ALL chats (for global search feature).
     * Returns latest 100 matches ordered by recency.
     *
     * Usage from future GlobalSearchActivity:
     *   List<MessageEntity> results =
     *       db.messageDao().searchAllChats("%" + query + "%");
     */
    @WorkerThread
    @Query("SELECT * FROM messages " +
           "WHERE deleted != 1 " +
           "AND (" +
           "    (text IS NOT NULL AND LOWER(text) LIKE LOWER(:query)) " +
           "    OR " +
           "    (fileName IS NOT NULL AND LOWER(fileName) LIKE LOWER(:query))" +
           ") " +
           "ORDER BY timestamp DESC " +
           "LIMIT 100")
    List<MessageEntity> searchAllChats(String query);

    /**
     * Count matching messages in a chat — used for result count badge.
     *
     * Usage:
     *   int count = db.messageDao().countSearchResults(chatId, "%" + query + "%");
     */
    @WorkerThread
    @Query("SELECT COUNT(*) FROM messages " +
           "WHERE chatId = :chatId " +
           "AND deleted != 1 " +
           "AND (" +
           "    (text IS NOT NULL AND LOWER(text) LIKE LOWER(:query)) " +
           "    OR " +
           "    (fileName IS NOT NULL AND LOWER(fileName) LIKE LOWER(:query))" +
           ")")
    int countSearchResults(String chatId, String query);

    /**
     * Get a specific message by timestamp — used for scroll-to after search tap.
     * Returns the message closest to the given timestamp.
     *
     * Usage from ChatActivity.scrollToMessage():
     *   MessageEntity msg = db.messageDao().getMessageByTimestamp(chatId, ts);
     */
    @WorkerThread
    @Query("SELECT * FROM messages " +
           "WHERE chatId = :chatId " +
           "AND timestamp = :timestamp " +
           "LIMIT 1")
    MessageEntity getMessageByTimestamp(String chatId, long timestamp);

// ═══════════════════════════════════════════════════════════════════
// ADD TO MessageDao.java — ALSO ADD THESE MISSING ONES FROM SyncWorker
// ═══════════════════════════════════════════════════════════════════

    /**
     * Returns messages with a local file path but no CDN URL yet.
     * SyncWorker uses this to find failed media uploads and retry them.
     *
     * A message ends up here when:
     *   1. User sends image/video while offline
     *   2. ChatActivity sets mediaLocalPath = local file URI
     *   3. CloudinaryUploader fails (no internet)
     *   4. SyncWorker picks it up when internet returns
     */
    @WorkerThread
    @Query("SELECT * FROM messages " +
           "WHERE mediaLocalPath IS NOT NULL " +
           "AND (mediaUrl IS NULL OR mediaUrl = '') " +
           "ORDER BY timestamp ASC")
    List<MessageEntity> getPendingMediaUploads();

    /**
     * Soft-delete a message by ID (keeps row in DB, just marks deleted=1).
     * Used by ChatViewModel.startRealtimeListener() on Firebase onChildRemoved.
     */
    @WorkerThread
    @Query("UPDATE messages SET deleted = 1 WHERE id = :msgId")
    void markDeleted(String msgId);

    /**
     * Get all unread messages in a chat from a specific sender.
     * ChatViewModel.markMessagesRead() uses this to update Firebase read receipts.
     */
    @WorkerThread
    @Query("SELECT * FROM messages " +
           "WHERE chatId = :chatId " +
           "AND senderId = :senderUid " +
           "AND (status IS NULL OR status != 'read')")
    List<MessageEntity> getUnreadMessages(String chatId, String senderUid);

    /**
     * LiveData stream of draft text for a chat.
     * ChatViewModel.getDraft() uses this so Activity auto-updates on restore.
     * (Requires ChatDao to have a getDraftLive method — see ChatEntity.draftText)
     */
    // Note: getDraftLive() is in ChatDao, not MessageDao. 
    // Add this to ChatDao.java instead:
    //
    // @Query("SELECT draftText FROM chats WHERE chatId = :chatId")
    // LiveData<String> getDraftLive(String chatId);

    /**
     * Insert or update a single message.
     * Named insertOrReplace to match ChatViewModel.messageToEntity() call site.
     */
    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplace(MessageEntity message);
