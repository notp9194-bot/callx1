package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/**
 * Room DB entity for chat metadata cache.
 * Covers both 1-on-1 and group chats.
 *
 * v20 additions:
 *   • archived        — hide from main list (WhatsApp-style archive)
 *   • disappearTimer  — disappearing messages duration (ms), 0 = off
 */
@Entity(
    tableName = "chats",
    indices = {
        @Index(value = {"lastMessageAt"}),
        @Index(value = {"type"}),
        @Index(value = {"archived"})   // v20: fast filter for archived chats
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
    public String partnerThumb;

    public String lastMessage;
    public Long   lastMessageAt;
    public Long   unread;
    public Boolean muted;
    public Boolean pinned;

    /** Unix ms when this row was last refreshed from Firebase. */
    public long syncedAt;

    /** v18: Draft message — auto-restored when reopening chat. */
    public String draft;

    /** v18: Offline read receipt queue. */
    public Boolean pendingMarkRead;

    /**
     * v20 NEW: Archive chat.
     * true  = chat hidden from main list, shown in "Archived" section.
     * false/null = normal (visible in main list).
     * Auto-unarchives on new message (configurable per WhatsApp behavior).
     */
    public Boolean archived;

    /**
     * v20 NEW: Disappearing messages timer for this chat (in milliseconds).
     * 0 or null = off.
     * Options: 86400000 (24h), 604800000 (7 days), 7776000000 (90 days).
     * When set, each new message's disappearAt = timestamp + disappearTimer.
     */
    public Long disappearTimer;

    public ChatEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
