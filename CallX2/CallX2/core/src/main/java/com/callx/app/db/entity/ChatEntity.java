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
    public String partnerThumb;  // 100×100 WebP — chat list fast avatar

    public String lastMessage;
    public Long   lastMessageAt;
    public Long   unread;
    public Boolean muted;
    public Boolean pinned;

    /** v35: chat archived hai ya nahi — "Archived Chats" list ke liye. */
    public Boolean archived;

    /** v28: read receipts (ticks) + media label cache — mirrors User's
     *  lastMessageType/Status/SenderUid/Id so the chat list can render
     *  ticks and media labels instantly from Room, offline-first, before
     *  the Firebase listener re-syncs. See AppDatabase.MIGRATION_27_28. */
    public String lastMessageType;
    public String lastMessageStatus;
    public String lastMessageSenderUid;
    public String lastMessageId;

    /** Unix ms when this row was last refreshed from Firebase. */
    public long syncedAt;

    /**
     * v18 IMPROVEMENT 2: Draft message persist karo.
     * Jab user type kare aur navigate away kare, yahan save hoga.
     * Wapas aane par etMessage mein auto-restore hoga.
     */
    public String draft;

    /**
     * v18 IMPROVEMENT 4: Offline read receipt queue.
     * Offline hone par markRead() fail hota tha silently.
     * Ab agar offline hai toh pendingMarkRead = true set karo.
     * SyncWorker online hone par batch push karega.
     */
    public Boolean pendingMarkRead;

    // ── v38: Chat Folders / Labels ────────────────────────────────────────────

    /**
     * ID of the Chat Folder this chat belongs to.
     * NULL = shown in "All Chats" (not in any specific folder).
     * Set via ChatDao.setChatFolder(). Cleared when folder is deleted
     * via ChatDao.clearFolder(folderId).
     */
    public Integer folderId;

    /**
     * Comma-separated label tags attached to this chat, e.g. "work,important".
     * NULL = no labels. Max 5 recommended (UI truncates beyond that).
     * Labels are shown as small chips on the chat row in the chat list.
     */
    public String labels;

    public ChatEntity() {
        this.syncedAt = System.currentTimeMillis();
    }
}
