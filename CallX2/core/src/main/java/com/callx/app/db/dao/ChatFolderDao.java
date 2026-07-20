package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.callx.app.db.entity.ChatFolderEntity;

import java.util.List;

/**
 * ChatFolderDao — queries for chat_folders table.
 * Added in AppDatabase v38.
 */
@Dao
public interface ChatFolderDao {

    @Query("SELECT * FROM chat_folders ORDER BY sortOrder ASC, createdAt ASC")
    LiveData<List<ChatFolderEntity>> getAllFolders();

    @Query("SELECT * FROM chat_folders ORDER BY sortOrder ASC, createdAt ASC")
    List<ChatFolderEntity> getAllFoldersSync();

    @Query("SELECT * FROM chat_folders WHERE id = :id LIMIT 1")
    ChatFolderEntity getFolder(int id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertFolder(ChatFolderEntity folder);

    @Update
    void updateFolder(ChatFolderEntity folder);

    @Delete
    void deleteFolder(ChatFolderEntity folder);

    @Query("DELETE FROM chat_folders WHERE id = :id")
    void deleteFolderById(int id);

    @Query("SELECT COUNT(*) FROM chat_folders")
    int getFolderCount();

    @Query("UPDATE chat_folders SET sortOrder = :order WHERE id = :id")
    void updateSortOrder(int id, int order);
}
