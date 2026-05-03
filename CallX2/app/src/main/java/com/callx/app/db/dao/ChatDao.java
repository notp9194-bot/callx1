package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.ChatEntity;

import java.util.List;

@Dao
public interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY lastMessageAt DESC")
    LiveData<List<ChatEntity>> getAllChats();

    /**
     * FIX v8: Blocking synchronous version of getAllChats().
     *
     * Use this on background threads (e.g., in SyncWorker.doWork()).
     * LiveData.getValue() returns null on background threads because no
     * lifecycle owner is observing — this @Query is safe everywhere.
     */
    @Query("SELECT * FROM chats ORDER BY lastMessageAt DESC")
    List<ChatEntity> getAllChatsSync();

    @Query("SELECT * FROM chats WHERE chatId = :chatId LIMIT 1")
    ChatEntity getChat(String chatId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChat(ChatEntity chat);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChats(List<ChatEntity> chats);

    @Query("UPDATE chats SET lastMessage = :msg, lastMessageAt = :ts WHERE chatId = :chatId")
    void updateLastMessage(String chatId, String msg, long ts);

    @Query("UPDATE chats SET unread = :count WHERE chatId = :chatId")
    void updateUnread(String chatId, long count);

    @Query("UPDATE chats SET muted = :muted WHERE chatId = :chatId")
    void updateMuted(String chatId, boolean muted);

    @Query("UPDATE chats SET pinned = :pinned WHERE chatId = :chatId")
    void updatePinned(String chatId, boolean pinned);

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    void deleteChat(String chatId);

    @Query("SELECT COUNT(*) FROM chats")
    int getChatCount();
}
