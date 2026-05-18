package com.callx.app.db.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.paging.PagingSource
import com.callx.app.db.entity.MessageEntity

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessages(chatId: String): LiveData<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesPagingSource(chatId: String): PagingSource<Int, MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC")
    fun getMessagesDesc(chatId: String): LiveData<List<MessageEntity>>

    // Delta Sync
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND timestamp > :lastTimestamp ORDER BY timestamp ASC")
    fun getMessagesSince(chatId: String, lastTimestamp: Long): List<MessageEntity>

    @Query("SELECT MAX(timestamp) FROM messages WHERE chatId = :chatId")
    fun getLastTimestamp(chatId: String): Long?

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessage(chatId: String): MessageEntity?

    // Starred Messages
    @Query("SELECT * FROM messages WHERE starred = 1 ORDER BY timestamp DESC")
    fun getStarredMessages(): LiveData<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE starred = 1 ORDER BY timestamp ASC")
    fun getStarredMessagesSync(): List<MessageEntity>

    // Write Operations
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND status = 'pending' ORDER BY timestamp ASC")
    fun getPendingMessages(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE mediaLocalPath IS NOT NULL AND (mediaUrl IS NULL OR mediaUrl = '') AND status = 'pending' ORDER BY timestamp ASC")
    fun getFailedMediaUploads(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status = 'pending' ORDER BY timestamp ASC")
    fun getAllPendingMessages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    @Update
    fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET deleted = 1, text = '' WHERE id = :messageId")
    fun softDelete(messageId: String)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    fun updateStatus(messageId: String, status: String)

    @Query("UPDATE messages SET starred = :starred WHERE id = :messageId")
    fun updateStarred(messageId: String, starred: Boolean)

    // Pruning
    @Query("DELETE FROM messages WHERE chatId = :chatId AND timestamp < (SELECT timestamp FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1 OFFSET :keepCount)")
    fun pruneOldMessages(chatId: String, keepCount: Int)

    @Query("DELETE FROM messages WHERE timestamp < :cutoffTimestamp AND deleted != 1")
    fun deleteMessagesOlderThan(cutoffTimestamp: Long): Int

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    fun deleteAllForChat(chatId: String)

    // Stats
    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    fun getMessageCount(chatId: String): Int

    @Query("SELECT COUNT(*) FROM messages")
    fun getTotalMessageCount(): Long

    @Query("DELETE FROM messages")
    fun deleteAll()
}
