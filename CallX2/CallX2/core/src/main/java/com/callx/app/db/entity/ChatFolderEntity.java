package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * ChatFolderEntity — Room entity for Telegram-style Chat Folders.
 *
 * Each folder can include chats by type (contacts/groups) and/or
 * by an explicit JSON list of chatIds. The UI renders a horizontal
 * scrollable chip row above the chat list — tapping a chip filters
 * the list to that folder's chats.
 *
 * Added in AppDatabase MIGRATION_37_38.
 */
@Entity(tableName = "chat_folders")
public class ChatFolderEntity {

    @PrimaryKey(autoGenerate = true)
    public int id;

    /** Display name, e.g. "Work", "Family", "Unread" */
    public String name;

    /** Emoji icon shown on the chip, e.g. "💼", "👨‍👩‍👧", "⭐" */
    public String emoji;

    /** Position in the horizontal chip row (lower = leftmost). */
    public int sortOrder;

    /**
     * JSON array of explicit chatIds included in this folder, e.g.
     * ["chat_abc123", "chat_def456"]. Null or empty = rule-based only.
     */
    public String chatIdsJson;

    /** Include ALL private 1-on-1 chats. */
    public boolean includeContacts;

    /** Include ALL group chats. */
    public boolean includeGroups;

    /** Include ALL non-contacts. */
    public boolean includeNonContacts;

    /** Include muted chats (default false = muted chats hidden from folder). */
    public boolean includeMuted;

    /** Filter to only unread chats when true. */
    public boolean includeUnreadOnly;

    /** Unix ms when this folder was created. */
    public long createdAt;

    public ChatFolderEntity() {}
}
