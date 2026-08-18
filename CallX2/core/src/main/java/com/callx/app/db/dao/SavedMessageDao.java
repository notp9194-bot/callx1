package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.SavedMessageEntity;

import java.util.List;

/**
 * SavedMessageDao — queries for saved_messages table.
 * Added in AppDatabase v38.
 */
@Dao
public interface SavedMessageDao {

    /** All saved messages, newest first. */
    @Query("SELECT * FROM saved_messages ORDER BY savedAt DESC")
    LiveData<List<SavedMessageEntity>> getAllSaved();

    /** Synchronous version for background threads. */
    @Query("SELECT * FROM saved_messages ORDER BY savedAt DESC")
    List<SavedMessageEntity> getAllSavedSync();

    /** Save a message (REPLACE = idempotent if same messageId). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSaved(SavedMessageEntity saved);

    /** Unsave a message by its original message ID. */
    @Query("DELETE FROM saved_messages WHERE id = :messageId")
    void deleteSaved(String messageId);

    /** Check if a message is already saved. */
    @Query("SELECT COUNT(*) FROM saved_messages WHERE id = :messageId")
    int isSaved(String messageId);

    /** Load a single saved message for editing the note. */
    @Query("SELECT * FROM saved_messages WHERE id = :messageId LIMIT 1")
    SavedMessageEntity getSaved(String messageId);

    /** Update the personal note for a saved message. */
    @Query("UPDATE saved_messages SET note = :note WHERE id = :messageId")
    void updateNote(String messageId, String note);

    /** Messages saved from a specific chat. */
    @Query("SELECT * FROM saved_messages WHERE origChatId = :chatId ORDER BY savedAt DESC")
    List<SavedMessageEntity> getSavedForChat(String chatId);

    /** Full-text search in saved messages. */
    @Query("SELECT * FROM saved_messages WHERE text LIKE :pattern ORDER BY savedAt DESC LIMIT 50")
    List<SavedMessageEntity> search(String pattern);

    @Query("SELECT COUNT(*) FROM saved_messages")
    int getTotalCount();

    @Query("DELETE FROM saved_messages")
    void deleteAll();
}
