package com.callx.app.db.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.callx.app.db.entity.ChatEntity

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY lastMessageAt DESC")
    fun getAllChats(): LiveData<List<ChatEntity>>

    @Query("SELECT * FROM chats ORDER BY lastMessageAt DESC")
    fun getAllChatsSync(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE chatId = :chatId LIMIT 1")
    fun getChat(chatId: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChat(chat: ChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChats(chats: List<ChatEntity>)

    @Query("UPDATE chats SET lastMessage = :msg, lastMessageAt = :ts WHERE chatId = :chatId")
    fun updateLastMessage(chatId: String, msg: String, ts: Long)

    @Query("UPDATE chats SET unread = :count WHERE chatId = :chatId")
    fun updateUnread(chatId: String, count: Long)

    @Query("UPDATE chats SET muted = :muted WHERE chatId = :chatId")
    fun updateMuted(chatId: String, muted: Boolean)

    @Query("UPDATE chats SET pinned = :pinned WHERE chatId = :chatId")
    fun updatePinned(chatId: String, pinned: Boolean)

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    fun deleteChat(chatId: String)

    @Query("SELECT COUNT(*) FROM chats")
    fun getChatCount(): Int

    // Draft persist
    @Query("UPDATE chats SET draft = :draft WHERE chatId = :chatId")
    fun saveDraft(chatId: String, draft: String)

    @Query("SELECT draft FROM chats WHERE chatId = :chatId LIMIT 1")
    fun getDraft(chatId: String): String?

    // Offline read receipt queue
    @Query("UPDATE chats SET pendingMarkRead = 1, unread = 0 WHERE chatId = :chatId")
    fun queueMarkRead(chatId: String)

    @Query("SELECT * FROM chats WHERE pendingMarkRead = 1")
    fun getPendingMarkReadChats(): List<ChatEntity>

    @Query("UPDATE chats SET pendingMarkRead = 0 WHERE chatId = :chatId")
    fun clearPendingMarkRead(chatId: String)
}
