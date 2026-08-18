package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * SavedMessageEntity — Global "Saved Messages" feature.
 *
 * Telegram-style: any message from any chat can be saved here.
 * Shows in GlobalSavedMessagesActivity, accessible from the chat list
 * menu or the bottom nav. Unlike per-chat "starred", this is a
 * cross-chat personal bookmarks store.
 *
 * Added in AppDatabase MIGRATION_37_38.
 */
@Entity(
    tableName = "saved_messages",
    indices = {
        @Index(value = {"savedAt"}),
        @Index(value = {"origChatId"})
    }
)
public class SavedMessageEntity {

    /** The original message ID — also used as PK so saving the same
     *  message twice is idempotent (REPLACE conflict strategy). */
    @PrimaryKey
    @NonNull
    public String id = "";

    /** chatId / groupId where this message came from. */
    public String origChatId;

    /** Display name of the source chat (partner name or group name). */
    public String chatName;

    /** Whether the source chat was a group. */
    public boolean isGroup;

    /** UID of the message sender. */
    public String senderUid;

    /** Display name of sender. */
    public String senderName;

    /** Avatar URL of sender. */
    public String senderPhoto;

    /** Message text content. */
    public String text;

    /** Message type: text | image | video | audio | file | etc. */
    public String type;

    /** Media URL (image/video/audio/file). */
    public String mediaUrl;

    /** Thumbnail URL for images/videos. */
    public String thumbnailUrl;

    /** File name for document messages. */
    public String fileName;

    /** Duration in seconds for audio/video. */
    public Long duration;

    /** Original message timestamp (ms). */
    public Long origTimestamp;

    /** When the user saved this message (ms). */
    public Long savedAt;

    /** Optional personal note the user can attach. */
    public String note;

    /** Reactions JSON (mirrors MessageEntity.reactionsJson). */
    public String reactionsJson;

    /** Quoted reply text snippet (if the original message was a reply). */
    public String replyToText;

    /** Quoted reply sender name. */
    public String replyToSenderName;

    public SavedMessageEntity() {}
}
