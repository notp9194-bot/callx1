package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/**
 * Room DB entity for chat metadata cache.
 * Covers both 1-on-1 and group chats.
 */
@Entity(
    tableName = "chats",
    indices = {
        @Index(value = {"lastMessageAt"}),
        @Index(value = {"type"})
    }
)
public class ChatEntity {

    @PrimaryKey
    @NonNull
    public String chatId = "";

    /** "private" or "group" */
    public String type;

    public String partnerUid;
    public String partnerName;
    public String partnerPhoto;

    public String lastMessage;
    public Long   lastMessageAt;
    public Long   unread;
    public Boolean muted;
    public Boolean pinned;

    /** Unix ms when this row was last refreshed from Firebase. */
    public long syncedAt;

    public ChatEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
