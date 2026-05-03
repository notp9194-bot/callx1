package com.callx.app.db.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/**
 * Room DB entity for cached messages.
 * Indexed on chatId + timestamp for fast query performance.
 */
@Entity(
    tableName = "messages",
    indices = {
        @Index(value = {"chatId", "timestamp"}),
        @Index(value = {"chatId", "starred"}),
        @Index(value = {"syncedAt"})
    }
)
public class MessageEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public String chatId;
    public String senderId;
    public String senderName;
    public String text;
    public String type;           // text | image | video | audio | file
    public String mediaUrl;
    public String thumbnailUrl;
    public String fileName;
    public Long   fileSize;
    public Long   duration;
    public Long   timestamp;
    public String status;         // sent | delivered | read
    public String replyToId;
    public String replyToText;
    public String replyToSenderName;
    public Boolean edited;
    public Long   editedAt;
    public Boolean deleted;
    public String forwardedFrom;
    public Boolean starred;
    public Boolean pinned;
    public Boolean isGroup;

    /** Last delta sync timestamp — used for incremental sync. */
    public long syncedAt;

    /**
     * v18 IMPROVEMENT 5: Offline media upload queue.
     * Image/video/file bhejne ka try offline karo — CloudinaryUploader fail hoga.
     * Local file URI yahan store karo. SyncWorker ke HEAVY pass mein retry karega:
     *   mediaLocalPath != null && mediaUrl == null → re-upload aur Firebase push.
     */
    public String mediaLocalPath;

    /**
     * v18 IMPROVEMENT 5: Media resource type — retry ke liye zaroori.
     * "image" | "video" | "raw" | "auto"
     */
    public String mediaResourceType;

    public MessageEntity() {}
}
